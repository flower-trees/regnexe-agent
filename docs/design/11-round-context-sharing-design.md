# 轮次上下文共享设计：工具调用记录拍平 + 批量压缩

- 状态：**已实现**
- 涉及仓库：`regnexe-agent`（`TaskExecutionState`/`ExecutionOutput`/`ReflectionDecision`/`CapabilityExecutor`/`Reflector`/`TaskPlanner`/`DefaultResultComposer`，新增 `common/util/RoundRecords.java`）
- 关联文档：09 号（上下文压缩）、10 号（单次工具结果溢出）——这次解决的是第三个维度："Execute 对过去几轮完全没有直接可见性"。08 号文档提出的 `roundSummary` 字段被这次的机制取代，不再需要。

---

## 一、问题

`CapabilityExecutor`（Execute）过去对 `state.rounds` 里的历史几乎零读取——唯一涉及 `rounds` 的地方是 `currentRound(state)`（取当前轮的写入目标）。Execute 唯一能了解"之前发生了什么"的渠道，是 Planner 这一轮愿不愿意在 narrative 里转述——而 Planner 自己看到的历史也很薄（只有 `roundSummary`，经常是空的，因为 08 号设计要求 Reflector 每轮都顺带产出一份摘要，绝大多数时候这份摘要根本用不上，纯粹是投机性劳动）。

信息链条：真实发生的事 → Reflector 提炼 `roundSummary`（经常没写好/空）→ Planner 转述 → Execute 读转述。每转一手都在损耗。

## 二、方案

**不再层层转述，让 Execute 直接读第一手、结构化的数据**：把 `ToolExecutionRecord` 从 `RoundRecord.executionResult` 里拍平出来，变成 `TaskExecutionState` 上的一个任务级共享列表；压缩改成 Layer 1（`PeriodicConversationSummaryMemoryStorer`）那种"攒够阈值整批压一次"，不是 `SlidingWindowContext` 那种"超一次压一次"（轮次可能非常多，滚动式代价太高）。

### 数据结构

```java
// TaskExecutionState 新增
private List<ToolExecutionRecord> toolExecutions;   // 全任务共享，不再按轮嵌套
private String earlyRoundsSummary;                    // 老轮次压缩后的摘要，滚动更新

// ExecutionOutput 删除
// private List<ToolExecutionRecord> toolExecutions;   // 已拍平，改存 finalText/status/partialContext 即可

// ReflectionDecision 删除
// private String roundSummary;   // Reflector 不再兼职写摘要
```

`ToolExecutionRecord` 本身早就带 `round` 字段（自证是哪一轮的），拍平之后不需要额外结构就能按轮过滤/分组。

### 写入路径

`CapabilityExecutor` 不再攒局部 `ArrayList` 再拷贝进 `RoundRecord`，直接对 `state.getToolExecutions()`（懒初始化）append。

### 读取路径

- **Reflector**：判断这一轮时，按 `round == state.getCurrentRound()` 过滤 `state.getToolExecutions()` 取工具调用数（guard 规则、"Tools executed this round" 计数）；`buildPrompt()` 里判断"是否完成"仍然只读 `execText`（≈finalText），不逐条读工具调用——Reflector 不需要调用级别的细节。
- **Execute**：`buildAgentInput()` 新增"Progress so far"——`earlyRoundsSummary`（若非空）+ 当前 `state.getToolExecutions()` 里属于**更早轮次**（`round < state.getCurrentRound()`）、还没被压缩掉的原始记录，按轮分组渲染。天然有界（压缩会定期清空老记录），不需要额外截断。单条记录本身也已经因为 10 号文档的 `ToolOutputOverflow` 在源头被限制在 2000 字符以内。
- **TaskPlanner**：`earlyRoundsSummary` + 最近 `RECENT_ROUNDS_WINDOW`（3）轮的 `finalText`，逻辑跟之前一样，只是不再有 `roundSummary` 这层，直接读 `finalText`。

### 压缩：批量式，仿照 Layer 1

```java
private static final int ROUND_COMPACT_PERIOD = 5;

private void compactToolExecutionsIfNeeded(state, chainActor, llmProvider, defaultModel) {
    List<ToolExecutionRecord> all = state.getToolExecutions();
    if (all.isEmpty()) return;
    int minRound = all.stream().mapToInt(ToolExecutionRecord::getRound).min().orElse(currentRound);
    if (currentRound - minRound + 1 < ROUND_COMPACT_PERIOD) return;   // 还没攒够，不压

    String batchText = renderForCompaction(all);                      // 全量整批
    String updated = callSummaryLlm(earlyRoundsSummary, batchText);   // 复用 defaultModel，跟 Layer 3 同一个
    // LLM 调用失败：退化成纯文本拼接，不让压缩本身拖垮主流程
    state.setEarlyRoundsSummary(updated);
    all.clear();                                                      // 整批清空，不是"留个尾巴"
}
```

触发点：Reflector 每轮跑完之后检查一次（不是每次 append 就检查），跟 Layer 1 的"每个完整回合检查一次阈值"是同一个粒度。跟 `SlidingWindowContext` 的差异：那个是每超一步就压一步（步数一般不多，代价可接受）；这里是轮次级别，任务可能跑几十轮，滚动式压缩次数太多，改成整批压缩、压完清零。

## 三、顺带修的隐患：`currentRound()` 按位置取值

`CapabilityExecutor`/`Reflector`/`TaskPlanner` 原来各有一份 `rounds.get(rounds.size() - 1)`——按列表最后一个元素取"当前轮"，隐含"列表从不缩短"的假设。这次统一抽成 `RoundRecords.current(state)`，按 `roundNumber == state.getCurrentRound()` 显式匹配，不再依赖位置假设，三份重复代码合并成一份。

（这次压缩动的是 `state.toolExecutions`，不是 `state.rounds` 本身——`rounds` 列表依然按位置递增、从不删除，所以这个隐患目前不会被直接触发，纯粹是防御性加固，为将来 `rounds` 本身也要做类似处理时不留坑。）

## 四、验证方式

`regnexe-agent`/`regnexe-cli` 编译通过，真实起了一个会调用 bash 的任务，直接查 `~/.rex/rex.db` 里持久化的 `TaskExecutionState` JSON 确认：
- `tool_executions` 是顶层字段，不再嵌套在 `rounds[i].execution_result` 里
- `round.execution_result` 只剩 `final_text`/`status`
- 记录带正确的 `round` 值
- 任务真实跑了 2 轮，两轮的 `execution_result` 都正确写入各自的 `RoundRecord`（验证 `RoundRecords.current()` 按轮号匹配在多轮场景下是准的）

压缩逻辑（攒够 5 轮触发）没有在真实测试里触发（测试任务只跑了 2 轮），逻辑本身在代码走查层面确认跟 `PeriodicConversationSummaryMemoryStorer` 同构，未来跑到长任务时会自然验证到。

## 五、暂不处理的

- `state.rounds` 本身不做压缩，只压 `state.toolExecutions`——`rounds` 里每条记录现在很小（narrative + finalText + reflection.action/reason，不含工具调用日志），暂时不构成问题。
- Planner/Reflector 各自维护独立持续对话（比这次方案更大的重构，讨论过但明确搁置）。
