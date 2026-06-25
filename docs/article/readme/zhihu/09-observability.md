# Agent 系统出问题怎么排查？聊聊可观测性设计

先说结论：Agent 系统排查问题难，不是因为日志少，是因为大部分实现压根没把"决策过程"当成一等公民数据——只记录了输入输出，没记录"为什么选了这个工具""为什么判断任务完成了"。这篇是系列最后一篇，聊聊 Regnexe 怎么处理这件事。

> 「Regnexe 实战系列」第 9 篇（共 10 篇），对应仓库 [`ExampleReadme09ObservabilityTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme09ObservabilityTest.java)。上一篇：[08. 长任务的暂停-恢复](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/08-pause-resume.md)。

## 排查 Agent 问题，难在哪

写普通业务代码，出了 bug 看堆栈、看日志，基本能定位。Agent 系统不一样——一次任务背后是"搜了哪些能力、规划选了哪几个、工具传了什么参数、模型回了什么、要不要继续"一整条链路，任何一环出问题，最终表现可能只是"答案不对"，但具体卡在哪一步，黑盒模式下根本无从下手。

Regnexe 这套 harness 的解法是：**Search → Plan → Execute → Reflect 每一步，加上 Execute 内部的每一次工具调用，全部对外发事件**，接一个监听器就能看到完整链路——毕竟 harness 的职责就是"驱动并暴露整个执行过程"，不暴露事件流等于只做了一半。

## 默认方案：ConsoleEventListener

这个系列从第一篇开始，每个代码示例里其实都已经在用它：

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool)
    .withEventListener(new ConsoleEventListener())
    .build();
```

跑起来直接看到完整链路：

```text
[Agent Start   ] R0 Goal: Check today's weather in Beijing. Is it good for running? | maxRounds: 3
[Search Result ] R1 Found 1 capabilities: get_weather
[Plan Result   ] R1 Selected: [get_weather] | Strategy: RETURN_LAST | ...
[TOOL Call     ] R1 get_weather {"city": "Beijing"}
[TOOL Result   ] R1 get_weather -> Beijing: sunny, 22 C, excellent air quality.
[Execute Result] R1 SUCCESS | Beijing: sunny, 22 C, excellent air quality.
[Reflect Result] R1 FINISH — weather data obtained, goal fully answered.
[Agent Done    ] R1 Status: FINISHED | Rounds: 1
```

调试阶段这就够用——Search 有没有找对能力、Plan 选的对不对、工具参数对不对、Reflect 判断逻辑合不合理，一行行都能对上。

## 进阶：按需打开 Token 和原始 LLM 输出

默认情况下 Token 消耗和模型原始响应文本是被过滤掉的。但排查"为什么这一步选错了"，有时候就得看模型当时到底"想了什么"：

```java
new ConsoleEventListener(true, true);   // showTokenEvents=true, showLlmEvents=true
```

打开之后会多出 `TOKEN_USAGE`、`CAPABILITY_TOKEN_USAGE`、`TASK_TOKEN_SUMMARY`，以及 `PLAN_LLM_RESPONDED`/`REFLECT_LLM_RESPONDED` 这类原始模型响应。前者用来做成本核算，后者用来排查模型这一步到底是怎么推理的。

## 生产环境：换成 Slf4jEventListener

`println` 本地调试方便，丢进生产环境就是灾难——日志不进统一采集系统，没法跟其他业务日志关联检索。

```java
regnexeAgentBuilder.withEventListener(new Slf4jEventListener()) ...
```

事件文本跟 `ConsoleEventListener` 一字不差，区别只是走 SLF4J，自动汇入应用现有的日志管道。调试用 Console，上线换 Slf4j，业务代码一行不用改——这跟前面几篇讲的 `Marketplace`、`ConversationStorage`、`TaskStore` 是同一套设计风格：默认实现简单，关键扩展点全部接口/可替换化。

## 想写自己的监听器

`ConsoleEventListener` 和 `Slf4jEventListener` 都继承自 `AbstractEventListener`，这个基类把"按类型过滤"和"格式化文本"都做好了，只需要关心 `onEvent`：

```java
public class MyDashboardListener extends AbstractEventListener {
    public MyDashboardListener() {
        super(false, false);   // 不要 Token 和原始 LLM 事件
    }

    @Override
    public void onEvent(AgentEvent event) {
        websocket.push(format(event));   // 推到前端实时展示
    }
}
```

接前端做实时进度展示，或者接监控系统做异常告警，都是这一个扩展点的事。

## 系列回顾

到这里，「Regnexe 实战系列」10 篇全部更新完毕：从 [00. 开篇](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md) 的"为什么单次工具调用不够用"讲起，依次拆解了 `withTool` 快速接入、Skill / Sub-Agent 两种子能力的本质区别、`@Plugin` 注解打包、四种插件加载方式、可替换的 Marketplace、三层记忆模型、暂停恢复，到这一篇的可观测性——9 个示例全部来自仓库里真实可跑的 `ExampleReadme01~09Test`。

一句话总结这套 harness：**Search 收窄能力范围，Plan 把目标拆成步骤，Execute 真正执行多种类型的能力，Reflect 检查结果是不是真的做完了**——四步闭环 + 可插拔的市场/记忆/可观测性，这就是 Regnexe 想做的事。

---

如果你也在做 Agent 系统的可观测性设计，最头疼的是哪一类问题——是排查决策错误，还是控制 Token 成本？欢迎评论区聊聊，仓库地址在下面，有问题也欢迎提 issue。

📌 上一篇：[08. 长任务的暂停-恢复](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/08-pause-resume.md) ｜ 系列开篇：[00. 为什么大多数 AI Agent 只是一次工具调用](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
