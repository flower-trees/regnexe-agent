# 9 行代码搞定 LLM 多工具调用！Regnexe 极简接入 Agent，不用Prompt、不用新建类、不用注解

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/00-intro.md)。

## 痛点：写个工具调用，为什么要建一个类

很多框架接一个新工具的标准动作是：建包、建类、加注解、注册 Bean、改配置……一套流程下来，十分钟过去了，工具本身的逻辑可能只有一行。

如果只是想"先跑起来看看效果"，这个成本就显得不太划算。

Regnexe 把这条路径压到了最短：**不需要类，不需要注解，一个 `Tool.builder()` + 一个 `withTool(...)`，直接完事**。

## 实战代码

来看仓库里的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)，注册两个工具——查天气、查空气质量——然后丢给 Agent 自己决定怎么组合：

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
    .withTool(weatherTool, airQualityTool)          // ← 可变参数，一次注册多个
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());          // FINISHED
```

注意 `withTool` 是可变参数——一次塞几个工具都行，不需要逐个 `.install()`。

## 运行效果：看 Agent 怎么"自己"决定

把 `ConsoleEventListener` 接上之后，Search → Plan → Execute → Reflect 每一步都会打到控制台：

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

划重点：**我们没有写"先查天气再查空气质量"的顺序，也没有写"两个结果怎么合并"**。Planner 自己看到目标里有"weather and air quality"两个诉求，选中两个工具；Execute 把两次调用结果都拿到手；Reflect 确认两项数据都齐了，才判定 `FINISH`。

如果只调了一个工具就想结束，Reflect 这一步会把它打回去，要求继续——这就是单次工具调用框架做不到的地方。

## 踩坑提醒

> `Tool.builder()` 的 `params` 字段不是随便写的注释，它会被拼进喂给模型的工具描述里，直接影响模型怎么构造调用参数。建议格式统一成 `参数名: 类型 -- 说明`，模型对这种格式的遵循度明显更高。

## 小结

`withTool(Tool...)` 是接入 Regnexe 最快的路径，没有类、没有注解，适合：

- 临时脚本、PoC 验证
- 工具逻辑特别简单，不想为它单独建类
- 动态生成的工具（比如从配置或者 DB 拼出来的 `Tool` 对象）

记住一点：不管走哪条注册路径，背后驱动 Search→Plan→Execute→Reflect 的都是同一套 harness——`withTool` 只是接进这套 harness 最快的一个入口。如果工具多到需要打包管理、加标签、做版本控制，那就是下一篇 `@Plugin` 注解登场的时候了。

---

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/00-intro.md) ｜ 下一篇：[02. Skill：为什么它"只能借工具不能占"](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent ，欢迎 Star
