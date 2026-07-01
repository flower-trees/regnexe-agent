# 多工具调用只是开始：如何用 Regnexe 构建真正会反思的 Java Agent？

先说结论：如果只是让模型连续调用几个工具，那更像 ReAct / Function Calling Loop；真正值得称为 Re-Planning Agent 的，是它能在执行后检查目标是否完成，发现缺信息时继续规划。

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md)。

## 为什么不是“调两个工具”这么简单

假设用户问：

```text
Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.
```

很多代码会写成这样：

```java
String weather = getWeather("Beijing");
String air = getAirQuality("Beijing");
return llm.summarize(weather, air);
```

这当然能跑，但它不是 Agent 在规划，只是程序员提前写好了调用顺序。

如果用户换一种更自然的问法：

```text
北京今天适不适合户外跑步？
```

这里隐含了天气和空气质量两个维度。如果只查天气，答案可能不完整；如果只查空气质量，也不完整。

所以问题不是“能不能调工具”，而是：

> Agent 能不能知道自己还缺什么？

## 先区分三种东西

这类问题很容易混在一起：workflow、ReAct、Re-Planning Agent。

| 模式 | 解决什么 | 典型特点 |
|---|---|---|
| Workflow | 固定流程自动化 | 程序员写死步骤，稳定但不灵活 |
| ReAct / Function Calling Loop | 让模型连续调用工具 | 模型决定 ToolCall，工具结果写回上下文 |
| Re-Planning Agent | 检查任务是否真的完成 | Search、Plan、Execute、Reflect 分层，没完成继续规划 |

三者不是替代关系，而是不同抽象层级。

固定 SOP 用 workflow 很好。

查询型助手用 ReAct / Function Calling Loop 也很自然。

但如果任务目标必须被检查，比如“天气 + 空气质量都要拿到后才能判断是否适合跑步”，就需要更明确的 Reflect / Re-Planning。

## McpAgentExecutor 能完成多步 ToolCall，但问题还没结束

以 `j-langchain` 的 `McpAgentExecutor` 为例，它可以把手写 Function Calling 循环封装起来。

同样是这个任务，可以先定义两个工具：

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

然后接入 `McpAgentExecutor`：

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

理想情况下，过程会是：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.
>> ToolCall: get_air_quality {"city":"Beijing"}
>> Observation: Beijing: AQI 35, excellent air quality.

=== 最终答案 ===
北京今天晴，22°C，AQI 35，空气质量很好，适合户外跑步。
```

这类封装非常有价值，因为它解决了很多工程样板：ToolCall 解析、工具执行、Observation 写回、多轮调用控制。

但它主要关注的是“模型能不能连续调工具”。

更难的是另一件事：

```text
如果模型只查了天气，没有查空气质量，它知不知道自己没做完？
```

比如只出现了：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.

=== 最终答案 ===
北京今天晴，22°C，适合户外跑步。
```

这在 ToolCall 层面可能已经结束了，但从用户目标看，它少了空气质量判断。

## Regnexe 的核心：把 Reflect 放进执行循环

Regnexe 的执行过程可以简化成四步：

```text
Search  找能力
Plan    规划这轮要调用哪些能力
Execute 执行工具 / Skill / Sub-Agent
Reflect 检查目标是否真的完成
```

如果 Reflect 判断目标没有完成，就不会直接返回，而是进入下一轮，再 Search / Plan / Execute。

所以这里的 Re-Planning 不是外层手写 while-loop，而是框架把“结果是否足够、是否需要继续”纳入执行闭环。

## 代码：把两个工具交给 Regnexe

仓库里的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) 很短：

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

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool, airQualityTool)
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());
```

这里没有写：

- 先调用 `get_weather`
- 再调用 `get_air_quality`
- 最后把两个结果合并
- 如果少了空气质量再补一次

你只是给了目标和可用能力，剩下的交给 Agent 的规划和反思闭环。

## 运行时发生了什么

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

这段日志里有几个关键点：

- Search 找到了两个相关能力
- Plan 选择两个工具，而不是只选一个
- Execute 执行两个工具调用
- Reflect 检查“天气 + 空气质量”都拿到了，才允许结束

这就是 Re-Planning Agent 和普通多步 ToolCall 的区别。

## 为什么 Reflect 很关键

很多工具调用框架的问题在于：模型调完工具后就开始回答，至于任务是否真的完成，没人再检查。

但多工具任务里，“结果是否足够”非常重要。

用户问“适不适合跑步”，天气只是一个维度，空气质量也是一个维度。模型如果只查天气就回答“适合”，表面上像完成了任务，其实缺了一块判断依据。

Regnexe 把 Reflect 放进闭环里，就是为了避免这种“看起来答了，其实没做完”的情况。

## 一个容易忽略的细节：工具描述很重要

`Tool.builder()` 里的 `params` 不是给人看的注释，它会影响模型怎么构造工具调用参数。

```java
.params("city: String -- city name")
```

我更建议统一成这种格式：

```text
参数名: 类型 -- 说明
```

工具越多，模型越依赖描述判断“这个工具能不能用、参数该怎么填、是否要和其他工具一起用”。

## 小结

这篇的重点不是“少写几行代码”，而是：

> Regnexe 让你用最短路径构建一个真正有规划和反思闭环的多工具 Re-Planning Agent。

`McpAgentExecutor / ReAct Loop` 解决的是连续工具调用。

`Regnexe / Re-Planning Agent` 解决的是目标完成度检查和重新规划。

你现在做的 Agent，是“调用一次工具后回答”，还是会检查目标没完成就重新规划？

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md) ｜ 下一篇：[02. Skill 和 Sub-Agent 到底有什么本质区别](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
