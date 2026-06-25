# withTool 极简接入：不建类、不写注解，几行代码注册多个工具

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/00-intro.md)。

## 不少框架的"标准接入流程"成本偏高

接一个新工具，很多框架的标准动作是：建包、建类、加注解、注册 Bean、改配置，一套流程走完，工具本身的逻辑可能只有一行代码。如果只是想先验证一个想法、跑个 PoC，这个成本就显得不太划算。

Regnexe 把这条路径压到了最短：不需要类，不需要注解，一个 `Tool.builder()` + 一个 `withTool(...)`，直接完事。

## 实战代码

仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) 注册了两个工具——查天气、查空气质量——然后交给 Agent 自己决定怎么组合：

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

## 运行效果：自主决策才是重点

接上 `ConsoleEventListener`，把 Search → Plan → Execute → Reflect 每一步打到控制台：

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

我们没有写"先查天气再查空气质量"的顺序，也没有写"两个结果怎么合并"。Planner 自己识别出目标里有两个诉求，选中两个工具；Reflect 确认两项数据都拿到了，才判定 `FINISH`。如果只调了一个工具就想结束，Reflect 会把它打回去要求继续——这就是单次工具调用方案做不到的地方。

## 一个实现细节

`Tool.builder()` 的 `params` 字段不是注释，它会被直接拼进喂给模型的工具描述里，影响模型怎么构造调用参数。建议统一写成 `参数名: 类型 -- 说明` 这种格式，模型遵循度会明显更高。

## 适用场景

`withTool` 适合临时脚本、PoC 验证、动态生成的工具（比如从配置或数据库拼出来的 `Tool` 对象）。如果工具数量多到需要打包管理、加标签、做版本控制，那就是后面要讲的 `@Plugin` 注解该登场的时候了。

不管走哪条注册路径，背后驱动 Search→Plan→Execute→Reflect 的都是同一套 harness，`withTool` 只是接进这套 harness 最快的一个入口。

---

如果这篇内容对你有帮助，欢迎去仓库点个 Star，支持一下国内的开源 Agent 框架项目。

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/00-intro.md) ｜ 下一篇：[02. Skill 设计：为什么强制不让配模型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
