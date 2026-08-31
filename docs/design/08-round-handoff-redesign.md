# 轮次交接重设计：finalText 职责拆分 + Reflector 产出结构化 roundSummary

- 状态：**设计已讨论定调，准备实现**
- 涉及仓库：`regnexe-agent`（`task.worker.Reflector`/`TaskPlanner`/`CapabilityExecutor`、`task.state.reflection.ReflectionDecision`、`task.DefaultResultComposer`）
- 关联文档：跟 07 号文档（`iterationsHint`/分角色模型）同一批真实测试中发现，是同一条"Plan/Execute/Reflect 怎么配合"主线下的另一个具体问题，两者无直接依赖
- 背景：`salt-robot-skills` 真实资讯写作任务连续多轮测试中，观察到两个具体、可复现的现象：
  1. Reflector 把"查询到的历史文章"误判成"这一轮创建的"（引用了一篇几小时前已发布的旧文章 id，当作本轮产出）
  2. 一轮因为 Execute 自己生成的 Python 代码里有个未转义引号导致 `SyntaxError`、整批三篇文章写库失败后，**下一轮没有去修那一个字符转义问题，而是把上一轮已经做完的全部调研（5次搜索、多次数据库查询）原样重跑了一遍**，预算耗尽依然没有写库成功

两个现象根子相同：**轮次之间唯一的信息通道又窄又不一致，且被两种矛盾的目的（人类可读摘要 vs 机器可用交接）挤在同一个字段里**。

---

## 一、现状（已用真实数据核实）

### 1. 三层记忆里，问题出在哪一层

```
第一层 Session 记忆     跨 task 的对话历史（ConversationStorage）
第二层 Task 账本        单次 execute()/resume() 内每一轮的记录（TaskStore）  ← 问题在这
第三层 Agent 执行上下文  单轮内部工具调用历史（AgentContext）——每轮清零，设计如此，不动
```

第二层账本本身的数据是**完整、未截断**的：`CapabilityExecutor.recordToolExecution()` 把每次工具调用的完整 `observation` 原样存进 `RoundRecord.executionResult.toolExecutions`，落库后可验证。问题不是"存丢了"，是 **Planner 和 Reflector 对第二层账本的读法太窄、而且两者读的不是同一份东西**：

```java
// Reflector.java —— 读上一轮情况时：
String execText = bus.getTransmit(ContextBusKeys.EXEC_TEXT);
if ((execText == null || execText.isBlank()) && state.getLastToolResult() != null) {
    execText = state.getLastToolResult();   // 失败路径几乎总是走到这里
}
```

`EXEC_TEXT` 只在 `CapabilityExecutor` 的**成功分支**才更新（`bus.putTransmit(EXEC_TEXT, executionText)`），失败/超预算分支完全不碰它——于是 Reflector 判断失败轮次时，`execText` 要么是上一个成功轮次遗留的旧值，要么直接 fallback 到 `state.getLastToolResult()`，也就是**这一轮最后一次工具调用的原始结果**（一条 SQL 查询、一次搜索……随便什么），不是整轮摘要。真实案例：round 1 最后一次工具调用恰好是 `SELECT ... FROM article` 查到了一篇几小时前发布的旧文章，Reflector 因此把它当成"这轮的产出"。

```java
// TaskPlanner.java —— 给下一轮规划时：
List<RoundRecord> rounds = state.getRounds();
if (rounds.size() > 1) {
    RoundRecord prev = rounds.get(rounds.size() - 2);   // 只看紧邻的上一轮
    if (prev.getExecutionResult() != null && prev.getExecutionResult().getFinalText() != null) {
        humanSb.append("\n\nPrevious round summary:\n").append(prev.getExecutionResult().getFinalText());
    }
}
```

Planner 读的是 `finalText`——跟 Reflector 读的**不是同一份数据**，而且只看紧邻的一轮，三轮以上的任务前面的进展全部看不到。

### 2. finalText 现在同时承担两个互相矛盾的职责

- 成功路径：Execute 自己（当前是便宜档 `deepseek-v4-flash`）写的"最终回答"，prompt 里明确要求"Be concise"——**它是照着"给用户一个简洁答案"这个目的写的，不是照着"给下一轮 Planner 交接技术细节"写的**
- 失败/超预算路径：`AgentAbortException.getMessage()`（j-langchain `McpAgentExecutor.summarizeStepsForDiagnostic()` 生成）+ `state.getLastToolResult()` 拼出来的诊断文本，其中每条工具调用的 observation 被硬截断到 **120 字符**（`truncateForDiagnostic(obs, 120)`）——Python 报错的关键信息（`SyntaxError: ...`）几乎总是在 traceback 最后一行，120 字符经常连头几行定位信息都装不满，真正有用的那句话被截没了

真实案例：round 2 因为 heredoc 脚本里一处未转义引号整批写库失败，round 3 的 Planner 拿到的"上一轮情况"里，报错信息被砍成 `File "/opt/mini...`——看不出具体是什么错，只能选择"重新来一遍"这种保守策略，而不是"就是那处引号，改一下重试"。

这两个用途（人类可读的简洁总结 / 机器可用的精确诊断）本来就该是两份不同的文本，硬塞进同一个字段，两边都写不好。

### 3. finalText 不保证非空

- `CapabilityExecutor` 兜底 `catch (Exception e)` 分支：`e.getMessage()` 常见是 `null`（很多异常，比如 NPE），直接塞进 `finalText` 会得到字面 `"null"`
- `DefaultResultComposer.compose()`：如果任务所有轮次都没能跑到 `CapabilityExecutor` 就失败了（比如更早阶段直接抛异常），拿到的是 `null`，`CliMain.handleAgentResult` 里 `if (answer != null && !answer.isBlank())` 直接判假——用户看到的是"什么都不打印"，这也是之前"任务不成功也该说明情况"那次讨论的根因之一

---

## 二、设计方向

**不改动"每轮全新上下文"这个大前提**（Execute 每轮 `AgentContext.create()` 重来，是合理的成本控制，08 号文档不碰）。要改的是：Reflector 和 Planner 对第二层账本的读法，从"各自窥探一个窄且不一致的切片"，改成"读同一份、由 Reflector 产出的结构化摘要"。

### 1. `ReflectionDecision` 新增 `roundSummary` 字段

```java
@Data
public class ReflectionDecision {
    private ReflectionAction action;
    private String reason;
    private ReflectionHint hintForNext;
    /** NEW: 面向下一轮 Planner 的结构化交接摘要（完成了什么、失败在哪、还差什么）*/
    private String roundSummary;
}
```

由 Reflector 在**同一次**结构化 JSON 输出里多吐一个字段——Reflector 本来每轮就会真实调用一次 LLM（判 FINISH/CONTINUE/ESCALATE），现在已经是 pro 档模型（07 号文档的结论：误判 FINISH 是单向门，这个判断本身的质量值得花钱），顺带产出交接摘要**不需要额外的 LLM 调用**，还天然由强模型、读**完整**数据来写，不再依赖便宜档 Execute 模型自己的简洁自述。

Reflector 的 prompt 需要能看到这一轮**完整**的 `tool_executions`（工具名 + 参数 + 结果），而不是现在只有 `execText`/`state.getLastToolResult()` 这一条——数据本来就在 `RoundRecord.executionResult.toolExecutions` 里，完整、未截断，只是之前没喂给它。

guard rule 强制 CONTINUE 的路径（零工具调用）不需要额外 LLM 判断，`roundSummary` 直接复用 `decision.reason`（"Guard: N capabilities selected but no tools executed this round"）即可，不用为了这个字段单独绕一次 LLM。

### 2. `finalText` 职责收窄

- **成功路径**：不变，还是 Execute 自己写的简洁最终回答——这个 LLM 调用躲不掉，内容因每次实际完成的工作而异，没法用固定模板替代
- **失败/超预算路径**：改成固定短句，不再依赖诊断截断逻辑：

  ```java
  output.setFinalText("Round " + round + " incomplete: iteration budget ("
          + maxAgentIterations + " steps) exceeded.");
  ```

  原来 `truncateForDiagnostic`/`summarizeStepsForDiagnostic` 那套截断逻辑**不用修**——它已经不再是 Planner/Reflector 的交接通道，`AgentAbortException.getMessage()` 继续原样用于**实时事件日志**（`EventType.EXECUTION_COMPLETED` dispatch，给终端上正在看的人用），这个用途截断到 120 字符完全可以接受，不用动

### 3. `EXEC_TEXT` 无条件更新

`CapabilityExecutor` 每一轮结束后，不管成功还是失败，都把这一轮最终确定的 `finalText` 写进 `EXEC_TEXT`——消除 Reflector 读到"上一个成功轮次遗留的旧值"这个可能性。（Reflector 自己现在改成主要读完整 `tool_executions` 而不是 `EXEC_TEXT`，这一条算是顺手补的一致性修复，不是这次的核心。）

### 4. `TaskPlanner` 从"只看上一轮"改成"拼最近 K 轮的 roundSummary"

```java
List<RoundRecord> rounds = state.getRounds();
int from = Math.max(0, rounds.size() - 1 - RECENT_ROUNDS_WINDOW);  // 不含当前轮
for (int i = from; i < rounds.size() - 1; i++) {
    ReflectionDecision refl = rounds.get(i).getReflection();
    if (refl != null && refl.getRoundSummary() != null) {
        humanSb.append("\n\nRound ").append(i + 1).append(" summary:\n").append(refl.getRoundSummary());
    }
}
```

`RECENT_ROUNDS_WINDOW` 先定一个保守值（比如 3）——因为现在喂给它的是 Reflector 写的**精简**摘要，不是原始 tool_executions，多拼几条不会让 prompt 失控膨胀，这是跟"看完整历史"完全不同量级的开销。

### 5. `DefaultResultComposer` 用最后一轮的 `roundSummary` 兜底

任务耗尽轮数仍未 FINISH（TIMEOUT）时，展示给用户的文本从"最后一轮的 finalText"（现在可能是那句固定短句）改成优先用**最后一轮的 `roundSummary`**（如果有）——用户至少能看到"做到了什么程度、卡在哪"，而不是一句"iteration budget exceeded"就完了。`finalText` 仍作为 `roundSummary` 缺失时的兜底。

### 6. 补两处 null 兜底

- `CapabilityExecutor` 通用 `catch (Exception e)`：`e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)"`
- `DefaultResultComposer.compose()`：所有轮次都没有可用文本时，返回 `"Task ended (" + state.getStatus() + ") after " + rounds.size() + " round(s) without a usable result."`，不返回 `null`

---

## 三、改动范围（预估，实现时可能微调）

| 文件 | 改动 |
|---|---|
| `task/state/reflection/ReflectionDecision.java` | 新增 `roundSummary` 字段 |
| `task/worker/Reflector.java` | prompt 加完整 `tool_executions` 渲染；SYSTEM_PROMPT 要求输出 `roundSummary`；guard 路径复用 `reason` |
| `task/worker/CapabilityExecutor.java` | 失败路径 `finalText` 简化为固定模板；`EXEC_TEXT` 无条件更新；通用异常 null 兜底 |
| `task/worker/TaskPlanner.java` | "Previous round summary" 改成拼最近 K 轮 `roundSummary` |
| `task/DefaultResultComposer.java` | 优先用最后一轮 `roundSummary`；全空兜底文案 |

不涉及 `j-langchain`（`truncateForDiagnostic` 保持原样，用途已经理清楚，不用改）。

---

## 四、还没定的东西

- `RECENT_ROUNDS_WINDOW` 具体取多少——3 是拍脑袋的保守值，需要真实多轮任务跑一跑看 prompt 膨胀情况
- Reflector 的 `roundSummary` 具体长度/格式约束（要不要在 prompt 里明确"控制在 N 字以内，列出：完成的产出 / 失败原因 / 还差什么"三段式），现在只打算给个宽松的自然语言指令，可能不够稳定
- 要不要机械提取"已完成产出"清单（扫 `tool_executions` 里的 `write_file`/`insert-article --commit` 类调用，拼成一行 `Artifacts so far: ...` 附在 `roundSummary` 后面）——这个之前讨论过，不依赖 LLM、成本几乎为零，但这次先用 Reflector 的自然语言摘要验证够不够用，不够再加这层机械提取
- 跟 05 号文档（`taskRoadmap`）的关系——如果以后真做了跨轮次持久化的任务分解对象，`roundSummary` 和 roadmap 的"哪一步完成了"可能有重叠，届时需要理一遍，这次不处理

## 五、验证方式（实现后要做）

- 单元测试：`Example00GettingStartedTest`/`Example01WeatherForecastTest` 全量跑一遍，确认没有回归
- 真实复测：拿一次容易复现"round 2 失败、round 3 重复调研"的真实资讯写作任务，看 round 3 是不是能正确读到 round 2 的 `roundSummary`、跳过已完成的调研、直接修复问题重试
- 确认 `DefaultResultComposer` 的兜底文案在真实 TIMEOUT 场景下确实展示了有意义的内容，不是空白或者原始异常堆栈
