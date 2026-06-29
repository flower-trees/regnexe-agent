# Java 写 AI Agent，if-else 伪装够了——我造了个真能"想-干-查"的开源框架

> 仓库：[regnexe-agent](https://github.com/flower-trees/regnexe-agent) | 示例代码全部在 `ExampleReadme01~09Test`，真能跑，不是 PPT 代码。

---

## 先说一件让我很烦的事

网上 80% 的"Java AI Agent 教程"长这样：

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

意图分类 + if-else + 模型兜底。给它起个名叫 "Agent"，做个 Demo 没问题。

用户真问出"帮我查成都天气，顺便看看报销条款够不够，再把三天行程排一下"——三个系统，三种能力，前后有依赖，这套东西直接崩。

**问题不是代码写得不好，是模型压根就不在执行循环里。**

---

## Java AI 框架现状：够用，但不够

| 框架 | 定位 | 缺什么 |
|---|---|---|
| Spring AI | Spring 生态接入各家模型 | 工具调用有，但没有多轮自主规划 |
| LangChain4j | LangChain 的 Java 移植 | 链式调用为主，复杂任务需要大量手写逻辑 |
| 手撸 ReAct | 自己实现 Thought-Action-Observation | 每个项目都在重复造轮子，能力复用难 |

这些方案接入简单，但遇到"多步骤、跨系统、结果需要验证"的任务，都要回到"你来设计主流程"。

**我想要的是：只告诉它目标和工具，让它自己把活干完，干完自己检查。**

---

## Regnexe 的做法：Search → Plan → Execute → Reflect 闭环

```
用户目标
   │
   ▼
┌─────────────────────────────────────────┐
│  Search   找出跟目标相关的能力          │
│     ↓                                   │
│  Plan     决定调用顺序和组合方式        │  × N 轮
│     ↓                                   │
│  Execute  真发起调用（工具/Skill/Agent）│
│     ↓                                   │
│  Reflect  判断任务是否真完成了          │
└─────────────────────────────────────────┘
   │
   ▼
AgentResult
```

四步是一轮。没完成，接着下一轮。Reflect 判断"完了"，整个循环才退出。

这不是我发明的——这是 Self-Refine / ReAct / CAMEL 等研究里反复出现的"Agent 应该是个循环"的结论。Regnexe 做的是把它落到 Java / Spring Boot 生产环境里。

---

## 它和 SDK 的本质区别

很多人把"接大模型"等同于"调个 SDK"——传 Prompt，拿回复，结束。

Regnexe 是 **Agent Harness**，不是 SDK：

```
SDK 模式：  你 → 调 → 模型/工具         （你是主流程）
Harness 模式：你 → 描述目标和能力 → 框架主导 Search-Plan-Execute-Reflect
                                         （框架是执行引擎）
```

`pom.xml` 里写的是 `Enterprise-grade Agent Harness`，这个词是刻意选的。你不写主流程，你只注册能力、声明目标，框架负责把任务从头跑到尾。

---

## 5 分钟上手

**1. 引依赖**

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.2</version>
</dependency>
```

**2. 配模型**

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
```

**3. 跑起来**

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

System.out.println(result.getFinalText());
// → 北京今天晴，22℃，空气优，适合跑步。
```

Spring Boot 自动装配，`RegnexeAgentBuilder` 注入即用，不用 `@EnableXxx`。

这几行背后，Search / Plan / Execute / Reflect 已经完整跑了一轮。下一篇把控制台日志展开，你能看到 Planner 是怎么"思考"的。

---

## 后面 9 篇讲什么

这个系列的结构是"从简单到复杂，每篇解决一个真实问题"：

| # | 解决什么问题 | 关键机制 |
|---|---|---|
| [01](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/01-multi-tool.md) | 多工具时 Planner 怎么选？日志里能看到决策过程吗？ | withTool / 控制台事件 |
| [02](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/02-skill.md) | 一个复杂子任务，交给工具还是交给 Skill？ | Skill 的提示词封装 |
| [03](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/03-subagent.md) | 贵的模型做规划，便宜的模型干执行，怎么拆？ | Sub-Agent 模型隔离 |
| [04](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/04-plugin-annotation.md) | 一个类里有 4 种能力，能不能一个注解全注册？ | @Plugin / @AgentTool |
| [05](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/05-plugin-packaging.md) | 能力包打成 jar，部署时热插拔，怎么做？ | 插件打包四种姿势 |
| [06](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/06-marketplace.md) | 能力市场从内存换成数据库，要改多少代码？ | Marketplace 接口替换 |
| [07](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/07-three-layer-memory.md) | 同一个用户多轮对话，上下文怎么管？ | 三层记忆模型 |
| [08](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/08-pause-resume.md) | 长任务跑到一半，用户喊停，能接着继续吗？ | pause / resume |
| [09](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/09-observability.md) | Agent 答错了，怎么知道是哪一步出的问题？ | 事件监听器 |

每篇代码都在仓库里原样能跑，序号就是示例文件序号。

---

如果你也受够了"意图分类 + if-else = Agent"，欢迎去仓库点个 ⭐，追后面 9 篇 👇

📌 项目地址：https://github.com/flower-trees/regnexe-agent  
📌 下一篇：[01. 多工具场景下，Planner 怎么选工具？控制台日志全程可见](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/01-multi-tool.md)
