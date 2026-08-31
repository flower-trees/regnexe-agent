# Planner ↔ Executor 迭代预算协调（`iterationsHint` 单向问题）与 Plan/Reflect 分角色模型

- 状态：**iterationsHint 双向修复 + 分角色模型支持均已实现，单元测试通过（`Example00GettingStartedTest`/`Example01WeatherForecastTest`，12/12 + 11/11），真实端到端未系统性复测**
- 涉及仓库：`regnexe-agent`（`TaskPlanner`、`ContextBusKeys`、`RegnexeAgent`）
- 背景：`salt-robot-skills` 真实 skill 全流程测试中，一个本该很快做完的任务（把4篇新文章加进资讯轮播——2次 DB 写入 + 1次 readback 验证，真实跑下来大概10来轮就该结束）在**真正提交成功、readback 确认无误之后**，模型没有停下来，继续调用工具——先是重复验证，然后带着幻觉路径 `cd /` 去搜整个磁盘找 `lib/db.py`。真实原因不是模型瞎但是 harness 没给它"你已经做完了"的信号：`Reflector`（专门判断 FINISH/CONTINUE/ESCALATE 的裁判节点）只在**整个 Planner 轮次结束后**才跑一次，而"一轮"内部的 ReAct 工具调用循环本身没有上限收紧机制，只受全局 `max_agent_iterations`（这次配置是 60）限制。

## 一、根因：`iterationsHint` 只能加预算，不能减预算

`PlanOutput.iterationsHint` 这个字段本意是让 Planner 估算"这一轮大概需要多少步"，但 `TaskPlanner.java` 里应用这个估算值的代码：

```java
// 修复前
if (plan.getIterationsHint() != null && plan.getIterationsHint() > 0) {
    Integer current = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS);
    int hint = Math.min(plan.getIterationsHint(), MAX_SAFE_ITERATIONS);
    if (current == null || hint > current) {          // ← 只在"更大"时才生效
        bus.putTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS, hint);
    }
}
```

只在 `hint > current` 时才覆盖——也就是说这个机制**只能替 Planner 申请更多预算，不能替它申请更少**。而 Planner 自己的 prompt 里更进一步明确指示："如果算出来的总量 <= 默认值，就把这个字段留空"：

```
Sum the costs for all planned operations, then add a 25% buffer.
If the total is <= the default (20), omit the field or set it to null.
```

于是即便 Planner 正确判断出"这一轮很简单，10来步就够"，它的反应也是**什么都不做**——而不是主动把这一轮的预算收紧。结果是：只要没人主动"喊更多"，每一轮拿到的预算永远是全局默认值（这次是60），跟 Planner 自己估算出的更小、更靠谱的数字无关。附带发现：prompt 里那个 "20" 是写死的字面量，跟实际配置的 `max_agent_iterations`（这次是60）对不上，本身就是过时的参照物。

## 二、修复

### 1. `iterationsHint` 改成双向生效

```java
// 修复后
if (plan.getIterationsHint() != null && plan.getIterationsHint() > 0) {
    int hint = Math.min(plan.getIterationsHint(), MAX_SAFE_ITERATIONS);
    int hintWithMargin = (int) Math.ceil(hint * 1.3);   // 30% 安全余量
    Integer current = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS);
    if (current == null || hintWithMargin != current) {
        bus.putTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS, hintWithMargin);
    }
} else {
    // 这一轮没给 hint（Planner 认为默认值够用，或者走的是无候选能力的直接回答分支）——
    // 重置回真正的原始默认值，不要静默沿用上一轮可能留下的覆盖值。
    bus.putTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS, defaultIterations);
}
```

两个关键设计决定：

- **加 30% 安全余量，不卡死在 Planner 的原始估算上**——`iterationsHint` 是 LLM 的估算，不是保证，重试、多一次确认框、临时需要的额外工具调用都会吃掉预算。直接卡死在估算值上，会有真实进行中的轮次被腰斩的风险（正好在 `Reflector` 的"零工具调用不许 FINISH"保险丝本该自然放行的时候被过早打断）。
- **每一轮的覆盖值必须相对"真正的原始默认值"计算，不能相对"当前 `MAX_AGENT_ITERATIONS` 的值"计算**——`ContextBusKeys.MAX_AGENT_ITERATIONS` 是整个任务生命周期内共享、可变的一个值（`RegnexeAgent` 只在任务开始时写一次，`TaskPlanner` 每轮都可能覆盖它，`CapabilityExecutor` 每轮读取当前值）。如果直接用它做双向覆盖的参照系，会出现"第1轮收紧到10，第2轮 Planner 没给 hint（以为会拿到全局默认值），结果第2轮被第1轮的收紧值卡住"这种跨轮次污染。为此新增了一个不可变的基准值：

```java
// ContextBusKeys.java（新增）
public static final String MAX_AGENT_ITERATIONS_DEFAULT = "maxAgentIterationsDefault";
```

`RegnexeAgent.buildTransmitMap()` 在任务开始时把它和 `MAX_AGENT_ITERATIONS` 一起设成同一个初始值，此后永不覆盖；`TaskPlanner` 每一轮都从这个不变的基准值出发计算本轮的有效预算，不看上一轮可能已经改动过的"当前值"。

### 2. prompt 里的硬编码 "20" 改成动态引用真实配置值

`SYSTEM_PROMPT` 原来是纯字符串常量，现在通过 `.formatted(defaultIterations)` 在构造 system message 时把真实的默认值代入：

```
If the total is <= the default (%s), omit the field or set it to null — but if this
round's plan is simpler than that default suggests, set iterationsHint to your own
lower estimate anyway: it also tightens the budget, not just raises it, ...
```

顺带把 prompt 原文里那条 "omit when <= default" 的指示改成了"即使 <= 默认值，也建议给出更低的估算，因为这个字段现在也能收紧预算"——不然即使代码层面支持了双向，Planner 按旧指示还是会继续留空，双向机制形同虚设。

## 三、验证

- `regnexe-agent` 编译通过；`Example00GettingStartedTest`/`Example01WeatherForecastTest`（走真实 Planner→Execute→Reflect 全流程，含真实 LLM 调用）12 个用例全部通过，`FINISHED`/单轮完成，未观察到因新增的 `.formatted()` 调用或双向覆盖逻辑引入的回归。
- **未做**：没有专门构造一个"简单任务、Planner 应该给出收紧的 iterationsHint"的真实场景去复测这个双向覆盖本身有没有按预期生效（比如确认它真的把类似"轮播"这种任务的预算从 60 降到十几，且不影响后续轮次）——这是下一步用户自己真实跑一遍类似任务时要观察的点。

## 四、Plan / Execute / Reflect 分角色模型配置

**讨论起点**：既然 `Reflector` 的 FINISH 误判是"单向门"（判错了没有下一轮能纠正，不像 Planner 的错误活在循环里能自愈），那 Reflector（以及同样是单次结构化 JSON 调用、不随轮数摊薄成本的 Planner）换更强模型、Execute（真正烧 token 的多轮工具调用循环）留在便宜档，是一个合理的成本/收益不对称——花小钱买判断质量，不放大真正贵的那部分。

**实现**：`regnexe-agent` 加了两个可选的按角色模型覆盖，不设置就退回 `defaultModel`（不破坏现有单模型部署）：

- `ContextBusKeys.PLANNER_MODEL` / `REFLECTOR_MODEL`：新增的 transmit-map key。
- `RegnexeAgentBuilder.Builder#withPlannerModel(vendor, model)` / `#withReflectorModel(vendor, model)`：新增 builder 方法，vendor 是独立参数（agent 层从一开始就没限制必须跟主模型同厂商）。
- `TaskPlanner.java`/`Reflector.java`：各自读取自己的角色 key，`null` 则退回 `DEFAULT_MODEL`。
- `regnexe-cli`：`RexConfig.ModelConfig` 新增 `planner_name`/`reflector_name`，以及 `planner_vendor`/`planner_api_key`/`reflector_vendor`/`reflector_api_key`（都可选，不设置就沿用主模型的 vendor/api_key）——补上是因为真实用户场景就是"可能跨厂商"，不是纯粹换档位。`CliMain.main()` 新增 `wireRoleApiKey()`，给每个角色的 vendor 单独设置对应的 Spring key 属性；如果用户配了 `<role>_vendor` 却忘了配 `<role>_api_key`，会在启动时打印警告而不是静默失败或者误发主模型的 key。

真实配置示例（这次用户在 `~/.rex/config.yml` 里配的）：

```yaml
model:
  vendor: deepseek
  name: deepseek-v4-flash        # Execute 用
  planner_name: deepseek-v4-pro  # Planner 用
  reflector_name: deepseek-v4-pro  # Reflector 用
  api_key: ${DEEPSEEK_KEY}
```

**验证**：`regnexe-agent` 编译通过，`Example00GettingStartedTest`（11个用例，`plannerModel`/`reflectorModel` 均为 null 的默认路径）全部通过，确认新增的可选参数没有破坏现有单模型场景。真实按角色分模型跑了一次简单任务（`date` 查询）冒烟测试，`FINISHED`、单轮完成、无异常——确认三层分模型配置不会导致连不上/崩溃。**跨厂商警告也真实测过**：临时把 `planner_vendor` 改成 `aliyun`、不配 `planner_api_key`，启动时正确打印：

```
[warn] model.planner_vendor is set to 'aliyun' but model.planner_api_key is empty — planner
will likely fail to authenticate unless ALIYUN_KEY is already set another way (env var, -D
system property).
```

没有崩溃，也没有误把主模型（deepseek）的 key 发给 aliyun。测完已经把配置改回去了。

**未做**：没有验证 Planner/Reflector 调用时实际发出的 HTTP 请求 `model`/vendor 字段确实符合预期（这几次冒烟测试只确认了"配了不崩、警告逻辑对"，没有从网络层核实"确实生效、确实是那个模型/厂商在响应"）——这条如果要较真，需要开 DEBUG 日志或者抓包核实；也没有真实测过"两个角色配了两个都存在、都能连通的不同厂商"这种完整跨厂商成功路径，只测了"配了但缺 key"这一种失败路径。
