# 事件的调用父子关系设计：AgentEvent 携带 spanId / parentSpanId

- 状态：**第一步已实现**（只改 `regnexe-agent`）
- 涉及仓库：`regnexe-agent`（`AgentEvent`、各 Worker、`RegnexeAgent`）、`regnexe-cli`（`OpikEventListener`）
- 第二步（未做）：`j-langchain` 的 `McpAgentExecutor` 回调结构化

---

## 一、问题

`AgentEvent` 只有 `taskId / round / type / text / timestamp`，不表达"谁调用了谁"。`regnexe-cli` 的 `OpikEventListener` 要把事件还原成调用树，只能靠推断：

1. **靠事件顺序。** 用一个栈，假设"还没结束的工具调用就是当前最内层"。工具或子 agent 一旦并行，父节点就会配错，而且不报错。
2. **靠解析文本。** 子 agent 的作用域是从 `"[sub_agent:xxx] ..."` 这种前缀里看出来的。
3. **每个消费方各推一遍。** 以后接别的观测后端，或者把调用树落库，都要重复这套逻辑。

另外还有两个相关缺陷：

- **`/resume` 产生一条新 trace。** 恢复执行和原来那次只通过 `task_id` 关联，看不成一棵树。
- **循环抛异常时不发 `AGENT_COMPLETED`。** `RegnexeAgent.runLoop` 在 catch 里直接 rethrow，消费方收不到任务结束，根节点永远不闭合。

## 二、方案

发事件的地方本来就知道自己在哪个作用域里，由框架在发事件时把父子关系填好，消费方直接用。

### 2.1 AgentEvent 新增两个字段

| 字段 | 含义 |
|---|---|
| `spanId` | 这个事件所属的调用。开启一次调用的事件带一个新 id；结束或补充这次调用的事件带同一个 id |
| `parentSpanId` | 这次调用的父调用。只在开启事件上有意义；根调用为 `null` |

两个字段都可为空，旧的监听器不读它们，行为不变。事件类型和 `text` 的格式都不改。

id 的格式沿用 OpenTelemetry：`spanId` 是 16 位十六进制，`traceId` 是 32 位十六进制，消费方可以原样发给 OTLP 后端。

### 2.2 一次调用由哪些事件组成

| 调用 | 开启事件 | 同一 `spanId` 的后续事件 | 父 |
|---|---|---|---|
| 任务（首次执行） | `AGENT_STARTED` | `TASK_TOKEN_SUMMARY`、`AGENT_COMPLETED` | 无 |
| 任务（恢复执行） | `AGENT_STARTED` | 同上 | 原任务的根 span |
| 阶段 search / plan / execute / reflect | `*_STARTED` | `*_COMPLETED` | 本次运行的 span |
| Plan / Reflect 的 LLM 调用 | `PLAN_LLM_RESPONDED` / `REFLECT_LLM_RESPONDED` | `LLM_OUTPUT`、`TOKEN_USAGE` | 所在阶段 |
| 执行循环的 LLM 调用 | `LLM_RESPONDED` | `TOKEN_USAGE` | execute 阶段 |
| 执行循环的工具调用 | `TOOL_CALLED` | `TOOL_RESULT` | execute 阶段 |
| 子 agent 内的 LLM 调用 | `AGENT_LLM_RESPONDED` | `CAPABILITY_TOKEN_USAGE` | 调起该子 agent 的那次工具调用 |
| 子 agent 内的工具调用 | `TOOL_CALLED` | `TOOL_RESULT` | 同上 |
| 直接调用 skill（`executeSkill`） | `SKILL_LLM_RESPONDED` / `TOOL_CALLED` | `CAPABILITY_TOKEN_USAGE` / `TOOL_RESULT` | 任务根 span |

`SEARCH_COMPLETED` 在复用缓存时没有对应的 `SEARCH_STARTED`，此时它单独带一个新 `spanId`，消费方当作零时长的调用处理。

### 2.3 trace 和根 span 的 id 由 taskId 确定

```
traceId    = SHA-256(taskId) 的前 32 位十六进制
rootSpanId = SHA-256("root:" + taskId) 的前 16 位十六进制
```

由 `SpanIds.traceId(taskId)` / `SpanIds.rootSpanId(taskId)` 提供。其余 span 的 id 随机生成。

用确定值而不是随机值，是为了 `/resume`：恢复执行可能发生在另一个进程里，它必须能算出原任务的 trace 和根 span，才能把自己挂上去。

### 2.4 `/resume` 挂到原 trace 下

- `execute()`：`AGENT_STARTED` 带 `spanId = rootSpanId(taskId)`，`parentSpanId = null`。
- `resume()`：`AGENT_STARTED` 带一个新的 `spanId`，`parentSpanId = rootSpanId(taskId)`。之后各阶段的父都是这个新 span。

消费方看到 `AGENT_STARTED` 的 `parentSpanId` 非空，就知道这是一次恢复执行。一棵树长这样：

```
task                       首次执行
├─ search R1 / plan R1 / execute R1 / reflect R1
└─ resume                  恢复执行（可以有多次，都是根的子节点）
   └─ search R2 / plan R2 / execute R2 / reflect R2
```

### 2.5 异常时补发 AGENT_COMPLETED

`runLoop` 的 catch 在 rethrow 之前发 `AGENT_COMPLETED`（`Status: FAILED | <异常信息>`）。副作用是失败的任务现在也会有 `TASK_TOKEN_SUMMARY`，CLI 会打印 token 汇总行，这是期望的行为。

### 2.6 框架内部怎么维护

- **本次运行的 span**：`RegnexeAgent` 生成，放进 `ContextBus`（`ContextBusKeys.RUN_SPAN_ID`），各 Worker 取出来当阶段的父。
- **阶段 span**：各 Worker 的 `process()` 里生成一个局部变量，`*_STARTED` 和 `*_COMPLETED` 共用。
- **循环内的 LLM / 工具调用**：新增 `LoopSpans`，每个循环作用域（外层执行循环、每个子 agent、直接调用的 skill）一个实例，记录"当前这次 LLM 调用"和"当前这次工具调用"的 id。子 agent 的 `LoopSpans` 以外层循环"当前工具调用"为父。

## 三、这一步没有解决的

`LoopSpans` 仍然依赖一个假设：**同一个循环作用域内，LLM 调用和工具调用是串行的。** 具体是两处：

1. `TOKEN_USAGE` 归到"最近开启的那次 LLM 调用"，`TOOL_RESULT` 归到"最近开启的那次工具调用"。
2. 子 agent 的父是外层循环"此刻正在进行的工具调用"。

原因是 `McpAgentExecutor` 的 `onLlm / onToolCall / onObservation` 回调只传字符串，没有 tool call id，框架拿不到更可靠的配对依据。

和改造前相比，这个假设从"所有消费方各自承担"收窄到"框架内一处"，并且子 agent 的归属不再靠解析文本前缀。要彻底去掉它，需要第二步：`j-langchain` 的回调改成结构化对象，带上 tool call id、工具名、参数、结果和模型原始回复。那一步同时能解决"执行循环的 LLM 输出只能近似"的问题。

## 四、消费方（regnexe-cli 的 OpikEventListener）的变化

- 去掉栈和按深度索引的推断，改为按 `spanId` 建一张"未结束的 span"表。
- `traceId` 用 `SpanIds.traceId(taskId)`，不再随机。
- 恢复执行：根 span 不重发（Opik 对同一 trace 的第二个根 span 返回 409），只发 `resume` 及其子 span；结束后通过 Opik 的 REST 接口更新原 trace 的输出和结束时间。如果原 trace 不存在（首次执行时没开追踪，或进程被强杀导致根 span 没发出去），则补发一个根 span，保证树是完整的。

以上关于 Opik 的行为都实测过：晚到的子 span 会并入已有 trace；子 span 可以先于根 span 到达；重发根 span 被拒绝；trace 可以按 `metadata.task_id` 查到并用 `PATCH` 更新。

## 五、验证情况

- 单元测试：`LoopSpansTest`（id 的确定性和格式、同一调用的事件共用 spanId、子循环挂在外层当前工具调用下）。
- 端到端：用一个本地脚本模拟 OpenAI 兼容接口（不调用真实模型），跑 regnexe-cli 的完整流程，从 Opik 读回 trace 核对：
  - 普通任务：task → 四个阶段 → LLM / 工具调用，层级正确。
  - 任务未完成后在新进程里 `/resume`：`resume` 及其各阶段挂在原 trace 的根 span 下，trace 的输出、结束时间和 token 合计随之更新。
  - 原任务没有被追踪时 `/resume`：补发根 span，树完整。
- 未做端到端验证：子 agent 嵌套（只有单元测试覆盖）、直接调用 skill、`runLoop` 抛异常时补发的 `AGENT_COMPLETED`。
