# 子任务想换个便宜模型跑？Sub-Agent 这样设计

> 「Regnexe 实战系列」第 3 篇（共 10 篇），对应仓库 [`ExampleReadme03SubAgentTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java)。上一篇：[02. Skill：只能借工具不能占](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md)。

## 真实场景：主 Agent 用旗舰模型，子任务想省钱

很多团队做多 Agent 系统会遇到这个成本问题：主任务需要强模型做复杂推理，但拆出来的某个子任务——比如"估算一下这次出差大概花多少钱"——逻辑很简单，用旗舰模型纯属浪费。

理想情况是：主 Agent 用 `deepseek-v4-flash` 这种强模型，子任务用更便宜的模型单独跑，互不影响。

上一篇讲的 Skill 做不到这件事——它被设计成强制继承主模型。这篇要讲的 **Sub-Agent**，规则正好反过来。

## Sub-Agent 的核心规则：可以拥有，而不是只能借

```java
SubAgentConfig.builder()
    .model("aliyun:qwen-plus")   // 自己的模型，跟主 Agent 完全独立
    .ownTools(List.of(myPrivateTool))   // 私有工具，外面看不到
    .build();
```

对比一下两种类型的规则差异：

| | Skill | Sub-Agent |
|---|---|---|
| 模型 | 强制继承父 Agent | 自己的模型，或写 `"inherit"` 继承 |
| 工具 | 只能借（`allowedTools`） | 私有拥有（`ownTools`），外部不可见 |
| 适合 | 跟主 Agent 紧耦合的轻量子流程 | 需要隔离、需要独立模型的独立子任务 |

`model` 字段默认值就是 `"inherit"`——不配就跟 Skill 一样继承父模型；一旦写了具体值（比如 `"aliyun:qwen-plus"`），就会走独立的 `ModelProvider` 分支单独构建一个模型实例。

## 实战代码

仓库里的 [`ExampleReadme03SubAgentTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java)：一个 `expense_estimator` Sub-Agent，自己的模型 `aliyun:qwen-plus`，自己的私有工具 `estimate_trip_cost`：

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

主 Agent 跑 `deepseek-v4-flash`，`expense_estimator` 内部跑 `aliyun:qwen-plus`——两个模型互不干扰，各自配各自的。

## 关键点：ownTools 为什么外面看不到

`estimate_trip_cost` 这个工具，**从头到尾没有出现在 `withTool` 或者 marketplace 的任何注册调用里**。它只存在于 `expenseEstimator.ownTools` 这一个地方。

这意味着：主 Agent 的 Planner 在 Search 阶段，候选列表里压根不会出现 `estimate_trip_cost`，它没法直接调用这个工具——唯一的入口是先选中 `expense_estimator` 这个 Sub-Agent，工具才会在它内部的执行循环里被用上。

这是真正意义上的"私有"——不是权限控制，是从能力市场的可见性上就把它隔离掉了。适合那些你不想让外层 Agent 瞎调用、必须经过子任务封装好的逻辑统一处理的场景。

## 小结：Skill 还是 Sub-Agent，三秒判断

问自己一句话：**这个子能力需要自己的模型，或者需要外部完全看不到的私有工具吗？**

- 要——用 Sub-Agent
- 不要，就是想复用主 Agent 的模型省成本——用 Skill

两种类型不是谁更高级，是这套 harness 解决不同问题的工具。下一篇会讲怎么把这两种类型连同普通 tool 一起，用注解打包进一个类里，一次注册全搞定。

---

📌 上一篇：[02. Skill：只能借工具不能占](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md) ｜ 下一篇：[04. @Plugin 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/04-plugin-annotation.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
