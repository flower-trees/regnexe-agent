# 多工具调用只是开始！用 Regnexe 构建真正会反思的 Java Agent

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/00-intro.md)。

很多人做 Java Agent，第一步都是让大模型调用工具。

但我要先泼一盆冷水：**多工具调用只是开始，不代表你已经做出了 Agent**。

如果只是把工具塞给模型，让模型连续调几次，这更像 ReAct / Function Calling Loop。它很有用，但它解决的是“模型能不能连续调工具”。

真正进入 Agent 层面以后，还要多问一句：

```text
这件事真的做完了吗？
如果只完成了一半，能不能继续规划下一步？
```

这篇就用一个很小的例子讲清楚：**Regnexe 如何用 Search → Plan → Execute → Reflect，把两个工具接成一个会检查目标完成度的 Re-Planning Agent**。

## 痛点：多工具任务，最怕“看起来完成了”

用户问：

```text
Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.
```

很多项目第一反应是手写流程：

```java
String weather = getWeather("Beijing");
String air = getAirQuality("Beijing");
String answer = llm.summarize(weather, air);
```

这段代码没错，但它是 workflow。调用顺序是程序员提前写死的，不是 Agent 自己规划出来的。

真实用户还可能只说：

```text
北京今天适不适合户外跑步？
```

这里至少隐含两个信息需求：天气和空气质量。

如果只查天气就回答“适合”，看起来像完成了任务，实际上缺了空气质量这个关键依据。

所以多工具 Agent 真正难的地方不是“能不能调用两个函数”，而是：

- 能不能从目标里识别出缺哪些信息？
- 能不能选择合适工具？
- 能不能检查结果是否足够？
- 不够时能不能继续规划下一轮？

## 多步 ToolCall 能跑，不代表 Agent 真的会做完

拿 `j-langchain` 里的 `McpAgentExecutor` 举个参照。它可以把手写 Function Calling 循环封装掉，让模型用很少代码完成多步工具调用。

同样是天气 + 空气质量这个任务，可以这样定义两个工具：

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

然后通过 `.tools(...)` 接入 `McpAgentExecutor`：

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

正常情况下，它可能跑出这样的链路：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.
>> ToolCall: get_air_quality {"city":"Beijing"}
>> Observation: Beijing: AQI 35, excellent air quality.

=== 最终答案 ===
北京今天晴，22°C，AQI 35，空气质量很好，适合户外跑步。
```

这类封装非常有价值，它解决了 Function Calling 循环里的很多样板工作：工具描述注册、ToolCall 解析、工具执行、Observation 写回、多轮调用控制。

但注意，它主要解决的是：

```text
模型能不能连续调工具？
```

而 Re-Planning Agent 要解决的是：

```text
模型调完之后，知不知道任务是否真的完成？
```

如果模型只查了天气：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.

=== 最终答案 ===
北京今天晴，22°C，适合户外跑步。
```

在 ToolCall 层面，这可能是一次正常结束；但在任务目标层面，它少了空气质量判断。

这就是普通多步工具调用和 Re-Planning Agent 的差别。

## Workflow、ReAct、Re-Planning Agent 怎么区分

| 模式 | 核心思路 | 优点 | 短板 | 适合场景 |
|---|---|---|---|---|
| Workflow | 程序员写死流程，模型只负责局部生成 | 稳定、可控、好测试 | 流程变化就要改代码，不会自己补缺失步骤 | 固定业务流程、审批流、明确 SOP |
| ReAct / Function Calling Loop | 模型在循环里决定下一次 ToolCall | 灵活，能完成多步工具调用 | 计划结构和完成度检查不够显式 | 查询助手、MCP 工具助手、轻量多步调用 |
| Re-Planning Agent | Search → Plan → Execute → Reflect，没完成就重新规划 | 能力搜索、计划、执行、反思分层清楚 | 框架更重，需要清晰定义能力边界 | 多工具、多能力、多轮任务，结果必须被检查 |

所以这三者不是谁替代谁，而是抽象层级不同。

任务固定，用 workflow。

只想让模型连续调工具，用 ReAct / Function Calling Loop。

如果你关心“目标有没有真的完成”“缺的信息能不能被发现”“没做完能不能继续规划”，那就是 Re-Planning Agent 的场景。

## Regnexe 的做法：Search → Plan → Execute → Reflect

Regnexe 的执行链路是四步闭环：

```text
Search   找能力：从工具、Skill、Sub-Agent 中找到可能有用的能力
Plan     排计划：决定这一轮调用哪些能力、怎么组合
Execute  执行：真正调用工具并收集结果
Reflect  反思：检查目标是否完成，没完成就继续下一轮
```

Reflect 是关键。

如果目标里明确要求 weather and air quality，但结果只有 weather，Reflect 就不应该放行，而应该触发下一轮规划。

## 实战代码：两个工具，交给 Agent 自己规划

仓库里的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) 注册两个工具：

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

注意，这里没有手写：

- 先调 `get_weather`
- 再调 `get_air_quality`
- 少了数据再补一次
- 最后综合两个结果

你只提供目标和能力，剩下的交给 Agent 的执行闭环。

## 运行日志：不是黑盒

接上 `ConsoleEventListener` 后，可以看到完整链路：

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

这里最重要的不是“调用了两个工具”，而是：

- Search 找到了相关能力
- Plan 选择本轮要用的工具
- Execute 执行并收集结果
- Reflect 检查“天气 + 空气质量”都齐了，才允许结束

这就是 Regnexe 和普通 ToolCall 循环的差别：它把“完成度检查”放进了 Agent 主循环。

## 一个实现细节：params 不是注释

`Tool.builder()` 里的 `params` 会进入工具描述，影响模型构造参数：

```java
.params("city: String -- city name")
```

建议统一成：

```text
参数名: 类型 -- 说明
```

工具越多，描述越重要。Planner 要从工具名、描述、参数中判断“这个工具能不能用、参数怎么填、和其他工具怎么组合”。

## 小结

多工具调用只是开始。

`McpAgentExecutor / ReAct Loop` 解决的是“模型能不能连续调工具”。

`Regnexe / Re-Planning Agent` 解决的是“模型调完以后，知不知道任务是否真的完成，以及不完整时能不能重新规划”。

如果你的 Agent 只是调完工具就回答，它还停留在工具调用器阶段。

如果它会检查目标完成度，并在缺信息时继续规划，它才开始接近真正的 Agent。

---

你现在做的 Agent，是 ToolCall 循环，还是会反思和重新规划的 Re-Planning Agent？欢迎评论区聊聊。

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/00-intro.md) ｜ 下一篇：[02. Skill：为什么它"只能借工具不能占"](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent ，欢迎 Star
