# Sub-Agent：子任务用独立模型降成本，这个开源框架怎么设计的

> 「Regnexe 实战系列」第 3 篇（共 10 篇），对应仓库 [`ExampleReadme03SubAgentTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java)。上一篇：[02. Skill 设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/02-skill.md)。

## 真实的成本问题

很多团队做多 Agent 系统会遇到这个问题：主任务需要强模型做复杂推理，但拆出来的某个子任务——比如"估算一下这次出差大概花多少钱"——逻辑很简单，用旗舰模型纯属浪费。理想情况是：主 Agent 用强模型，子任务用更便宜的模型单独跑，互不影响。

上一篇讲的 Skill 做不到这件事，它被设计成强制继承主模型。这篇要讲的 Sub-Agent，规则正好反过来。

## Sub-Agent 的规则：可以拥有，不是只能借

```java
SubAgentConfig.builder()
    .model("aliyun:qwen-plus")   // 自己的模型，跟主 Agent 完全独立
    .ownTools(List.of(myPrivateTool))   // 私有工具，外面看不到
    .build();
```

两种类型对比：

| | Skill | Sub-Agent |
|---|---|---|
| 模型 | 强制继承父 Agent | 自己的模型，或写 `"inherit"` 继承 |
| 工具 | 只能借（`allowedTools`） | 私有拥有（`ownTools`），外部不可见 |
| 适合 | 跟主 Agent 紧耦合的轻量子流程 | 需要隔离、需要独立模型的独立子任务 |

`model` 默认值是 `"inherit"`，不配就跟 Skill 一样继承父模型；写了具体值，就走独立的 `ModelProvider` 分支单独构建模型实例。默认行为安全，按需打开独立配置，这个设计避免了因为忘记配置而出现意外行为。

## 代码示例

仓库 `ExampleReadme03SubAgentTest`：一个 `expense_estimator` Sub-Agent，自己的模型 `aliyun:qwen-plus`，自己的私有工具 `estimate_trip_cost`：

```java
// 私有工具：只在 SubAgent 内部执行器里可见，通过 ownTools 注入
Tool estimateCostTool = Tool.builder()
        .name("estimate_trip_cost")
        .description("Estimates total cost for a multi-day business trip.")
        .params("days: int -- trip length; city: String -- destination city")
        .func(input -> "3-day Chengdu trip estimate: flights 1800 CNY, hotel 1200 CNY, meals 600 CNY. Total: 3600 CNY.")
        .build();

SubAgentConfig expenseEstimator = SubAgentConfig.builder()
        .name("expense_estimator")
        .description("Estimates the total cost of a business trip. " +
                     "TRIGGER: Use when the user asks for a trip budget or cost estimate.")
        .model("aliyun:qwen-plus")      // 自己的模型，独立于主 Agent 的默认模型
        .systemPrompt("""
                You are a travel expense estimator.
                1. Call estimate_trip_cost with the trip length and destination.
                2. Report the total and a one-line breakdown.
                """)
        .ownTools(List.of(estimateCostTool))
        .build();

RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")   // 主 Agent 用的模型
        .withSubAgent(expenseEstimator)
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(3)
        .build();

AgentResult result = agent.execute("What would a 3-day business trip to Chengdu cost?");
```

主 Agent 跑 `deepseek-v4-flash`，`expense_estimator` 内部跑 `aliyun:qwen-plus`，两个模型互不干扰，调用成本也分得清楚。

## 私有工具的隔离机制

`estimate_trip_cost` 这个工具，从头到尾没有出现在 `withTool` 或者 marketplace 的任何注册调用里，只存在于 `expenseEstimator.ownTools` 这一个地方。这意味着主 Agent 的 Planner 在 Search 阶段，候选列表里压根不会出现这个工具，没法直接调用——唯一入口是先选中 `expense_estimator` 这个 Sub-Agent。

这是从能力市场的可见性上做的隔离，不是约定俗成的君子协定。适合那些不想让外层 Agent 瞎调用、必须经过子任务封装处理的场景，比如涉及资金操作的工具。

## 怎么判断该用 Skill 还是 Sub-Agent

这个子能力需要自己的模型，或者需要外部完全看不到的私有工具——要，用 Sub-Agent；不要，就是想复用主 Agent 模型省成本——用 Skill。两种类型不是谁更高级，是这套 harness 解决不同问题的工具。

---

如果你也在为多 Agent 系统的模型成本头疼，欢迎参考这套设计，去仓库点个 Star，也欢迎在评论区分享你的实践。

📌 上一篇：[02. Skill 设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/02-skill.md) ｜ 下一篇：[04. 一个注解打包 4 种能力类型](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/04-plugin-annotation.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
