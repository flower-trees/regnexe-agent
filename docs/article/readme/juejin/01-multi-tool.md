# 多工具调用只是开始：用 Regnexe 构建真正会反思的 Java Agent

> 「Regnexe 实战系列」第 1 篇（共 10 篇），对应仓库 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java)。上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/00-intro.md)。

上一篇我说，很多 Java AI Agent 其实只是“意图分类 + if-else + 模型兜底”。

这一篇继续往前走一步：**如果一个任务需要多个工具协作，到底怎样才算 Agent 在工作？**

我的判断很简单：

如果你在外层代码里写死了调用顺序，那它只是 workflow。

如果模型按 ReAct / Function Calling 循环自己连续调工具，那已经比 workflow 灵活很多。

但如果框架还能根据目标找能力、排计划、执行工具、检查结果，发现不完整还能继续规划，那才更接近 Re-Planning Agent。

## 先看一个常见写法

用户问：

```text
Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.
```

很多项目会写成这样：

```java
String weather = getWeather("Beijing");
String air = getAirQuality("Beijing");
String answer = llm.summarize(weather, air);
```

这段代码没错，也很稳定。

但它解决的是“我已经知道要调哪些工具”的问题。

真实的 Agent 场景里，用户通常不会按你的接口名提问。他可能只说：

```text
北京今天适不适合户外跑步？
```

这里隐含了多个信息需求：

- 天气怎么样
- 温度适不适合运动
- 空气质量是否影响户外活动

如果只查天气就回答“适合”，那只是看起来完成了任务；如果空气质量很差，这个回答就是错的。

所以多工具 Agent 的重点，不是“能不能调用两个函数”，而是：

> 它能不能知道自己还缺什么。

## 多步 ToolCall 能跑，不代表 Agent 真的会做完

我之前在 `j-langchain` 里写过一个类似封装：`McpAgentExecutor`。它的目标很明确：把手写 Function Calling 循环封装掉，让调用方用很少的代码完成多步工具调用。

如果换成这篇文章里的同一个任务，也可以把天气和空气质量做成两个 `Tool`，直接通过 `.tools(...)` 接进去：

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String")
    .func(args -> "Beijing: sunny, 22 C.")
    .build();

Tool airQualityTool = Tool.builder()
    .name("get_air_quality")
    .description("Get today's air quality index (AQI) for a city.")
    .params("city: String")
    .func(args -> "Beijing: AQI 35, excellent air quality.")
    .build();
```

然后交给 `McpAgentExecutor`：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(weatherTool, airQualityTool)
    .systemPrompt("你是一个户外运动助手，可以调用天气和空气质量工具后回答用户问题。")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> ToolCall: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke(
    "Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running."
);
```

运行时，它大概会呈现出这样的过程：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.
>> ToolCall: get_air_quality {"city":"Beijing"}
>> Observation: Beijing: AQI 35, excellent air quality.

=== 最终答案 ===
北京今天晴，22°C，AQI 35，空气质量很好，适合户外跑步。
```

这类封装非常有价值。它把 Function Calling 循环里最烦的样板逻辑都收起来了：

- 把 `Tool` 转成模型可识别的 Function Calling 描述
- 让模型决定下一步要不要调工具
- 解析 ToolCall
- 执行工具
- 把 Observation 写回上下文
- 在多轮 ToolCall 后生成最终回答

它解决的是“模型能不能连续调工具”的问题。

比如这个任务里，模型可以先调天气工具，再调空气质量工具，最后综合回答是否适合户外跑步。对很多查询类助手来说，这已经够用了。

但这里还有一个更深的问题：

```text
如果模型只查了天气，没有查空气质量，它知不知道自己没做完？
```

普通 ToolCall 循环更关注“下一次要不要调工具”。只要模型自己认为可以回答，循环就会结束。

但多工具任务真正危险的地方，往往不是“工具调不起来”，而是“信息缺了一块，模型仍然给出了看似合理的答案”。

比如它只拿到：

```text
>> ToolCall: get_weather {"city":"Beijing"}
>> Observation: Beijing: sunny, 22 C.

=== 最终答案 ===
北京今天晴，22°C，适合户外跑步。
```

这在 ToolCall 层面可能是一次正常结束；但在任务目标层面，它少了空气质量判断。

Re-Planning Agent 更关注的是“当前结果是否足够完成目标”。如果目标明确要求 weather and air quality，而结果里只有 weather，就应该把任务打回去继续规划。

所以 Regnexe 这篇不是要否定 `McpAgentExecutor` 这类封装。它们解决的是不同层级的问题：

```text
McpAgentExecutor / ReAct Loop:
    让模型连续调用工具

Regnexe / Re-Planning Agent:
    让 Agent 显式经历 Search、Plan、Execute、Reflect，
    并在 Reflect 发现目标未完成时重新规划
```

也就是说，多步工具调用只是开始。真正要进入 Agent 层面，就必须回答“任务有没有真的做完”。

## Workflow、ReAct、Re-Planning Agent 怎么选

放在一起看会更清楚：

| 模式 | 核心思路 | 优点 | 短板 | 适合场景 |
|---|---|---|---|---|
| Workflow | 程序员写死流程，模型只负责局部生成 | 稳定、可控、好测试 | 流程变化就要改代码，不会自己补缺失步骤 | 固定业务流程、审批流、明确 SOP |
| ReAct / Function Calling Loop | 模型在循环里决定下一次 ToolCall | 灵活，能完成多步工具调用，封装后代码很短 | 主要依赖模型连续决策，计划结构和完成度检查不够显式 | MCP 工具助手、查询类任务、轻量多步调用 |
| Re-Planning Agent | Search → Plan → Execute → Reflect，没完成就重新规划 | 能力搜索、计划、执行、反思分层清楚，可观察性更强 | 框架更重，需要定义好能力描述和执行边界 | 多工具、多能力、多轮任务，结果必须被检查 |

所以这三类不是谁替代谁，而是抽象层级不同。

如果任务永远固定，workflow 最稳。

如果只是希望模型在几个工具里连续调用，ReAct / Function Calling Loop 很合适。

如果你关心的是“目标有没有真正完成”“缺的信息能不能被发现”“执行过程能不能被观察和重新规划”，那 Re-Planning Agent 更合适。

## Regnexe 的执行链路差在哪

普通 function calling 的典型路径是：

```text
用户问题 → 模型选择工具 → 调用工具 → 模型回答
```

ReAct / Function Calling Loop 会把这件事做成循环：

```text
用户问题 → ToolCall → Observation → ToolCall → Observation → 最终回答
```

Regnexe 做的是把循环拆成更明确的 Agent Harness：

```text
Search   找能力
Plan     排计划
Execute  真执行
Reflect  查结果是否足够
```

四步是一轮。Reflect 判断没完成，就继续下一轮。

这就是 Re-Planning Agent 的价值：**不是只会调工具，而是把“任务是否完成”也纳入执行循环**。

## 代码很短，但背后不是简单调用

仓库里的 [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) 只注册了两个工具：天气和空气质量。

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
```

这里没有新建工具类，没有注解，也没有额外配置。

`Tool.builder()` 只是把能力描述清楚：

- 工具叫什么
- 工具能做什么
- 参数怎么传
- 真正执行时跑哪段函数

然后把两个工具交给 Agent：

```java
AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool, airQualityTool)
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());
```

这里最关键的是没有写这些逻辑：

```text
先查天气
再查空气质量
如果缺空气质量再补一次
最后综合两个结果
```

你只是给了目标和可用能力。Regnexe 负责让 Agent 自己走完整个执行闭环。

## 控制台日志：Planner 到底在干什么

接上 `ConsoleEventListener`，你能看到这不是黑盒：

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

这段日志里最重要的是四个节点：

- `Search Result`：找到了天气和空气质量两个能力
- `Plan Result`：决定这一轮两个都要调
- `Execute Result`：拿到工具结果并合成中间结论
- `Reflect Result`：检查目标是否真的完成

如果只拿到了天气，没有拿到 AQI，Reflect 就不应该给 `FINISH`，而是进入下一轮重新规划。

这也是我觉得很多 Agent Demo 不够工程化的地方：它们展示了“模型能调工具”，但没展示“模型怎么知道自己没做完”。

## Reflect 为什么比你想的更重要

我们把问题换成中文：

```text
北京今天适不适合户外跑步？
```

如果模型只调用了天气工具，拿到：

```text
Beijing: sunny, 22 C.
```

它很容易回答：

```text
适合跑步。
```

但这不是一个可靠答案，因为户外跑步还要看空气质量。

这就是 Reflect 的意义：它不是为了让日志更好看，而是为了在执行后问一句：

```text
当前结果是否足够回答用户目标？
```

不够，就继续规划。

在真实业务里，这个机制会更重要。比如：

- 报销审核：只查制度不查票据，不完整
- 旅行规划：只查天气不查交通，不完整
- 订单处理：只查订单不查库存，不完整
- 运维排障：只查日志不查指标，不完整

多工具 Agent 真正的难点一直是“缺什么还能意识到缺什么”。

## 一个很容易忽略的工程细节

`Tool.builder()` 里的 `params` 不是普通注释：

```java
.params("city: String -- city name")
```

它会进入工具描述，影响模型怎么构造工具调用。

建议统一成这种结构：

```text
参数名: 类型 -- 说明
```

工具少的时候你可能感觉不到差异。工具一多，Planner 需要靠这些描述判断：

- 这个工具是不是适合当前目标
- 参数从用户问题里怎么抽取
- 多个工具之间怎么组合

描述越稳定，选择越稳定。

## withTool 适合什么时候用

`withTool(Tool...)` 是 Regnexe 里最快的工具接入方式，适合：

- PoC 阶段，先验证多工具 Agent 能不能跑通
- 工具逻辑很轻，不想为了一个函数建类和注解
- 工具来自配置、数据库或运行时动态生成
- 想快速观察 Search / Plan / Execute / Reflect 全链路

但它不是所有场景的终点。

当工具变多，需要分组、标签、版本、权限控制、独立发布时，就应该进入后面的 `@Plugin`、插件打包和 Marketplace。

## 小结

这篇真正想讲的不是“Regnexe 怎么少写几行工具代码”。

更核心的是：**Regnexe 让你用很短的代码，把多个工具放进一个可观察、可反思、可重新规划的 Agent 执行循环里。**

workflow 解决的是“确定流程怎么自动跑”。

ReAct / Function Calling Loop 解决的是“模型能不能连续调工具”。

Re-Planning Agent 解决的是“模型调完以后，知不知道任务是否真的完成，以及不完整时能不能重新规划”。

这三件事差很多。

---

如果你也在写 Java AI Agent，可以对照一下自己的实现：它是在跑 workflow、在做 ToolCall 循环，还是在围绕目标持续规划和检查？

📌 上一篇：[00. 系列开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/00-intro.md) ｜ 下一篇：[02. Skill 和 Sub-Agent 怎么选](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/02-skill.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
