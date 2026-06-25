# Java 也有 Agent Harness 了：Regnexe 开源，Apache 2.0 协议，企业可直接商用

Regnexe 是一个基于 Java / Spring Boot 的 Agent 框架，核心是 **Search → Plan → Execute → Reflect** 四步执行闭环，**Apache 2.0 协议**开源，企业项目可以直接拿来商用，不用担心协议风险。这是系列第一篇，后面 9 篇会把核心能力点逐一讲透，每篇都配可直接运行的代码。

> 项目地址：[regnexe-agent](https://github.com/flower-trees/regnexe-agent)，文章对应的示例代码全部在仓库 `ExampleReadme01~09Test` 里，真实可跑，不是讲概念硬凑的伪代码。

## 为什么又造了一个新框架

市面上接大模型工具调用的方案不少，但大部分停留在"调一次工具"的层面：意图分类、匹配工具、调用、模型润色一下，回答完事。这套流程在简单查询场景下够用，一旦用户的需求变成复合任务——"查一下成都天气，顺便看看报销条款风险，再把三天行程排一下"——三个系统、三种能力，还要把结果综合起来回答，单次工具调用模型就不够用了。

它没有"先找哪些能力相关"的搜索过程，没有"先做什么后做什么"的规划过程，更没有"做完之后检查一下是不是真的做对了"的反思过程。Regnexe 想补的就是这一块——把"调一次工具"升级成一个真正能把任务跑完的执行闭环。

## 核心执行闭环

```
用户目标
  │
  ▼
[Search 找能力 → Plan 排计划 → Execute 真执行 → Reflect 查结果] × N 轮
  │
  ▼
AgentResult
```

- **Search**：从插件市场里挑出跟当前目标相关的能力，不会把所有工具一股脑塞进 Prompt
- **Plan**：决定调用哪些能力、先后顺序、最后结果要不要综合
- **Execute**：真正发起调用——可能是工具，也可能是 Skill 或 Sub-Agent
- **Reflect**：检查任务是不是真的完成了，没完成就接着进入下一轮

四步拼起来，Agent 才从"答一次题"变成"把活干完"。

## 严格来说这是 Harness，不是 SDK

很多人把"接入大模型"等同于"调一下 SDK"——传个 Prompt，拿个回复，结束。Regnexe 想做的事情更重一点，业内对这类框架有专门的叫法：**Agent Harness**。仓库 `pom.xml` 里的项目描述写得很直白：

```text
Enterprise-grade Agent Harness — Search-Plan-Execute-Reflect
```

SDK 模式是你写主流程，需要的时候调一下模型/工具；Harness 模式是你只描述目标和能力，整个 Search→Plan→Execute→Reflect 的执行权交给框架，它负责把任务从头跑到尾，包括要不要重试、什么时候算完成。后面 9 篇讲的工具注册、Skill/Sub-Agent、插件市场、记忆分层、暂停恢复、可观测性，全都是这套 harness 为了"把任务跑完"而提供的能力。

## 协议和依赖说明

- 开源协议：**Apache License 2.0**，商用无忧，企业内部项目可以直接引入
- 运行环境：Java 17+，Spring Boot 3.x
- 发布渠道：Maven Central，坐标 `io.github.flower-trees:regnexe-agent`

## 最简上手示例

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

Spring Boot 自动装配，`RegnexeAgentBuilder` 注入即用，不需要 `@EnableXxx`、不需要 XML 配置。这几行代码背后，Search/Plan/Execute/Reflect 已经完整跑了一轮——下一篇会换成两个工具，把完整的控制台日志展开给你看 Planner 是怎么"思考"的。

## 系列大纲

| # | 主题 |
|---|---|
| 01 | [withTool 极简接入，不建类不写注解](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/01-multi-tool.md) |
| 02 | [Skill 设计：为什么强制不让配模型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/02-skill.md) |
| 03 | [Sub-Agent：子任务用独立模型降成本](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/03-subagent.md) |
| 04 | [一个注解打包 4 种能力类型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/04-plugin-annotation.md) |
| 05 | [插件加载方式：四种姿势全解析](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/05-plugin-packaging.md) |
| 06 | [能力市场换成数据库，一个接口的事](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/06-marketplace.md) |
| 07 | [Agent 记忆为什么要拆成三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/07-three-layer-memory.md) |
| 08 | [长任务的暂停-恢复机制设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/08-pause-resume.md) |
| 09 | [可观测性：一行代码切换调试/生产](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/09-observability.md) |

每篇代码都能在仓库原样找到，真实可跑。

---

国产开源项目能不能在 Agent 框架这条赛道上跑出来，离不开大家的关注和反馈。如果这个项目对你有用，欢迎去仓库点个 Star 支持一下，也欢迎提 Issue、提 PR 一起完善。

📌 项目地址：https://github.com/flower-trees/regnexe-agent
📌 下一篇：[01. withTool 极简接入](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/01-multi-tool.md)
