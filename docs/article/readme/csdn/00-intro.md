# 别再给 LLM 写 if-else 了！我用 Java 写了一个能自己规划、执行、反思的 Agent 框架

> 本文是「Regnexe 实战系列」开篇（共 10 篇），系列文章会把 [regnexe-agent](https://github.com/flower-trees/regnexe-agent) 仓库里 9 个可直接运行的示例（`ExampleReadme01~09Test`）逐一拆解。建议收藏，跟着代码一篇一篇撸下来。

## 先问自己一个问题

你现在的"Agent"，是不是长这样：

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

一个意图分类 + 一堆 if-else + 模型兜底。能跑，但只能跑最简单的场景。

用户一旦问出"帮我查一下成都天气，再看看报销条款有没有坑，最后把三天行程排一下"——三个系统、三个能力、还要把结果拼起来——这套 if-else 直接崩。

这不是你写得不好，是**单次工具调用**这个模型本身就扛不住复杂任务。真正的企业级 Agent，需要的是一个能**自己找能力、自己排计划、自己执行、自己检查结果**的闭环，而不是你在外面手写流程图。

这就是我写 Regnexe 的原因。

## Regnexe 是什么

一句话：**基于 Spring Boot 的 Java Agent 框架，核心是 Search → Plan → Execute → Reflect 四步闭环**。

```
用户目标
  │
  ▼
[Search 找能力 → Plan 排计划 → Execute 真执行 → Reflect 查结果] × N 轮
  │
  ▼
AgentResult
```

- **Search**：从插件市场里挑出跟当前目标相关的能力，不会把几十个工具全塞进 Prompt
- **Plan**：决定调用哪些能力、谁先谁后、最后结果要不要综合
- **Execute**：真正发起工具调用 / Skill / Sub-Agent 执行
- **Reflect**：检查任务是不是真的完成了，没完成就接着来下一轮

四个字看着简单，但这四步拼起来，Agent 就从"答一次题"变成了"把活干完"。

## 严格来说，这不是一个 SDK，是一个 Harness

很多人把"接入大模型"等同于"调一下 SDK"——传个 Prompt，拿个回复，完事。但 Regnexe 想做的事情更重一些，业内对这类框架有个专门的叫法：**Agent Harness**。

`pom.xml` 里写得很直白：

```text
Enterprise-grade Agent Harness — Search-Plan-Execute-Reflect
```

SDK 是"你调用它"，Harness 是"它驱动你"。区别在于谁掌控执行的主导权：

- **SDK 模式**：你写流程代码，需要的时候调一下模型/工具，模型只是流程里的一个函数调用
- **Harness 模式**：你只描述目标和能力，整个 Search → Plan → Execute → Reflect 的执行权交给框架，框架负责把任务从开始驱动到结束，包括要不要重试、要不要继续、什么时候算完成

简单说：**SDK 解决"怎么调一次模型"，Harness 解决"怎么把一个任务跑完"**。后面 9 篇讲的工具注册、Skill/Sub-Agent、插件市场、记忆分层、暂停恢复、可观测性，全都是这套 harness 为了"把任务跑完"而提供的能力，不是孤立的功能点。

## 先看一个最简例子

不啰嗦，加依赖、配 Key、写代码，三步跑起来。

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

剩下的事——Search 找能力、Plan 排计划、Execute 调工具、Reflect 查结果——全部由这套 harness 自动完成，不需要你写一行流程代码。多工具协作、控制台日志怎么看，下一篇逐行拆给你看。

## 这套框架到底有什么能力？

接下来 9 篇文章，会按这个顺序，把仓库里 `src/test/java/.../example/readme/ExampleReadme01~09Test.java` 这 9 个**真实可跑**的示例逐一讲透：

| # | 主题 | 一句话 |
|---|---|---|
| 01 | [`withTool` 多工具入门](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/01-multi-tool.md) | 不写注解、不建类，几行代码注册多个工具直接跑 |
| 02 | [Skill：共享模型的子工作流](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md) | 永远继承主 Agent 的模型，只能"借"工具不能"占" |
| 03 | [Sub-Agent：自带模型的独立子任务](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/03-subagent.md) | 自己的模型、自己的私有工具，外面看不见 |
| 04 | [`@Plugin` 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/04-plugin-annotation.md) | 一个类，嵌套注解，一次注册 2 个 tool + 1 个 skill + 1 个 subagent |
| 05 | [插件打包的三种姿势](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/05-plugin-packaging.md) | 代码直打包、包扫描、文件系统目录，任你选 |
| 06 | [Marketplace：能力市场可以换成数据库](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/06-marketplace.md) | 接口而已，换成 DB/ES/向量库都行 |
| 07 | [三层记忆模型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/07-three-layer-memory.md) | Session 记忆、Task 账本、Agent 上下文，互不打扰 |
| 08 | [说停就停，说续就续](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/08-pause-resume.md) | 任意线程 `pause()`，带着新上下文 `resume()` |
| 09 | [可观测性：别让 Agent 是个黑盒](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/09-observability.md) | Console 调试，SLF4J 生产，一行代码切换 |

每一篇都不是空对空讲概念——文中的代码块都能在仓库里原文找到，**真的跑得通，不是 PPT 代码**。

## 为什么不是"又一个 Agent Demo"

市面上很多 Agent 教程，本质是"调一次工具 + 模型润色一下"，换个场景就要重写。Regnexe 想解决的是另一个问题：

- 能力会越来越多——今天 5 个工具，明天 50 个，靠 Search 先收窄候选集，而不是全部塞给模型
- 任务会越来越复杂——靠 Plan 把目标拆成有序步骤，而不是硬编码调用顺序
- 结果必须可信——靠 Reflect 检查是不是真的做完了，而不是模型说完就算完
- 团队要看得懂——靠事件流把每一轮 Search/Plan/Execute/Reflect 都暴露出来，不是黑盒

说白了：**Demo 看模型会不会调工具，生产看任务能不能稳定跑完**。Regnexe 就是为后者写的——这也是为什么它不叫"Agent SDK"，而是定位成 Agent Harness。

---

📌 项目地址：https://github.com/flower-trees/regnexe-agent ，顺手点个 ⭐ 不迷路
📌 下一篇：[01. `withTool` 三行代码接入一个 Java Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/01-multi-tool.md)
