# Skill 和 Sub-Agent 到底有什么区别？一个细节看懂

> 「Regnexe 实战系列」第 2 篇（共 10 篇），对应仓库 [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java)。上一篇：[01. withTool 多工具入门](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/01-multi-tool.md)。

## 一个让人纠结的设计问题

做多 Agent 系统的时候，几乎所有人都会卡在同一个问题上：

> "这个子能力，要不要给它配个独立的模型？要不要让它有自己的私有工具？"

配置太灵活，团队用起来会乱套——有人配了模型有人没配，排查问题的时候根本搞不清哪个 Sub-Agent 在用哪个模型、调了哪个工具。配置太死板，又满足不了"有些子任务确实需要更强/更便宜的模型"这种真实需求。

Regnexe 的答案是：**别让一个概念干两件事，拆成 Skill 和 Sub-Agent 两种类型，规则定死，不留选择空间**。这篇先讲 Skill。

## Skill 的核心规则：只能继承，不能拥有

直接看 `SkillConfig` 这个类——你会发现它**压根没有 `model` 字段**。不是没暴露，是设计上就不给你配：

> Skill 永远继承父 Agent 的模型，没有例外。

工具也一样，`SkillConfig` 只有 `allowedTools`（按 id 借用市场里已经注册的工具），没有 `ownTools`（私有工具）。换句话说：

| | Skill |
|---|---|
| 模型 | 强制继承父 Agent，没有配置项 |
| 工具 | 只能"借"——引用市场里已存在的 capability id |
| 适合 | 跟主 Agent 紧耦合、要省成本的轻量子工作流 |

这不是"功能阉割"，是刻意为之——Skill 就该是主 Agent 的一个延伸动作，不该自己另起炉灶。

## 实战代码

来看仓库里的 [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java)：一个 `travel_advisor` Skill，借用市场里已经注册的 `get_weather` 工具，给跑步爱好者提建议。

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

注意顺序：**`get_weather` 必须先用 `withTool` 注册进市场，`travel_advisor` 才能借到它**。这不是写法上的偶然先后，是硬性依赖——`allowedTools` 里写的 id 在市场里找不到，Skill 内部就压根调不到这个工具。

## description 里那句 TRIGGER 不是凑字数的

```java
.description("... TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
```

这句话是写给 Planner（背后也是个 LLM）看的，相当于在候选能力列表里给这个 Skill 贴一张"什么时候该选我"的说明书。实测下来，description 里明确写触发条件，比单纯堆功能描述，命中率高出一个量级——这是从大量 Agent 工程实践里总结出来的小技巧，建议每个 Skill / Tool 的描述都带上。

## 小结：什么时候该用 Skill

- 子任务逻辑跟主 Agent 强相关，没必要换模型、没必要独立工具
- 想省 token、省调用成本——继承主模型意味着不会多开一个模型连接
- 子任务相对固定、可重复——比如"基于已有数据给一段结构化建议"这种

Skill 和 Sub-Agent 都是这套 harness 暴露出来的"能力类型"，不是两个互不相关的功能。如果你的子任务需要**自己的模型**（比如更便宜的模型跑简单子任务）或者**私有工具**（外面绝对看不到），那就不是 Skill 该管的事了——下一篇讲 Sub-Agent，规则正好反过来。

---

📌 上一篇：[01. withTool 多工具入门](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/01-multi-tool.md) ｜ 下一篇：[03. Sub-Agent：自己的模型，自己的工具](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/03-subagent.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
