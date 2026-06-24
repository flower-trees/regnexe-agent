# Java 调用大模型工具，真的需要为每个工具建一个类吗？

先说结论：不需要。这其实是被很多框架的"标准流程"惯出来的习惯——建包、建类、加注解、注册 Bean，一套流程走完，工具本身的逻辑可能只有一行代码。

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md)。

## 这个"标准流程"到底在解决什么问题

我理解为什么很多框架要求建类——工具一多，确实需要命名空间、需要分组、需要版本管理。但这是**规模化之后**才需要解决的问题。如果你现在只是想验证一个想法、跑个 PoC，强行套用"建类三件套"纯粹是负担。

Regnexe 把这两件事分开看：**先解决"怎么跑起来"，再解决"怎么管理"**。这篇讲第一件事。

## 怎么做到不建类的

核心就两个东西：`Tool.builder()` 构造一个工具对象，`withTool(...)` 注册进去。仓库里的 `ExampleReadme01MultiToolTest` 注册了两个工具——查天气、查空气质量——然后交给 Agent 自己决定怎么组合：

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
    .withTool(weatherTool, airQualityTool)          // 可变参数，一次注册多个
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());          // FINISHED
```

`withTool` 是可变参数，塞几个工具都行，不需要逐个 `.install()`。

## 真正值得关注的不是代码量，是 Agent 的"自主性"

接上 `ConsoleEventListener` 之后，把 Search → Plan → Execute → Reflect 每一步打到控制台，你会看到一个挺有意思的事：

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

我们没有写"先查天气再查空气质量"的顺序，也没有写"两个结果怎么合并"。Planner 自己识别出目标里有"weather and air quality"两个诉求，选中了两个工具；Reflect 确认两项数据都拿到了，才判定 `FINISH`——如果只调了一个工具就想结束，Reflect 会把它打回去要求继续。

这才是"建类与否"背后真正的重点：**框架有没有给 Agent 留出自主判断的空间**，而不是工具注册这一步省了几行代码。

## 一个容易忽略的细节

`Tool.builder()` 的 `params` 字段不是注释，它会被直接拼进喂给模型的工具描述里，影响模型怎么构造调用参数。我自己测试下来，统一写成 `参数名: 类型 -- 说明` 这种格式，模型遵循度明显更高——这种小细节在 Demo 阶段不会暴露问题，量大了之后差异就很明显。

## 什么时候不该用 withTool

- 临时脚本、PoC 验证、动态生成的工具——适合
- 工具数量多到需要打包管理、加标签、做版本控制——不适合，下一篇讲的 `@Plugin` 注解才是这个阶段该用的东西

不管走哪条注册路径，背后驱动 Search→Plan→Execute→Reflect 的都是同一套 harness——`withTool` 只是接进这套 harness 最快的一个入口，不是另一套体系。

---

你们团队接入大模型工具调用，是直接上注解体系，还是先用最简单的方式验证？评论区聊聊。

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md) ｜ 下一篇：[02. Skill 和 Sub-Agent 到底有什么本质区别](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
