# 跨轮上下文共享设计：复用 AgentTaskContext，不再自建平行体系

- 状态：**已实现**
- 涉及仓库：`j-langchain`（`AgentTaskContext`/`SlidingWindowContext`）、`regnexe-agent`（`TaskExecutionState`/`ExecutionOutput`/`CapabilityExecutor`/`Reflector`/`TaskPlanner`）
- 关联文档：取代 11 号文档（`state.toolExecutions` 任务级列表 + Reflector 里的批量压缩）。10 号文档（单次工具结果溢出保护）不受影响，继续生效。

---

## 一、问题：11 号方案是在 AgentTaskContext 之外，重新发明了一遍历史记录

`McpAgentExecutor` 本身已经有一套现成的、按 `AgentContext`/`AgentTaskContext` 组织的执行历史机制（`recentSteps`/`earlyStepsSummary`，`SlidingWindowContext` 实现），专门负责"Execute 内部循环该看到多少历史、超出窗口怎么压缩"。11 号文档没有复用它，而是在 `TaskExecutionState` 上另起了一个平行的 `toolExecutions`/`earlyRoundsSummary`，自己记录、自己压缩——本质上是把 `AgentTaskContext` 已经解决过的问题，在 regnexe 这一层用不同的数据结构和不同的压缩算法重新做了一遍。

根源在一个容易搞错的地方：`CapabilityExecutor` 每轮都把同一个 `agentContext`（`AgentContext` 工厂实例）传给 `McpAgentExecutor.Builder.context(...)`，容易让人以为"实例相同 = 历史跨轮共享"。实际上 `AgentContext.create(question, systemPrompt)` 每次调用都返回一个全新、空的 `AgentTaskContext`（j-langchain 自己的 javadoc 就写着"fresh"）；`CapabilityExecutor` 过去用的是两参数 `invoke(agentInput, stopSignal)`，从未把 `preloadedCtx` 传进去，所以即便工厂实例相同，实际执行历史每轮都从零开始，`AgentContext` 层面完全没有跨轮共享。

## 二、方案：真正接入 AgentTaskContext 的 resume 通道，压缩逻辑完全交给它

j-langchain 早就为 resume 场景预留了口子——`McpAgentExecutor.invoke(input, stopSignal, preloadedCtx)`：传入一个预先构建好、已经 `addStep()` 过若干历史步骤的 `AgentTaskContext`，执行器会在它的基础上继续，而不是新建一个空的。这次的改动就是让 `CapabilityExecutor` 每轮都走这条路。

### j-langchain：给 `AgentTaskContext` 补两个读写口子

`getCompletedSteps()` 只能拿到压缩窗口里还没被淘汰的 `recentSteps`，压缩产生的摘要文本（`SlidingWindowContext.Session.earlyStepsSummary`）完全没有对外暴露——不补上这个口子，跨进程持久化时这部分历史会直接丢失。新增两个默认方法：

```java
// AgentTaskContext.java
default String getEarlyStepsSummary() { return null; }
default void restoreSummary(String summary) { }
```

`SlidingWindowContext.Session` 直接读写自己现成的字段实现这两个方法；`restoreSummary` 只是赋值，不重新调用 summarizer。不支持压缩的实现（如默认的 `FullContext`）用接口默认值即可，不用改。

### regnexe-agent：`TaskExecutionState` 只存"数据"，不存"对象"

```java
// TaskExecutionState 新增
private List<AgentStep> priorSteps;       // 上一轮结束时 ctx.getCompletedSteps() 的快照
private String priorStepsSummary;         // 上一轮结束时 ctx.getEarlyStepsSummary() 的快照

// 删除（11 号引入的）
// private List<ToolExecutionRecord> toolExecutions;
// private String earlyRoundsSummary;
```

`AgentTaskContext` 本身不能整个存进 `state`：`TaskExecutionState` 每轮都整体 JSON 序列化落 SQLite（`SqliteTaskStore`），而 `AgentTaskContext` 的具体实现（如 `SlidingWindowContext.Session`）是非静态内部类，隐式持有外层 `SlidingWindowContext`（进而是一个活的 `BaseChatModel summarizer`）的引用——这部分是运行时接线，不是数据，不该也没法被序列化。`AgentStep` 则是干净的 `@Data` POJO（`AIMessage`/`List<BaseMessage>`/`String`），能正常序列化，是 `AgentTaskContext` 里真正该跨轮持久化的那一半。

### CapabilityExecutor：每轮显式构建、回放、回存

```java
String originalTask = state.getRequest().getGoal();   // 稳定不变的"人类原始诉求"
AgentTaskContext ctx = agentContext.create(originalTask, null);
if (priorSummary != null) ctx.restoreSummary(priorSummary);
if (priorSteps != null) priorSteps.forEach(ctx::addStep);

result = executor.invoke(agentInput, stopSignal, ctx);   // 三参数版本，带 preloadedCtx

// 不论成功/失败/重试耗尽，统一回存——ctx 是同一个对象引用，McpAgentExecutor 内部
// 已经在其上调用过 addStep()，即使中途抛异常，已完成的步骤也已经反映在 ctx 里
state.setPriorSteps(new ArrayList<>(ctx.getCompletedSteps()));
String updatedSummary = ctx.getEarlyStepsSummary();
if (updatedSummary != null) state.setPriorStepsSummary(updatedSummary);
```

`originalTask` 固定用整体任务目标（`state.getRequest().getGoal()`），不用本轮 `agentInput`——因为一旦传入非空 `preloadedCtx`，`McpAgentExecutor` 内部会走 `ctx.addHumanTurn(question)` 而不是 `create(question, ...)`（`McpAgentExecutor.java:434-443`），`question` 就是这次 `invoke()` 传的 `agentInput`。如果 `originalTask` 也用 `agentInput`，会导致同一段文本作为 `originalTask` 和 `resumeInput` 出现两次。固定用整体目标，本轮的具体指令统一走 `addHumanTurn` 这一条路径进入对话历史，不重复。

一个连带的好处：重试循环（`MAX_EXECUTE_RETRIES`）里多次尝试复用同一个 `ctx` 对象——如果第一次尝试在部分工具调用成功后失败，第二次尝试会在 `ctx` 里已经看到这些步骤，降低了"重试导致重复副作用（比如重复插库）"的风险，比 11 号方案的纯"整段重跑"更安全一点。

### `buildAgentInput()` 不再手工拼"Progress so far"

11 号方案里 `CapabilityExecutor.renderProgressSoFar()` 手工把 `earlyRoundsSummary` + 早期轮次的原始记录拼成一段文本塞进 `agentInput`。现在这段完全删除——`AgentTaskContext.buildMessages()` 已经把 `earlyStepsSummary` + 回放的 `recentSteps` 组织成真实的对话消息（system/human/AI-tool-call/tool-result 轮流出现），比手工拼文本更贴近模型原生的多轮对话理解方式,不需要 regnexe 自己再渲染一遍。

### Reflector 的交叉校验：改回"per-round"，不再共享

`AgentStep`/`BaseMessage` 是通用的对话消息，没有 `round`/能力类型标签这些 regnexe 特有的元信息，没法直接拿来做"这一轮到底调了哪些工具"的判断。这部分职责跟"跨轮共享给 Execute 的历史"是两件不同的事，拆开处理：

```java
// ExecutionOutput 重新加回（11 号里删过一次）
private List<ToolExecutionRecord> toolExecutions;   // 只存这一轮自己的调用，不再是任务级共享列表
```

`CapabilityExecutor` 里的 `List<ToolExecutionRecord> toolExecutions` 局部变量不再指向 `state` 共享列表，变回每轮全新的本地列表，跑完一轮存进 `output.setToolExecutions(...)`。`Reflector` 的 guard 规则和交叉校验相应地改成读 `round.getExecutionResult().getToolExecutions()`（这一轮自己的），不再需要按 `round` 字段过滤一个任务级大列表。

### Reflector 里原有的批量压缩机制整个删除

`compactToolExecutionsIfNeeded()`/`ROUND_COMPACT_PERIOD`/`COMPACT_SYSTEM_PROMPT`/`concatSummary()` 全部移除——压缩这件事现在完全在 `AgentContext` 实现内部发生（`SlidingWindowContext.Session.addStep()` 超出 `windowSize` 时自动压缩一步），regnexe 不再自己判断"该不该压、什么时候压"，只负责每轮存取 `priorSteps`/`priorStepsSummary`。

### TaskPlanner

`earlyRoundsSummary` → `priorStepsSummary`，字段来源变了（现在是 `AgentTaskContext` 的压缩摘要，不是 Reflector 批量压缩的产物），但用途不变：还是作为"更早轮次的压缩摘要"混进 Planner 的 "Progress so far" 段落，跟最近几轮的 `finalText` 拼在一起。

## 三、需要注意的行为变化

- **压缩节奏从"按轮"变成"按 Execute 内部步数，且跨轮连续计数"**。`SlidingWindowContext.windowSize` 数的是 `AgentStep`（Execute 内部一次工具调用轮转），不是 Planner/Reflector 意义上的"轮"。11 号方案里"攒够 5 轮才压一次"是批量式；现在只要跨整个任务累计的步数超过 `windowSize` 就会压缩一步，是滚动式，且窗口范围从"只在本轮内计数、每轮结束清零"变成"跨轮持续累积"。长任务下触发压缩（调用一次 summarizer LLM）的频率会比 11 号方案更高。这是有意接受的权衡：跨轮共享的诉求优先级更高，如果后续发现压缩调用频率是个真实问题，可以再给 j-langchain 加一个"整批压缩"风格的 `AgentContext` 实现（仿照 `PeriodicConversationSummaryMemoryStorer`），本次改动的接口（`getEarlyStepsSummary`/`restoreSummary`）对任何 `AgentContext` 实现都通用，不需要再改 regnexe 这边。
- **`FullContext`（默认无压缩）用户完全不受影响**——`getEarlyStepsSummary()`/`restoreSummary()` 走接口默认值，等价于没有这次改动。只有显式配置了 `SlidingWindowContext`（`regnexe-cli` 的 `CliMain.java:707` 就是这么配的）才会有压缩行为。
- **round 1 的 `originalTask` 略有不同**：由于每轮现在都统一走"外部构建 ctx → 传入 preloadedCtx"这条路径（不再有"round 1 走 create()，round 2+ 走 preloadedCtx"的分支），round 1 的 `originalTask` 也固定为整体目标，而不是 round 1 自己的 `agentInput`——跟 round 2+ 保持一致，是行为统一，不是遗留差异。

## 四、验证方式

`j-langchain`/`regnexe-agent` 全量编译通过（`mvn compile`/`test-compile`）。真实多轮任务验证待跑（下一步）。
