# Skill 设计解析：为什么这个开源框架强制不让你配模型

> 「Regnexe 实战系列」第 2 篇（共 10 篇），对应仓库 [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java)。上一篇：[01. withTool 极简接入](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/01-multi-tool.md)。

## 一个多 Agent 系统绕不开的设计问题

做多 Agent 系统的时候，几乎都会遇到这个问题：子能力要不要给它配个独立的模型？要不要让它有自己的私有工具？配置太灵活，团队协作很快会乱——有人配了模型有人没配，排查问题的时候根本搞不清哪个子能力在用哪个模型。配置太死板，又满足不了"有些子任务确实需要独立模型"的真实需求。

Regnexe 的处理方式是拆成 Skill 和 Sub-Agent 两种类型，规则定死，不留模糊空间。这篇讲 Skill。

## Skill 的规则：只能继承，不能拥有

打开 `SkillConfig` 这个类，会发现它**压根没有 `model` 字段**。不是没暴露，是设计上就不给配：Skill 永远继承父 Agent 的模型，没有例外。工具也一样，`SkillConfig` 只有 `allowedTools`（按 id 借用市场里已注册的工具），没有 `ownTools`（私有工具）。

| | Skill |
|---|---|
| 模型 | 强制继承父 Agent，没有配置项 |
| 工具 | 只能"借"——引用市场里已存在的 capability id |
| 适合 | 跟主 Agent 紧耦合、要省成本的轻量子工作流 |

这不是功能阉割，是刻意为之——Skill 应该是主 Agent 的一个延伸动作，不该自己另起炉灶。

## 代码示例

仓库 `ExampleReadme02SkillTest`：一个 `travel_advisor` Skill，借用市场里已经注册的 `get_weather` 工具：

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

注意顺序：`get_weather` 必须先用 `withTool` 注册进市场，`travel_advisor` 才能借到它。这是硬性依赖——`allowedTools` 里写的 id 在市场里找不到，Skill 内部就调不到这个工具。

## description 里的 TRIGGER 字段

```java
.description("... TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
```

这句话是写给 Planner（背后也是个 LLM）看的，相当于在候选能力列表里给这个 Skill 贴一张"什么时候该选我"的说明书。description 里明确写触发条件，比单纯堆功能描述，命中率会有明显提升，这是个值得记录的工程经验。

## 什么时候该用 Skill

子任务逻辑跟主 Agent 强相关、想省 token 成本（继承主模型意味着不会多开一个模型连接）、任务相对固定可重复，这三个条件符合就该用 Skill。如果子任务需要自己的模型或者私有工具，那就不是 Skill 该管的事——下一篇讲 Sub-Agent，规则正好反过来。

Skill 和 Sub-Agent 都是这套 harness 暴露出来的能力类型概念，理解了这层边界设计，再看具体 API 会顺畅很多。

---

这套"模型归属权"的设计如果对你的项目选型有参考价值，欢迎 Star 支持，也欢迎在评论区交流你的看法。

📌 上一篇：[01. withTool 极简接入](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/01-multi-tool.md) ｜ 下一篇：[03. Sub-Agent：子任务用独立模型降成本](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/03-subagent.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
