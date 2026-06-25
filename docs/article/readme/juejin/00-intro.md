# 我用 Java 写了一个能自己规划、执行、反思的 Agent 框架，开源了 🚀

大家好，最近在搞一个开源项目，想分享出来，顺便记录一下踩坑过程——一个 Java / Spring Boot 的 Agent 框架，叫 **Regnexe**。这是系列第一篇，后面打算用 9 篇文章把核心能力点逐一讲完，每篇都带可以直接跑的代码。

> 仓库地址：[regnexe-agent](https://github.com/flower-trees/regnexe-agent)，文章对应的示例代码全部在 `ExampleReadme01~09Test` 里，不是 PPT 代码，建议跟着撸一遍。

## 🤔 起因：我受不了"调一次工具"式 Agent 了

写过 Agent 相关功能的同学应该都遇到过这种"伪 Agent"：

```java
String intent = classifyIntent(userInput);
if ("weather".equals(intent)) {
    return callWeatherApi(userInput);
} else if ("order".equals(intent)) {
    return callOrderApi(userInput);
} else {
    return llm.chat(userInput);
}
```

意图分类 + if-else + 模型兜底，演示的时候看起来挺智能，用户真问出"帮我查一下成都天气，顺便看看报销条款有没有问题，最后把三天行程排一下"——三个系统、三种能力，这套东西直接崩。

不是代码写得不好，是**单次工具调用**这个模型本身扛不住复合任务。我想要的是一个能自己找能力、自己排计划、自己执行、自己检查结果的闭环——这就是 Regnexe 的由来。

## ⚡ 核心机制：四步闭环

```
用户目标
  │
  ▼
[Search 找能力 → Plan 排计划 → Execute 真执行 → Reflect 查结果] × N 轮
  │
  ▼
AgentResult
```

- 🔍 **Search**：从插件市场挑出跟目标相关的能力，不会把几十个工具全塞进 Prompt
- 📝 **Plan**：决定调用哪些能力、先后顺序、结果要不要综合
- ⚙️ **Execute**：真正发起调用——可能是工具，也可能是 Skill / Sub-Agent
- 🔁 **Reflect**：检查任务是不是真做完了，没做完就接着下一轮

四步拼起来，"Agent"才从"答一次题"变成"把活干完"。

## 🏷️ 划重点：这玩意儿严格来说不是 SDK，是 Harness

很多人把"接大模型"等同于"调个 SDK"——传个 Prompt，拿个回复，结束。Regnexe 想干的事更重一点，业内对这类框架有个专门叫法：**Agent Harness**。仓库 `pom.xml` 里写的就是：

```text
Enterprise-grade Agent Harness — Search-Plan-Execute-Reflect
```

SDK 是"你调用它"，Harness 是"它驱动你"：

- SDK 模式：你写主流程，需要的时候调一下模型/工具
- Harness 模式：你只描述目标和能力，整个 Search→Plan→Execute→Reflect 的执行权交给框架，它负责把任务从头跑到尾

后面 9 篇讲的工具注册、Skill/Sub-Agent、插件市场、记忆分层、暂停恢复、可观测性，都是这套 harness 为了"把任务跑完"长出来的能力，不是东拼西凑的功能点。

## 🛠️ 上手最简版（建议跟着抄一遍）

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.2</version>
</dependency>
```

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
```

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String -- city name")
    .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
    .build();

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool)
    .build()
    .execute("Check today's weather in Beijing. Is it good for running?");

System.out.println(result.getFinalText());   // FINISHED：北京今天晴，22℃，适合跑步
```

Spring Boot 自动装配，`RegnexeAgentBuilder` 注入即用，不用 `@EnableXxx`。这几行背后，Search/Plan/Execute/Reflect 已经完整跑了一轮——下一篇换成两个工具，把控制台日志展开给你看 Planner 是怎么"思考"的。

## 📚 系列大纲

| # | 主题 |
|---|---|
| 01 | [withTool 极简接入，不建类不写注解](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/01-multi-tool.md) |
| 02 | [Skill 和 Sub-Agent 怎么选](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/02-skill.md) |
| 03 | [子任务用便宜模型，靠 Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/03-subagent.md) |
| 04 | [一个注解打包 4 种能力](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/04-plugin-annotation.md) |
| 05 | [插件加载的四种姿势](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/05-plugin-packaging.md) |
| 06 | [能力市场换成数据库，一个接口的事](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/06-marketplace.md) |
| 07 | [Agent 记忆为什么要拆三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/07-three-layer-memory.md) |
| 08 | [长任务说停就停说续就续](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/08-pause-resume.md) |
| 09 | [Agent 是黑盒？接个监听器就能看穿](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/09-observability.md) |

每篇代码都能在仓库原样找到，真能跑，不是讲概念硬凑的示例。

---

如果你也受够了"调一次工具就叫 Agent"，欢迎去仓库点个 ⭐，关注我追更后面 9 篇 🙌

📌 项目地址：https://github.com/flower-trees/regnexe-agent
📌 下一篇：[01. withTool 极简接入](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/01-multi-tool.md)
