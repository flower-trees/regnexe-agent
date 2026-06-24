# 为什么大多数所谓的「AI Agent」，本质上只是一次工具调用？

先说结论：因为大部分团队做的"Agent"，只是在意图分类之后接了一个 if-else，调一次工具，模型再润色一下——这种东西没有规划、没有反思、没有失败重试，叫它"Agent"有点名不副实，更准确的说法是"一次带工具的 LLM 调用"。

> 本文是「Regnexe 实战系列」开篇（共 10 篇），后面会逐篇拆解 [regnexe-agent](https://github.com/flower-trees/regnexe-agent) 仓库里 `ExampleReadme01~09Test` 这 9 个真实可跑的示例。代码全部来自仓库，不是讲 PPT。

## 真正的 Agent 和"一次工具调用"差在哪

我见过不少项目的"Agent 层"长这样：

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

简单场景下，这套东西看起来"很智能"。但只要用户的需求稍微复合一点——"帮我查一下成都天气，顺便看看报销条款有没有问题，最后把三天行程排一下"——三个系统、三种能力，还要把结果揉到一起回答——这套 if-else 立刻就露馅了。

问题不在于这段代码写得差，而在于**单次工具调用**这个模型本身就不具备处理复合任务的能力。它没有"找哪些能力相关"的搜索过程，没有"先做什么后做什么"的规划过程，更没有"做完之后检查一下是不是真的做对了"的反思过程。

这就是我自己写 Regnexe 的出发点：**把"调一次工具"升级成一个真正能把任务跑完的闭环**。

## 这套闭环长什么样

```
用户目标
  │
  ▼
[Search 找能力 → Plan 排计划 → Execute 真执行 → Reflect 查结果] × N 轮
  │
  ▼
AgentResult
```

- **Search**：从插件市场里挑出跟当前目标相关的能力，不会把几十个工具一股脑塞进 Prompt
- **Plan**：决定调用哪些能力、先后顺序、最后结果要不要综合
- **Execute**：真正发起调用——可能是一个工具，也可能是一个 Skill 或 Sub-Agent
- **Reflect**：检查任务是不是真的完成了，没完成就接着进入下一轮

这四步拼起来，"Agent"才从"答一次题"变成"把活干完"。

## 顺带说个容易被忽略的概念：Harness

很多人把"接入大模型"等同于"调一下 SDK"——传个 Prompt，拿个回复，结束。Regnexe 想做的事情更重一点，业内对这类框架其实有个专门叫法：**Agent Harness**。仓库的 `pom.xml` 里写得很直白：

```text
Enterprise-grade Agent Harness — Search-Plan-Execute-Reflect
```

SDK 和 Harness 的区别，本质是"谁掌控执行的主导权"：

- SDK 模式：你写主流程，需要的时候调一下模型/工具，模型只是流程里的一次函数调用
- Harness 模式：你只描述目标和能力，Search→Plan→Execute→Reflect 的执行权交给框架，它负责把任务从头驱动到尾，包括要不要重试、什么时候算完成

这个区分在我看来挺重要——**衡量一个 Agent 框架成不成熟，就看它解决的是"怎么调一次模型"，还是"怎么把一个任务跑完"**。后面 9 篇要讲的工具注册、Skill/Sub-Agent、插件市场、记忆分层、暂停恢复、可观测性，全都是这套 harness 为了"把任务跑完"而长出来的能力，不是东拼西凑的功能点。

## 一个最简例子，三步跑起来

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

Spring Boot 自动装配，`RegnexeAgentBuilder` 注入即用，不需要 `@EnableXxx`。这段代码背后，Search/Plan/Execute/Reflect 已经完整跑了一轮——下一篇会换成两个工具，把控制台日志展开给你看 Planner 到底是怎么"思考"的。

## 这个系列接下来讲什么

| # | 主题 |
|---|---|
| 01 | [withTool：要不要为每个工具建一个类](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/01-multi-tool.md) |
| 02 | [Skill 和 Sub-Agent 到底有什么本质区别](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/02-skill.md) |
| 03 | [子任务该不该用更便宜的模型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/03-subagent.md) |
| 04 | [一个注解能不能同时声明 Tool、Skill、Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/04-plugin-annotation.md) |
| 05 | [插件该怎么加载，才能让开发和运维都不吵架](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/05-plugin-packaging.md) |
| 06 | [能力市场换成数据库，对架构意味着什么](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/06-marketplace.md) |
| 07 | [Agent 的记忆，为什么不能只用一个 Map 存](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/07-three-layer-memory.md) |
| 08 | [长任务的暂停-恢复，工程上难在哪](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/08-pause-resume.md) |
| 09 | [Agent 出问题怎么排查：聊聊可观测性设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/09-observability.md) |

每一篇的代码都能在仓库里原样找到，不是为了讲概念硬凑的示例。

---

你怎么看"调一次工具"和"真正的 Agent"之间的界限？欢迎评论区聊聊你踩过的坑。

📌 项目地址：https://github.com/flower-trees/regnexe-agent
📌 下一篇：[01. withTool：要不要为每个工具建一个类](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/01-multi-tool.md)
