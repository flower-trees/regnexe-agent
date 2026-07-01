# 多工具调用只是开始：Regnexe 如何构建会反思的 Java Agent

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/00-intro.md)。

在 Java AI Agent 项目里，多工具调用通常是第一步。

但多工具调用并不等于 Agent。很多实现只是把 Function Calling 做成循环：模型决定 ToolCall，框架执行工具，再把 Observation 写回上下文。

这类能力很重要，也足够支撑很多查询型助手。但如果任务稍微复杂一点，还会遇到一个更关键的问题：

```text
当前结果是否足够完成用户目标？
```

Regnexe 这篇示例关注的就是这个问题：如何把多个工具接入一个具备 Search、Plan、Execute、Reflect 闭环的 Re-Planning Agent。

这不是为了把“工具调用”包装成更大的概念，而是为了让 Agent 在执行过程中具备一个明确的完成度检查点：**结果不够，就继续规划；结果足够，才允许结束**。

## 示例任务

用户目标：

```text
Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.
```

这个目标至少需要两个信息：

- 北京今天的天气
- 北京今天的空气质量

如果只查天气就回答“适合跑步”，这个答案是不完整的。

这类问题在业务系统里很常见：

- 报销审核不能只查制度，还要查票据和额度
- 旅行规划不能只查天气，还要查交通和住宿
- 运维排障不能只查日志，还要查指标和变更记录
- 客服处理不能只查订单，还要查库存、售后政策和用户状态

多工具任务真正麻烦的地方，不是“能不能调多个工具”，而是“缺了一个关键结果时，Agent 能不能意识到自己没做完”。

## 对比：McpAgentExecutor 的多步 ToolCall

以 `j-langchain` 的 `McpAgentExecutor` 为参照，它支持直接通过 `.tools(...)` 挂载多个 `Tool`。

同样是天气 + 空气质量任务，可以先定义两个工具：

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String")
    .func(args -> "Beijing: sunny, 22 C.")
    .build();

Tool airQualityTool = Tool.builder()
    .name("get_air_quality")
    .description("Get today's air quality index (AQI) for a city.")
    .params("city: String")
    .func(args -> "Beijing: AQI 35, excellent air quality.")
    .build();
```

再交给 `McpAgentExecutor`：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(weatherTool, airQualityTool)
    .systemPrompt("你是一个户外运动助手，可以调用天气和空气质量工具后回答用户问题。")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> ToolCall: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke(
    "Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running."
);
```

典型运行过程：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.
>> ToolCall: get_air_quality {"city":"Beijing"}
>> Observation: Beijing: AQI 35, excellent air quality.

=== 最终答案 ===
北京今天晴，22°C，AQI 35，空气质量很好，适合户外跑步。
```

这类模式解决的是“模型能否连续调用工具”。对于查询类助手、MCP 工具助手，这是非常实用的抽象。

它封装掉了很多样板逻辑：

- 工具描述注册
- ToolCall 解析
- 工具执行
- Observation 写回上下文
- 多轮 ToolCall 控制
- 最终答案生成

但它和 Re-Planning Agent 仍有区别。

如果模型只拿到了：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.
```

然后直接回答：

```text
北京今天晴，22°C，适合户外跑步。
```

从 ToolCall 循环角度看，这可能已经结束；但从任务目标角度看，空气质量没有被检查。

这就是普通多步工具调用和 Re-Planning Agent 的分界点。

## 三种模式的边界

| 模式 | 核心思路 | 优点 | 局限 | 适合场景 |
|---|---|---|---|---|
| Workflow | 程序员写死执行步骤 | 稳定、可控、好测试 | 流程变化需要改代码，不会自己补缺失步骤 | 固定流程、审批流、明确 SOP |
| ReAct / Function Calling Loop | 模型在循环里决定 ToolCall | 灵活，能完成多步工具调用 | 主要依赖模型连续决策，完成度检查不够显式 | 查询助手、轻量多步工具调用 |
| Re-Planning Agent | Search → Plan → Execute → Reflect，目标未完成就继续规划 | 计划、执行、反思边界清楚，可观察性更强 | 框架更重，需要更清晰的能力描述 | 多工具、多能力、结果必须检查的任务 |

Regnexe 的定位是 Agent Harness，而不是简单的工具调用封装。

这里的 Harness 可以理解为：它不只负责“把工具交给模型”，还负责组织任务执行过程，包括能力搜索、计划生成、执行调度和完成度检查。

## Regnexe 的执行闭环

Regnexe 将任务执行拆成四个阶段：

```text
Search   找能力
Plan     规划本轮要调用哪些能力
Execute  执行工具 / Skill / Sub-Agent
Reflect  检查目标是否完成
```

四步是一轮。如果 Reflect 判断目标没有完成，则进入下一轮规划。

因此，它关注的不只是“工具有没有被调用”，还包括“调用结果是否足以完成目标”。

对于本文这个任务，Reflect 要检查的不是“天气工具有没有返回”，而是：

```text
是否同时拿到了天气和空气质量，并且已经基于两者给出跑步建议？
```

如果只拿到天气，Reflect 就不应该结束任务。

## Regnexe 实战代码

仓库中的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) 注册了两个工具：

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String -- city name")
    .func(city -> "Beijing: sunny, 22 C.")
    .build();

Tool airQualityTool = Tool.builder()
    .name("get_air_quality")
    .description("Get today's air quality index (AQI) for a city.")
    .params("city: String -- city name")
    .func(city -> "Beijing: AQI 35, excellent air quality.")
    .build();
```

`Tool.builder()` 描述的是一个可调用能力：

- `name`：工具名，模型和 Planner 识别能力时会用到
- `description`：工具用途，影响模型是否选择该工具
- `params`：参数说明，影响模型如何构造调用参数
- `func`：真正执行的函数

然后把两个工具交给 Regnexe：

```java
AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool, airQualityTool)
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());
```

这里没有在业务代码里写死：

- 先调用 `get_weather`
- 再调用 `get_air_quality`
- 如果缺空气质量再补一次
- 最后如何合并两个结果

业务代码只给出目标和能力，执行过程交给 Agent Harness。

## 运行日志

接入 `ConsoleEventListener` 后，可以看到完整执行过程：

```text
[Agent Start   ] R0 Goal: Check today's weather and air quality in Beijing... | maxRounds: 3
[Search Result ] R1 Found 2 capabilities: get_weather, get_air_quality
[Plan Result   ] R1 Selected: [get_weather, get_air_quality] | Strategy: SYNTHESIZE | ...
[TOOL Call     ] R1 get_weather {"city": "Beijing"}
[TOOL Result   ] R1 get_weather -> Beijing: sunny, 22 C.
[TOOL Call     ] R1 get_air_quality {"city": "Beijing"}
[TOOL Result   ] R1 get_air_quality -> Beijing: AQI 35, excellent air quality.
[Execute Result] R1 SUCCESS | Sunny, 22°C, AQI 35 — great conditions for a run.
[Reflect Result] R1 FINISH — both readings obtained and the goal is fully answered.
[Agent Done    ] R1 Status: FINISHED | Rounds: 1
```

这段日志体现了完整的执行闭环：

- Search 找到天气和空气质量两个能力
- Plan 决定本轮两个工具都要调用
- Execute 执行工具并收集结果
- Reflect 确认目标已完成

如果只获得天气结果，Reflect 就不应该结束任务，而应该让 Agent 继续规划。

这也是 Regnexe 这类 Agent Harness 的工程价值：它让每一步决策都有日志可观察，而不是把所有事情都藏在一次模型调用里。

## withTool 适合什么场景

`withTool(Tool...)` 是 Regnexe 中最快的接入方式，适合：

- PoC 阶段快速验证多工具 Agent
- 工具逻辑比较简单，不想为了一个函数新建类和注解
- 工具来自配置、数据库或运行时动态生成
- 想先观察 Search / Plan / Execute / Reflect 完整链路

如果工具数量继续增加，需要分组、标签、版本、权限控制、独立发布，就不应该一直堆在 `withTool` 里。后续文章会介绍 `@Plugin`、插件打包和 Marketplace。

## 工具描述细节

`Tool.builder()` 中的 `params` 建议认真写：

```java
.params("city: String -- city name")
```

它不是普通注释，而是会进入工具描述，影响模型如何构造调用参数。

建议统一成：

```text
参数名: 类型 -- 说明
```

工具越多，描述越重要。Planner 要从工具名、描述、参数中判断：

- 当前目标是否需要这个工具
- 参数应该如何从用户输入中抽取
- 是否需要和其他工具组合使用
- 当前结果是否足够回答用户问题

## 适用边界

Regnexe 的 Re-Planning Agent 更适合这类任务：

- 目标不止一步
- 需要多个工具或多个能力协作
- 中间结果可能不完整
- 需要日志和事件观察执行过程
- 需要在结果不足时重新规划

如果任务本身是固定流程，例如“先校验参数，再写库，再发消息”，workflow 仍然是更稳的方案。

如果任务只是简单查询，例如“查一下今天北京天气”，普通 ToolCall 循环已经足够。

框架选择不应该只看抽象是否高级，而要看任务是否需要这层能力。

## 小结

`withTool(Tool...)` 是接入 Regnexe 最快的方式，但这篇的重点不是“少写几行代码”。

更重要的是：Regnexe 把多工具调用放进了一个可观察、可反思、可重新规划的 Agent Harness 中。

`McpAgentExecutor / ReAct Loop` 解决连续工具调用。

`Regnexe / Re-Planning Agent` 解决目标完成度检查和重新规划。

对于需要多工具、多能力协作，并且结果必须被验证的任务，后者会更接近生产中的 Agent 需求。

---

如果这篇内容对你有帮助，欢迎去仓库点个 Star，支持一下国内的开源 Agent 框架项目。

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/00-intro.md) ｜ 下一篇：[02. Skill 设计：为什么强制不让配模型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
