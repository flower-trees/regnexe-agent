# Planner 选中不存在的 capability id：prompt 提示 + 运行时校验兜底

- 状态：**已实现**
- 涉及仓库：`regnexe-agent`（`TaskPlanner`）
- 背景：`salt-robot-skills` 真实多轮任务（robot-article-writing）中，某一轮 Plan 选中了
  `"255"`、`"105"` 两个纯数字 id，Marketplace 里完全没有这两个 id（真实 capability id
  都是语义化的名字，比如 `robot-article-writing`、`tavily_tavily-search`），导致
  `CapabilityExecutor.resolveDescriptor()` 两个都解析不到、整轮 0 个工具被执行，靠
  Reflector 的 guard 规则（"选了 N 个能力但本轮没执行任何工具"）兜底识别出异常才没有
  静默卡死，但已经白白浪费了一整轮（Search→Plan→Execute 空转→Reflect）。

## 一、根因

`TaskPlanner` 把 `selectedCapabilityIds` 当作 LLM 输出里"已验证过"的字段直接信任：解析
成功就直接用，从不检查这些 id 是不是真的在这一轮 Search 返回的候选列表（`candidates`）
里出现过。要等到 Execute 阶段 `CapabilityExecutor.resolveDescriptor(capId)` 返回 null 才
发现"这个 id 根本不存在"——这时候一整轮已经跑完了，纯浪费。

另外，`TaskPlanner.SYSTEM_PROMPT` 里原来也没有任何一条规则明确告诉模型"id 必须从
Available capabilities 列表里原样抄"——结合这一轮候选数量较多（`CapabilitySearcher`
里 `query.setTopK(50)`），对偏小/偏快的模型来说，在 50 条候选里精确复现一个字符串 id
本身就是容易出错的任务，没有强调"必须抄对"更容易放大这个问题。

## 二、修复

### 1. prompt 加一条硬性规则

```java
private static final String SYSTEM_PROMPT = """
        ...
        Rules:
        - ID FIDELITY: every id in selectedCapabilityIds MUST be copied character-for-character \
          from the "Available capabilities" list below. Never invent, guess, abbreviate, or use a \
          bare number/index as an id — if you are not certain an id is listed there, do not include it.
        - Select only capabilities that are genuinely relevant to the goal.
        ...
        """;
```

放在 Rules 列表最前面，因为 id 的正确性是后面所有规则的前提。

### 2. Plan 阶段立刻校验，复用现有重试预算自我纠正

`tryParsePlan()` 解析成功后，不直接 `break` 退出重试循环，先对比这一轮真实的
`candidates` 列表：

```java
PlanOutput parsed = tryParsePlan(lastRaw);
List<String> invalidIds = invalidCapabilityIds(parsed, candidates);
if (parsed != null && invalidIds.isEmpty()) {
    plan = parsed;
    break;
}
if (attempt < MAX_PLAN_RETRIES) {
    messages.add(BaseMessage.fromMessage(MessageType.AI.getCode(), lastRaw));
    if (parsed == null) {
        messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), PARSE_ERROR_CORRECTION));
    } else {
        messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), invalidIdCorrection(invalidIds)));
    }
} else if (parsed != null) {
    // 重试次数用完，但 JSON 本身是合法的——只去掉还是不存在的 id，不整个丢弃这个 plan
    // （丢给 recoverPlan() 会把 narrative 和其它选对的 id 也一起扔掉）。
    List<String> cleaned = new ArrayList<>(parsed.getSelectedCapabilityIds());
    cleaned.removeAll(invalidIds);
    parsed.setSelectedCapabilityIds(cleaned);
    plan = parsed;
}
```

`invalidIdCorrection(invalidIds)` 把具体哪些 id 不存在喂回给模型，让它在同一轮内自我纠
正，跟现有的 `PARSE_ERROR_CORRECTION`（JSON 解析失败重试）共用同一个重试预算
（`MAX_PLAN_RETRIES`），不额外增加成本。

重试预算用完后仍有无效 id 的兜底策略：**保留 plan，只剔除无效 id**，而不是走
`recoverPlan()` 的"整段丢弃、用确定性兜底方案"——因为这种情况下 JSON 结构本身是对的，
narrative 和其它选对的 id 都还有价值，没必要连带丢掉。

## 三、未覆盖的部分

`expandSelectedAllowedTools()` 从已匹配候选的 `allowedTools` 字段展开出来的 id（技能/
子代理声明的依赖工具）不在这次校验范围内——那些 id 来自可信的配置数据（marketplace 里
真实注册的 capability），不是 LLM 输出，不存在"幻觉"风险；如果配置本身写错了依赖 id，
`CapabilityExecutor` 的 "Capability not found" 警告依然会独立兜底捕获。
