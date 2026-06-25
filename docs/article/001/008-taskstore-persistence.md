# TaskStore：让 Agent 任务状态可保存、可恢复、可审计

企业不会接受一个完全黑盒的 Agent。

在生产环境中，业务方和技术团队经常需要知道：

- 当前任务执行到哪一步
- 已经调用了哪些能力
- 每一轮的计划是什么
- 哪些工具返回了什么结果
- 为什么任务继续或结束
- 失败时发生在哪个环节

如果这些信息只存在内存里，Agent 就很难用于长流程、审计和问题排查。

Regnexe 通过 `TaskStore` 保存任务状态，让 Agent 执行过程可持久化。

## 任务状态不只是最终答案

很多系统只保存最终回答。

但企业更关心过程。

一个 Agent 任务可能经历多轮：

```text
R1 Search → Plan → Execute → Reflect
R2 Search → Plan → Execute → Reflect
R3 Finish
```

每一轮都有自己的输入、候选能力、计划、执行结果和反思判断。

这些过程信息对于排查非常重要。

例如最终答案漏掉了合同分析，开发者需要知道：

- Search 是否找到了合同能力
- Planner 是否选择了合同能力
- Execute 是否调用成功
- Reflect 是否发现遗漏

只有保存过程状态，才能真正解释 Agent 行为。

## TaskExecutionState：任务执行快照

Regnexe 的任务状态由 `TaskExecutionState` 承载。

它包含：

- taskId
- sessionId
- 原始请求
- 当前状态
- 当前轮次
- 每轮记录
- 最近工具结果
- 创建和更新时间

这些信息可以通过 `TaskStore` 保存。

```java
RegnexeAgent agent = regnexeAgentBuilder
        .withTaskStore(taskStore)
        .build();
```

当 Search、Plan、Execute、Reflect 推进时，任务状态会持续更新。

## 支持暂停恢复

TaskStore 对 Pause & Resume 尤其关键。

任务暂停后，系统需要知道：

- 哪个 session 有暂停任务
- 暂停前执行到哪一轮
- 已经有哪些工具结果
- 恢复时应该带上哪些上下文

如果没有 TaskStore，Resume 就只能依赖内存对象。一旦进程重启或请求跨节点，就会丢失状态。

TaskStore 是长流程 Agent 的基础。

## 支持审计和排查

企业系统出现问题时，不能只看最终答案。

例如业务人员问：

> 为什么 Agent 没有分析条款 5？

有了任务状态，可以回看：

```text
[Search Result] Found: get_weather, contract_analyzer, travel_planner
[Plan Result] Selected: get_weather, travel_planner
[Reflect Result] CONTINUE: 缺少合同分析
```

这能帮助团队判断问题发生在搜索、规划、执行还是反思阶段。

没有 TaskStore，这些信息很容易散落在日志里，难以系统化管理。

## 可以接入不同存储实现

TaskStore 是抽象接口。

早期可以使用内存实现：

```java
TaskStore taskStore = new InMemoryTaskStore();
```

生产环境可以替换为：

- 数据库
- Redis
- 对象存储
- 企业审计系统
- 自定义状态服务

这让 Regnexe 可以适配不同企业架构，而不是绑定某一种存储。

## 商业价值

第一，任务过程可追踪。

企业可以看到每一轮发生了什么，而不是只看到最终答案。

第二，支持长流程恢复。

暂停任务、异步任务、跨请求任务都需要状态存储。

第三，便于审计。

谁发起任务、调用了哪些能力、产生了什么结果，都可以被记录。

第四，便于问题排查。

当 Agent 输出不符合预期时，可以定位问题发生在哪个阶段。

第五，适合平台化运营。

Agent 平台可以基于 TaskStore 做任务列表、状态看板、失败重试和执行历史。

## 结语

企业 Agent 不是只要回答正确，还要过程可查、状态可存、问题可追。

Regnexe 的 TaskStore 让任务状态从临时内存变成可管理数据。

这就是 Regnexe 的第八项核心商业价值：

**它让 Agent 执行过程可持久化，便于恢复、审计和排查。**
