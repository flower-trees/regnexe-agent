# 长任务的暂停-恢复机制设计：开源框架 Regnexe 怎么做到的

> 「Regnexe 实战系列」第 8 篇（共 10 篇），对应仓库 [`ExampleReadme08PauseResumeTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme08PauseResumeTest.java)。上一篇：[07. Agent 记忆为什么要拆三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/07-three-layer-memory.md)。

## 一个生产环境绕不开的需求

任务跑到一半，用户突然要补充一条信息，或者想先暂停去做别的事——这个需求在 Demo 阶段基本不会被考虑，但只要 Agent 真正跑起来处理稍微长一点的任务，说停就停、说续就续几乎是刚需。

很多框架的"暂停"本质上是杀掉进程/线程，恢复就是重新跑一遍，上下文全丢，等于没有暂停。Regnexe 把这件事做成了一等公民能力：`pause()` 可以从任意线程调用，状态会持久化，`resume()` 能带着新的上下文接着跑。

## 用 CountDownLatch 精确控制测试时序

暂停功能不好写单测，你怎么保证"恰好在工具执行中间"调用 `pause()`？仓库 `ExampleReadme08PauseResumeTest` 用两个 `CountDownLatch` 卡住时序，不依赖真实网络延迟去"赌"时间窗口：

```java
CountDownLatch toolStarted = new CountDownLatch(1);
CountDownLatch pauseSignaled = new CountDownLatch(1);

Tool slowWeatherTool = Tool.builder()
        .name("get_weather")
        .description("Get today's weather for a city.")
        .params("city: String -- city name")
        .func(city -> {
            toolStarted.countDown();                      // 告诉外面：工具已经开始执行
            try {
                pauseSignaled.await(15, TimeUnit.SECONDS);  // 卡住，等外面发出暂停信号
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Beijing: sunny, 22 C, excellent air quality.";
        })
        .build();

RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withTool(slowWeatherTool)
        .withTaskStore(new InMemoryTaskStore())   // 暂停/恢复必须有持久化状态，这一行不能省
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(3)
        .build();

TaskRequest request = new TaskRequest();
request.setSessionId(UUID.randomUUID().toString());
request.setGoal("Check today's weather in Beijing. Is it good for running?");

ExecutorService pool = Executors.newSingleThreadExecutor();
Future<AgentResult> future = pool.submit(() -> agent.execute(request));

toolStarted.await(30, TimeUnit.SECONDS);   // 确认工具已经在跑

agent.pause();              // 在任意线程调用——这里就是主测试线程
pauseSignaled.countDown();  // 放开工具，让它正常返回

AgentResult paused = future.get(30, TimeUnit.SECONDS);
// paused.getStatus() == PAUSED

AgentResult resumed = agent.resume(request.getSessionId(), "Also factor in today's air quality index.");
// resumed.getStatus() == FINISHED
```

## pause() 不是立刻杀死任务

它设置的是一个停止信号，正在执行的工具调用会自然走完，下一次"是否继续"的检查点才会真正让任务停下来，状态落到 `PAUSED`，持久化进 `TaskStore`。粗暴中断一个正在写数据的工具调用，比等它跑完更危险，这是刻意的设计选择。

## resume() 不是从头再来一遍

它会找到该 `sessionId` 下最近一次可恢复的任务，带着你传入的补充上下文接着跑，之前几轮的执行记录（上一篇讲的 Task 账本）会被自动注入进续传的 Prompt，模型知道"之前已经做了什么"，不会从零开始重复劳动。

`withTaskStore(new InMemoryTaskStore())` 这一行不能省，暂停状态总得存在某个地方。生产环境想要跨进程、跨实例恢复，就得换成自己的持久化实现——这跟前面讲的 `Marketplace`、`ConversationStorage` 是同一套设计哲学：默认内存实现，关键扩展点全部接口化。

## 适用场景

长耗时任务用户中途想插入新信息，需要人工审批介入的流程，系统资源紧张时主动暂停低优先级任务——这些场景的共同点是：任务执行不再是一次性的方法调用，而是一个可以被外部干预、随时查看进度、随时打断又能无损续上的有状态过程，这是这套 harness 提供的能力。

---

这套暂停恢复机制如果对你的长任务处理场景有参考价值，欢迎去仓库给个 Star，也欢迎反馈使用中遇到的问题。

📌 上一篇：[07. Agent 记忆为什么要拆三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/07-three-layer-memory.md) ｜ 下一篇：[09. 可观测性：一行代码切换调试/生产](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/09-observability.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
