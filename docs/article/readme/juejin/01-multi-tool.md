# 别再为接一个工具调用建一堆类了，几行代码搞定 ⚡

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/00-intro.md)。

## 😅 这个流程你肯定经历过

接一个新工具，很多框架的标准动作是：建包 → 建类 → 加注解 → 注册 Bean → 改配置，一套流程走完，十分钟过去了，工具本身的逻辑可能只有一行。

如果只是想"先跑起来看看效果"，这个成本属实有点劝退。Regnexe 把这条路径压到了最短：**不需要类，不需要注解，一个 `Tool.builder()` + 一个 `withTool(...)`，直接完事**。

## 🛠️ 实战代码

来看仓库里的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)，注册两个工具——查天气、查空气质量——丢给 Agent 自己决定怎么组合：

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

`withTool` 是可变参数，塞几个工具都行，不用逐个 `.install()`。

## 👀 运行效果：重点不是代码少，是 Agent 真的"自己"在决定

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

划重点 🎯：我们没写"先查天气再查空气质量"的顺序，也没写"两个结果怎么合并"。Planner 自己识别出目标里有两个诉求，选中两个工具；Reflect 确认两项数据都拿到了，才判定 `FINISH`。如果只调了一个工具就想结束，Reflect 会把它打回去要求继续——这就是单次工具调用做不到的地方。

## ⚠️ 踩坑提醒

`Tool.builder()` 的 `params` 字段不是随便写的注释，它会被拼进喂给模型的工具描述里，直接影响模型怎么构造调用参数。亲测格式统一成 `参数名: 类型 -- 说明`，模型遵循度明显更高，这个细节在 Demo 阶段不会暴露问题，工具一多差异就很明显。

## ✅ 小结

`withTool(Tool...)` 适合：

- 临时脚本、PoC 验证
- 工具逻辑特别简单，懒得为它单独建类
- 动态生成的工具（配置或 DB 拼出来的 `Tool` 对象）

不管走哪条注册路径，背后驱动 Search→Plan→Execute→Reflect 的都是同一套 harness——`withTool` 只是接进这套 harness 最快的入口。如果工具多到需要打包管理、加标签、做版本控制，下一篇 `@Plugin` 注解就该登场了。

---

欢迎去仓库点个 ⭐，关注我追更，下一篇讲 Skill 和 Sub-Agent 怎么选 👇

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/00-intro.md) ｜ 下一篇：[02. Skill 和 Sub-Agent 怎么选](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
