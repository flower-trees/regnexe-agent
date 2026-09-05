# 上下文/记忆压缩设计：从"硬截断"到"分级压缩"

> **后续进展**：① 部分点名的硬截断（`Reflector.capText()`/`ExecutionRecordFormatter`）已经整个移除，不是改成更聪明的截断——`ExecutionRecordFormatter` 类已删除，`resume` 整条链路也一并删除（见 CLI `--resume`/`--force-resume`/`--continue` 移除记录）。单次工具结果太大的问题由 10 号文档（tmp 文件 + 指针）解决；"轮次太多"（Plan B）由 11 号文档（`toolExecutions` 拍平成任务级共享列表 + 批量压缩）解决，取代了本文档②③部分设想的 `roundsSummary`/`roundsSummaryThroughRound` 两个字段方案。本文档保留作为问题排查过程的记录。

- 状态：**讨论已收敛，方案见 10/11 号文档**
- 涉及仓库：`regnexe-agent`（`ExecutionRecordFormatter`/`Reflector`/`TaskPlanner`/`RegnexeAgent`）、`regnexe-cli`（`BashTool`/`FileTools`/`McpTools`）、`j-langchain`（`McpAgentExecutor`）
- 关联文档：延续 08 号文档（`finalText`/`roundSummary` 拆分）——08 号解决的是"轮次交接读哪个字段"，本文档解决"读到的内容本身太大/被砍得太狠"
- 背景：`salt-robot-skills` 资讯写作任务真实跑到第 9 轮时，Planner 的 prompt 撑爆了 harness-punchbag-pro 的上下文上限（`Total tokens ... exceed max message tokens`），排查过程中发现问题不止一处——现有代码里有好几处独立的字符截断逻辑，且大多是"从头硬切"，跟 08 号文档当初要修的 120 字符截断是同一类毛病，只是发生在不同位置。

---

## 〇、全量清点（讨论范围，逐块过）

按"会不会喂给模型"分四类：

### ① 会喂给模型、且是头部硬截断 —— **本次先看这块**

| 位置 | 上限 | 状态 |
|---|---|---|
| `ExecutionRecordFormatter.capText()` | arguments 200 / observation 800 | 草稿，未上线 |
| `Reflector.capText()` | arguments 200 / observation 800 | **已上线**（08 号重设计的一部分） |
| `McpAgentExecutor.truncateForDiagnostic()`（j-langchain） | arguments 80 / observation 120 | 一直存在，当初逼出 08 号文档的那个截断，现在只影响 `MAX_STEPS` 中止时的诊断文本 |
| `McpTools.java` MCP 工具描述截断 | 220 字符 | 进系统提示词里的工具列表 |

### ② 已经在用"LLM 压缩，失败才退化成截断"（现成可参考的模式）

| 位置 | 做法 |
|---|---|
| `TextCompressor.compress()` | 通用工具类，超限先尝试 LLM 摘要，失败才退化成头部硬切 |
| `PeriodicConversationSummaryMemoryStorer`（Layer 1 批量压缩） | 旧摘要 + 新一批内容 → 一次 LLM 调用合并成新摘要，Plan B（轮次级压缩）打算照抄这个模式 |

### ③ 工具自身产出层面的上限（在到达 ①②之前已经切过一刀）

| 位置 | 上限 |
|---|---|
| `BashTool.MAX_OUTPUT_CHARS` | 10,000 字符 |
| `FileTools.MAX_READ_CHARS` | 8,000 字符 |

### ④ 纯终端展示，不影响模型看到什么（低优先级）

- `TerminalCliRenderer.MAX_TOOL_RESULT_CHARS`（300 字符）
- `CliMain.truncate()` 的几处展示用途（技能描述 60 字符、MCP 工具列表展示 2000 字符）

---

## 一、① 详细分析：会喂给模型的硬截断链路

### 1. 现状：两层硬截断叠在一起

一次 `bash` 调用的 observation，实际要经过两层裁剪才到 Reflector/Planner 手上：

```
BashTool 执行结果
  → 头部硬切到 10,000 字符（BashTool.MAX_OUTPUT_CHARS）
  → 存进 RoundRecord.executionResult.toolExecutions[i].observation（这一步是完整存的，不再切）
  → Reflector 读取时，头部硬切到 800 字符（Reflector.capText）
  → ExecutionRecordFormatter 读取时（resume 场景），头部硬切到 800 字符（草稿中）
```

第二层（800 字符）比第一层（10,000 字符）小得多，所以实际生效、决定模型看到什么的，几乎总是第二层——第一层的 10,000 字符上限只在防止把明显失控的输出（比如死循环打日志）整个存进 DB 时才真正起作用。

### 2. 这一刀切在哪：真实数据

`Reflector.capText()` 不是"超大异常情况才触发的保险丝"，而是**对每一条 tool_executions 无差别应用**——只要单条 observation 超过 800 字符就会被切，这在资讯写作这类任务里是常态，不是例外：

- `python3 lib/db.py insert-article payloads/articleN.json` 这类命令，标准输出经常会把写入的 payload 内容回显一遍（用于确认），一篇 1500-2500 中文字符的正文 + HTML 标签，轻松超过 800 字符。
- Python 报错的 traceback，关键信息（`SyntaxError: ...`、具体是哪一行、哪个字符）几乎总是在**最后几行**——这正是 08 号文档最初发现问题的原始案例。头部硬切在这种情况下把最有用的部分丢了，跟没截一样，甚至更糟（截出来的内容像是完整的，容易让模型误判"这就是全部信息"）。

结论：这条链路目前是**活跃生效、天天在切**的，不是理论风险。

### 3. `McpTools` 的 220 字符工具描述截断——单独一类风险

跟上面两个不是同一类问题：上面两个切的是"执行结果"，这个切的是**工具能力说明本身**，进的是系统提示词。如果某个工具的完整用法/参数说明超过 220 字符被从头切掉，模型可能根本不知道这个工具还有某个可选参数、某种正确用法，这是"模型都不知道该怎么用工具"级别的信息丢失，跟"事后回顾执行记录时看不全"性质不一样，值得单独评估要不要动，但目前没有实测证据证明它已经造成过具体问题（跟 Reflector 那条不一样，那条是有真实案例的）。

### 4. 改进方向：三个选项，不是非此即彼

**选项 A：头部+尾部各留一段，中间挖空**（上次提过的思路）
- 零额外 LLM 调用，改动最小。
- 对"报错信息在结尾"这类场景直接解决（08 号文档的原始诉求）。
- 对"大段正文内容被截"这类场景效果有限——掐头去尾也救不了一篇完整文章，模型看到的还是残缺内容，只是残缺得更均匀。

**选项 B：接入 `TextCompressor`，超限先 LLM 摘要**
- 复用现成、已验证的工具类，不用发明新机制。
- 但要注意粒度：如果按"每一条 tool_execution 单独压缩"，像第 1 轮 58 次调用这种量级，可能触发几十次额外 LLM 调用，本末倒置（本来是为了省 token/省钱，结果因为压缩本身的调用把成本打上去了）。
- 得按"整条 observation 是否值得单独理解"来判断，不是无脑全上。

**选项 C：不在这一层死磕，靠 Plan B（轮次级压缩）兜底**
- 论点：像"一篇完整文章正文"这种大段内容，Reflector/Planner 真正需要知道的是"这轮成功写了篇标题为 X 的文章"，不是文章原文——这种语义级压缩，本来就该是 Plan B 里"轮次摘要"该干的事，不该指望单条 observation 级别的截断/压缩去解决。
- 那这一层（①）只需要负责"别把明显有用的信息（比如报错的最后一行）无脑切掉"，用选项 A 的头尾截断就够了，不需要再上 LLM 压缩，避免跟 Plan B 的轮次级压缩重复花钱压缩同一批内容。

### 5. 目前倾向

选项 A（头尾截断）解决"结构性丢信息"（报错场景），选项 C 的逻辑成立的话，"大段正文被切"这个问题不需要在 ① 这层单独解决，等 Plan B 落地后由轮次摘要自然覆盖。`McpTools` 的 220 字符这个单独拎出来，因为它是另一个风险维度（工具说明缺失，不是执行记录缺失），要不要动、怎么动待定。

---

## 二、暂缓讨论的部分（②③④）

②（`TextCompressor`/`PeriodicConversationSummaryMemoryStorer`）已经在用、工作正常，本文档只是记录现状，暂不涉及改动。

③（`BashTool`/`FileTools` 自身输出上限）跟①的关系已经说明（两层截断，第二层更小、实际生效），单独要不要调整 10,000/8,000 这两个数字，待①敲定后再看是否还有必要。

④纯展示层，不影响模型上下文，暂不处理。

---

## 三、还没定的事

- ① 的最终方案：选 A、选 B、还是 A+C 组合，等讨论。
- `McpTools` 220 字符要不要动、怎么动。
- Plan B（轮次级压缩）的具体设计仍在 08 号文档之外单独讨论中，本文档只是引用其结论（"大段成功内容该由轮次摘要兜底，不该指望单条截断"），不在本文档展开。
