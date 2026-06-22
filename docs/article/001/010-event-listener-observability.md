# Event Listener：让 Agent 执行全过程可观测

企业系统需要可观测性。

对于 Agent 来说，可观测性尤其重要。因为 Agent 的执行过程包含模型推理、能力选择、工具调用、结果反思等多个环节，如果没有事件记录，系统就会变成黑盒。

Regnexe 提供 Event Listener 机制，让 Agent 的关键节点都能被监听。

## Agent 执行有哪些事件

一次完整任务中，可能出现这些事件：

- Agent Start
- Search Input
- Search Result
- Plan Input
- Plan Result
- Execute Input
- Tool Call
- Tool Result
- Execute Result
- Reflect Input
- Reflect Result
- Agent Done

这些事件让执行过程清晰可见。

```text
[Agent Start   ] R0 Goal: 查询成都天气并安排三天商务行程
[Search Result ] R1 Found 2 capabilities: get_weather, travel_planner
[Plan Result   ] R1 Selected: [get_weather, travel_planner]
[TOOL Call     ] R1 get_weather {"city": "成都"}
[TOOL Result   ] R1 get_weather -> 多云转阴，18~25 C
[Reflect Result] R1 FINISH
```

这比只看最终答案更有价值。

## 接入日志和控制台

开发阶段可以使用 `ConsoleEventListener`，直接在控制台观察执行过程。

```java
RegnexeAgent agent = regnexeAgentBuilder
        .withEventListener(new ConsoleEventListener())
        .build();
```

这对于调试非常直接。

开发者可以看到：

- Search 是否找到正确能力
- Planner 是否选择了正确能力
- Tool 参数是否构造正确
- Tool 返回是否符合预期
- Reflect 是否正确判断完成

## 接入监控和前端 UI

生产环境中，Event Listener 可以对接：

- 日志平台
- 监控系统
- 链路追踪
- 任务看板
- 前端实时执行界面
- 审计系统

例如在前端展示：

```text
正在搜索能力...
已选择：天气查询、行程规划
正在调用天气工具...
正在生成最终行程...
任务完成
```

这能显著提升用户信任感。

用户不再只是等待一个黑盒结果，而是能看到 Agent 正在做什么。

## 支持 Token 和能力级统计

除了业务事件，Event Listener 还可以接收模型响应和 token 使用信息。

企业可以基于这些信息做：

- 成本统计
- 模型调用监控
- 能力级消耗分析
- 慢任务排查
- 异常任务告警

这对规模化运营非常重要。

当 Agent 数量和任务数量增加后，成本和性能监控会成为平台能力的一部分。

## 商业价值

第一，提高透明度。

业务方和开发者可以看到 Agent 每一步做了什么。

第二，提升可调试性。

执行异常时，可以快速定位是 Search、Plan、Execute 还是 Reflect 出了问题。

第三，便于产品化。

事件流可以驱动前端 UI，让用户看到任务进度。

第四，支持运营监控。

可以统计任务量、耗时、失败率、工具调用次数和模型成本。

第五，满足审计要求。

企业可以记录关键工具调用和结果，支撑合规审查。

## 结语

Agent 要进入企业生产环境，必须可观测。

Regnexe 的 Event Listener 让 Agent 执行过程从黑盒变成事件流。

这就是 Regnexe 的第十项核心商业价值：

**它让 Agent Start、Search、Plan、Tool Call、Tool Result、Reflect 等过程都能被监听，方便接日志、监控、控制台和前端 UI。**
