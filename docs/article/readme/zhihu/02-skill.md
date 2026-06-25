# Agent 框架里的 Skill 和 Sub-Agent，到底有什么本质区别？

先说结论：区别不在"能力强弱"，而在**模型归属权**和**工具所有权**——Skill 永远用主 Agent 的模型、只能借工具；Sub-Agent 可以有自己的模型、可以拥有私有工具。这是两种设计哲学，不是同一个东西的强弱版本。

> 「Regnexe 实战系列」第 2 篇（共 10 篇），对应仓库 [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java)。上一篇：[01. withTool](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/01-multi-tool.md)。

## 为什么这个问题值得单独拿出来讲

做多 Agent 系统的时候，几乎每个人都会在这个问题上卡一下：

> "这个子能力，要不要给它配个独立的模型？要不要让它有自己的私有工具？"

如果设计上不把这件事定死，团队协作很快会乱——有人配了模型有人没配，排查问题的时候根本搞不清哪个子能力在用哪个模型、调了哪个工具。但如果完全不允许配置，又满足不了"有些子任务确实需要独立模型"这种真实需求。

Regnexe 的处理方式是：**不让一个概念干两件事**，拆成 Skill 和 Sub-Agent 两种类型，规则定死，不留模糊空间。这篇先讲 Skill。

## Skill 的规则：只能继承，不能拥有

打开 `SkillConfig` 这个类，你会发现一个挺有意思的设计——它**压根没有 `model` 字段**。不是没暴露出来，是设计上就不给配置的余地：

> Skill 永远继承父 Agent 的模型，没有例外。

工具也是一样的逻辑，`SkillConfig` 只有 `allowedTools`（按 id 借用市场里已注册的工具），没有 `ownTools`（私有工具）。

| | Skill |
|---|---|
| 模型 | 强制继承父 Agent，没有配置项 |
| 工具 | 只能"借"——引用市场里已存在的 capability id |
| 适合 | 跟主 Agent 紧耦合、要省成本的轻量子工作流 |

这不是功能阉割，是刻意为之——**Skill 应该是主 Agent 的一个延伸动作，不该自己另起炉灶**。一旦允许它自己配模型、自己拿私有工具，它就不再是"Skill"，而是变相的 Sub-Agent，两个概念的边界就会越用越模糊。

## 代码长什么样

仓库 `ExampleReadme02SkillTest` 里有个 `travel_advisor` Skill，借用市场里已经注册的 `get_weather` 工具：

```java
Tool weatherTool = Tool.builder()
        .name("get_weather")
        .description("Get today's weather for a city.")
        .params("city: String -- city name")
        .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
        .build();

SkillConfig travelAdvisor = SkillConfig.builder()
        .name("travel_advisor")
        .description("Gives outdoor-activity advice based on the current weather for a city. " +
                     "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
        .systemPrompt("""
                You are an outdoor-activity advisor.
                1. Call get_weather for the city the user mentions.
                2. Based on the result, give a short, direct go/no-go recommendation.
                """)
        .allowedTools(List.of("get_weather"))   // 按 id 借用，不是自己拥有
        .build();

RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withTool(weatherTool)        // Skill 借用的工具必须已经在市场里
        .withSkill(travelAdvisor)
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(3)
        .build();

AgentResult result = agent.execute(
        "I want to go for a run in Beijing today. Should I, and what should I watch out for?");
```

注意顺序：`get_weather` 必须先用 `withTool` 注册进市场，`travel_advisor` 才能借到它。这不是写法上的偶然先后，是硬性依赖——`allowedTools` 里写的 id 在市场里找不到，Skill 内部就压根调不到这个工具。

## 一个被低估的细节：description 里的 TRIGGER

```java
.description("... TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
```

这句话是写给 Planner（背后也是个 LLM）看的，相当于在候选能力列表里给这个 Skill 贴一张"什么时候该选我"的说明书。我自己实测下来，description 里明确写触发条件，比单纯堆功能描述，命中率高出一个量级——这条经验比"怎么用 API"本身更值得记下来。

## 怎么判断该不该用 Skill

问自己：这个子任务跟主 Agent 强相关吗？需不需要省成本（继承主模型意味着不会多开一个模型连接）？任务本身是不是相对固定、可重复？

如果答案都是"是"，用 Skill。如果子任务需要自己的模型，或者需要外部完全看不到的私有工具，那就不是 Skill 该管的事了——下一篇讲 Sub-Agent，规则正好反过来。

Skill 和 Sub-Agent 都是这套 harness 暴露出来的"能力类型"概念，不是两个孤立的功能，理解了这一层，再去看具体 API 会顺很多。

---

你们在设计多 Agent 系统时，是怎么决定一个子能力该不该有独立模型的？欢迎评论区聊聊。

📌 上一篇：[01. withTool](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/01-multi-tool.md) ｜ 下一篇：[03. Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/03-subagent.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
