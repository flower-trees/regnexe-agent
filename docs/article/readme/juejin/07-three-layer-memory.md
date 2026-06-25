# Agent 记忆为什么要拆三层？我踩过的真实教训

> 「Regnexe 实战系列」第 7 篇（共 10 篇），对应仓库 [`ExampleReadme07ThreeLayerMemoryTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme07ThreeLayerMemoryTest.java)。上一篇：[06. 能力市场换成数据库](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/06-marketplace.md)。

## 🧩 一个容易混淆的概念

"Agent 记忆"这个词在很多教程里是个筐，什么都往里装：历史对话、执行过程、工具调用轨迹……结果就是写代码的时候，一个 `Map<String, Object>` 存了三种完全不同生命周期的数据，排查问题的时候根本分不清是哪一层出的 bug——这个坑我自己踩过，所以这篇要专门聊聊。

Regnexe 把"记忆"拆成了三个独立的层，互相之间没有继承关系，谁出问题只查一层：

```
Session 记忆      "这个用户之前的任务里说过什么？"
Task 执行账本     "这次 execute()/resume() 每一轮发生了什么？"
Agent 执行上下文  "单次工具调用循环带多少历史？"
```

## 1️⃣ Session 记忆：跨任务的长期记忆

解决的问题：用户这次问"根据刚才的天气，建议我穿什么"，Agent 怎么知道"刚才"指的是什么。

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool())
    .withSessionStorage(new InMemoryConversationStorage())   // 第一层：跨任务会话历史
    .build();

TaskRequest first = new TaskRequest();
first.setSessionId(sessionId);
first.setGoal("Check today's weather in Beijing.");
agent.execute(first);

TaskRequest second = new TaskRequest();
second.setSessionId(sessionId);   // 同一个 sessionId
second.setGoal("Based on that weather, what should I wear today?");
agent.execute(second);   // 不用重新查天气，直接复用上一轮结果回答
```

关键是 `sessionId` 相同——两次 `execute()` 通过它串联起来。默认实现是 `InMemoryConversationStorage`，生产环境想跨实例共享就换成自己的 `ConversationStorage` 实现，跟上一篇的 `Marketplace` 是一个套路。

## 2️⃣ Task 执行账本：这一次任务到底经历了什么

解决的问题：一次 `execute()` 内部跑了几轮 Search/Plan/Execute/Reflect，每一轮选了什么能力、执行结果是什么——这些过程数据需要持久化，用于审计和断点恢复（下一篇细讲）。

```java
InMemoryTaskStore taskStore = new InMemoryTaskStore();
RegnexeAgent agent = regnexeAgentBuilder.withTool(weatherTool()).withTaskStore(taskStore).build();

AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");

TaskExecutionState ledger = taskStore.load(result.getTaskId()).orElseThrow();
ledger.getRounds().forEach(r ->
    System.out.println(r.getRoundNumber() + " | search=" + (r.getSearch() != null)
                      + " | plan=" + (r.getPlan() != null)
                      + " | execution=" + (r.getExecutionResult() != null)
                      + " | reflection=" + (r.getReflection() != null)));
```

跑完之后能看到这次任务每一轮具体做了什么——排查"为什么这次任务选错了能力"，这是最直接的数据来源，比翻日志方便得多。

## 3️⃣ Agent 执行上下文：单轮内部的工具调用历史

解决的问题：Execute 阶段内部如果要连续调用好几次工具（先查景点、再查餐厅、再生成行程），这些中间过程的历史要不要全部带给模型？带多了拖慢推理、费 token；带少了模型会"失忆"重复调用。

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool())
    .withAgentContext(SlidingWindowContext.builder().windowSize(5).build())   // 第三层：滑动窗口策略
    .build();
```

默认是 `FullContext`，不压缩、全量带上。工具调用轨迹一旦变长，换成 `SlidingWindowContext` 之类的有界策略，只保留最近若干步。

## 🚨 拆开和混在一起，差别到底在哪

设想一下如果这三层揉成一个"记忆模块"：Session 记忆要长期保留（跨多次任务），Task 账本只服务于当前这一次任务，生命周期完全不同——混在一起，要么 Session 记忆被随手清空，要么 Task 账本永久膨胀。Agent 上下文是"单轮内部"的临时数据，跟前两层根本不是一个维度——混进去之后，想单独换一个上下文压缩策略，会牵连到不该被影响的 Session/Task 数据。

拆成三层之后，每一层都可以独立替换、独立排查，互不传染。这不是为了显得架构复杂而拆的，是因为这三个问题原本就不该用同一个答案去解决——这套 harness 在"记忆"这件事上的态度，跟它在 Marketplace 上的态度是一致的：把不同维度的关注点拆开，而不是塞进一个万能结构。

---

你们项目的"会话历史"和"执行日志"是分开存的吗？混在一起踩过什么坑，评论区聊聊，点个赞 👍

📌 上一篇：[06. 能力市场换成数据库](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/06-marketplace.md) ｜ 下一篇：[08. 长任务说停就停说续就续](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/08-pause-resume.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
