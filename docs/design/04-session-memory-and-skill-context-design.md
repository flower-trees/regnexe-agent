# Session/记忆体系 与 Skill 执行上下文 设计

- 状态：**二、三、四、五节均已实现并验证**（五节经历了一轮"实现→真实验证发现缺口→修正设计→重新实现→再验证通过"，过程见下方"实现记录"）
- 涉及仓库：`regnexe-agent`（`RegnexeAgent`、`CapabilityExecutor`、`RegnexeAgentBuilder`）、`j-langchain`（`history.memory` 包新增一个策略类）、`regnexe-cli`（`CliMain` 的 `--continue`、`buildAgent()`）
- 关联文档：`03-harness-parity-gap-analysis.md`（那份文档梳理的是 marketplace/plugin 那条线的差距，不包含长期记忆——session/记忆这条线是单独一轮讨论出来的，跟 03 号平级，不是它的子项）
- 背景：这份文档汇总了两轮独立讨论——一轮是重新对着四家 Harness 梳理 session/记忆现状之后发现的三个缺口（长期记忆、压缩策略、session 续接方式），另一轮是顺着"Skill 和 SubAgent 现在共用同一套隔离机制"这个问题，查代码 + 查 Claude Code 原文，定下来的 Skill 执行上下文改造方案。四块内容分别独立，但都属于"session/执行上下文"这个大主题，放一份文档里。
- 面向读者：下一个要实现这四项改动的人或 AI

## 实现记录

| 节 | 内容 | 状态 | 验证方式 |
|---|---|---|---|
| 二 | 长期记忆 REX.md | ✅ 已实现 | `harness-testbed` 真实项目级+用户级 REX.md，确认项目级内容优先且都被注入 |
| 三 | `PeriodicConversationSummaryMemory` | ✅ 已实现 | 单元测试对比 50 轮历史下滚动版 vs 周期版摘要 LLM 调用次数：30 次 vs 2 次 |
| 四 | `--continue`/`-c` | ✅ 已实现 | `harness-testbed` 先用 `--session` 建历史，再单独 `--continue` 启动，确认自动接上同一 session 历史（`favorite number = 42` 正确召回），且未触发 Task resume |
| 五 | Skill 共享执行上下文 | ✅ 已实现并验证（经过一轮设计修正） | 见下方详述 |

**五节详情——第一轮实现**：`CapabilityExecutor.resolveCapabilities()` 的 `SKILL` 分支改为把 `SkillConfig` 的 `systemPrompt`/`references`/`scripts` 合并进本轮共享的 `executorBuilder`，不再调用 `Skill.from(...).build()` 起独立执行器；`allowedTools` 声明了的分支沿用旧的"按名字解析进 mcpTools"逻辑不变，没声明的分支只共享这一轮 Planner 恰好选中的工具。`j-langchain` 单元测试 + `regnexe-agent` 全量单测（`RegnexeAgentExecuteSkillTest` 等）通过，确认 `executeSkill()`（`/skill名` 直调）那条独立执行器路径完全不受影响。

真实场景验证分两部分：
- **声明了 `allowed-tools` / 有明确工具依赖的 Skill**（`Example02ContractAnalyzerTest`，`contract_analyzer`）：`Execute Input` 日志确认 `systemPrompt` 被正确拼进共享 prompt（带 `### Skill: contract_analyzer` 小标题），工具调用在同一个 executor 循环里正常发生——符合预期。
- **未声明 `allowed-tools` 的真实 Claude Code Skill**（`claude-md-improver`，来自官方 marketplace，`SKILL.md` frontmatter 用的是 `tools:` 而非 j-langchain 的 `allowed-tools:`，所以 `SkillConfig.allowedTools` 确实为空）：在 `harness-testbed` 跑了两次真实端到端（`harness-testbed-skillctx-v1`/`v2`），**两次 Planner 都只选中了 `claude-md-management.claude-md-improver` 这一个能力，没有同时选中 `bash`/`read_file` 等基础工具**——于是这一轮 `mcpTools` 是空的，模型反复尝试调用一个并不存在于这轮工具列表里的 `bash`（多种拼写，均未被识别为合法 tool_call，原样作为文本返回），连续 6 轮打转后放弃，给出"项目里没有 CLAUDE.md"的错误结论（实际存在）。`task_execution_states` 表逐轮记录证实了这一过程。

**根因排查**：一开始以为是"Planner 两段式渐进披露看不到 Skill 内部工具需求"这个规则本身的副作用，但去查了 Claude Code 官方文档原文（用户提出"Claude/Codex 的 Skill 是不是本来就没有 allowed-tools，SubAgent 才有？"这个问题后重新确认）才发现是**设计本身理解错了**——`allowed-tools` 在 Claude Code 里的真实语义是"当前 Turn 免确认的预批准清单"，不是访问范围声明；Skill 不管声不声明 `allowed-tools`，本来就该拿到主 Agent 的全部工具，因为它跟主 Agent 共享同一个上下文。原设计把 SubAgent 那种"访问边界"语义错套到了 Skill 头上，才会出现"没声明 = 没工具"这个真实倒退。

**设计修正 + 第二轮实现**：§5.2 表格已改为"Skill 无条件拿到 `baseToolNames`（`RegnexeAgentBuilder.withTool()` 注册的基础工具集）+ 按需额外解析自己声明的 `allowedTools`"；SubAgent 侧核查确认 `SubAgent.collectTools()`（`ownTools + inheritedTools`，`j-langchain`）本来就是真实的访问边界，没有改。代码改动：新增 `ContextBusKeys.BASE_TOOL_NAMES`，`RegnexeAgentBuilder.withTool()` 记录注册的工具名，`RegnexeAgent` 通过 transmitMap 下发，`CapabilityExecutor` 的 `SKILL` 分支无条件把 `baseToolNames` 并入这一轮要解析进 `mcpTools` 的集合。

**修正后重新验证**：`regnexe-agent` 编译通过、快速单测（含 `RegnexeAgentExecuteSkillTest`）全部通过；`harness-testbed` 用同一个 `claude-md-improver` 场景重新跑（`harness-testbed-skillctx-v3`），这次 Planner 依然只选中了 Skill 本身，但 `bash`/`list_files`/`read_file` 已经无条件可用——模型正确用 `bash`/`list_files`/`read_file` 找到并读取了真实的 `CLAUDE.md`、`README.md`、`fixtures.md`、`PARITY-NOTES.md`，交叉引用后给出准确、具体的审计报告（指出 `CLAUDE.md` 内容与 `README.md` 描述的真实约定不符），并且按 SKILL.md 自身工作流的要求，在给出报告后停下来等待用户批准、没有直接写入——确认设计修正后行为符合预期。确认门槛（`allowedTools` 声明的工具跳过确认）这半个语义（Claude Code 里 `allowed-tools` 的第二层作用）明确不在这轮做，见六节。

---

---

## 一、现状回顾（不是这份文档的结论，是讨论的起点）

regnexe 现在的记忆模型是三层 + CLI 侧再加一层：

```
（CLI 层，regnexe-cli 独有）
Session 绑定层    session 名字 → 工作目录 + sessionId（SQLite ~/.rex/rex.db 的 sessions 表）

（agent 库层）
第一层 Session 记忆    跨 task 的对话历史（ConversationStorage）
第二层 Task 账本       单次 execute()/resume() 内每一轮的记录（TaskStore）
第三层 Agent 执行上下文 单轮内部工具调用历史（AgentContext）
```

跟四家对比下来，确认要处理的缺口：没有 CLAUDE.md/AGENTS.md 那样的"项目级长期记忆"；`ConversationSummaryBufferMemory` 的压缩策略是"滚动式，超过阈值后每一轮都压一条"，不是 Claude Code `/compact` 那种"攒够了批量压一次"；`--resume` 现在是 Task 级续跑（必须记住 session 名字），没有 Claude Code `--continue` 那种"不用记名字，自动接上上一次"的能力。

另外顺着"Skill 该不该跟 SubAgent 一样被隔离"这个问题查证：`Skill.invoke()`（`j-langchain`）会建一个完全独立的 `McpAgentExecutor`，只接收调用时传的一个 `input` 字符串，看不到主线程已经聊过什么——查了 Claude Code 原文（`docs/harness/claude-code/4.How to load and execute plugins.md`）：

> Skill 调用……更接近：Skill Markdown + 用户参数 → Prompt Expansion → **进入当前 Agent 上下文**

而 SubAgent 那节明确写的是相反的设计目的（"Context Quarantine"）。这是两种不同的目的，regnexe 现在用同一套机制处理，是本文档要修的第二个问题。

---

## 二、长期记忆（对应 CLAUDE.md / AGENTS.md）

### 1. 磁盘约定

复用已经在 `.rex` 里验证过的双 scope 模式，不新发明一套：

```
~/.rex/REX.md              project/.rex/REX.md
```

项目级优先（跟 `resolveSkillDirectories`/`resolveMarketplacePluginDirectories`/`enabled.yml` 的 `ScopeResolver` 合并方向一致：项目覆盖用户）——但这里"覆盖"的语义跟 enabled.yml 不同，长期记忆是**拼接**不是**替换**：两层都存在时，先拼用户层、再拼项目层（项目层排在后面，离模型的注意力更近，这也是 Claude Code 自己"层级越具体越靠后"的常见做法）。

### 2. 注入时机

跟 CLAUDE.md/AGENTS.md 一样，**不是 session 记忆的一部分，是每次 `buildAgent()` 都会读、都会注入的项目上下文**——不依赖 sessionId，换个 session 名字、清空会话历史，这份文件的内容照样在。具体接入点：`CliMain.buildAgent()` 里读这两个文件的内容，拼进 system prompt（跟 `AgentContext`/`ConversationStorage` 完全独立，不走那三层记忆的任何一层）。

### 3. 不在这轮做的

- Claude Code 的 `#` 快捷键（对话中随手把学到的东西记进 CLAUDE.md）——这是一个交互层的功能，依赖能识别"用户此刻想记点什么"，这轮先把文件读取/注入这条主线做完，快捷写入留到 CLI 交互那一轮
- 企业级第三层（`docs/harness/codex/2.AGENTS.md...` 提到的 `requirements.toml` 那种强制约束）——03 号文档已经把"治理"列为独立、更远期的差距，不在这里重复设计

---

## 三、会话压缩：新增一个周期批量压缩策略类

### 1. 现状（已经在跑，不是新增能力，是策略问题）

`RegnexeAgent.storeSessionRound()` 在 `sessionStorage`+`defaultModel` 都配置时，默认用 `ConversationSummaryBufferMemory`（`j-langchain`，`history.memory.summarybuffer` 包）。它的触发方式是**滚动式**：`buffer.size() > maxSize` 时，把最老的**一条**压进 summary，这个动作在缓冲区打满之后**每一轮都会重复触发**——一个 50 轮的 session，第 11~50 轮之间会产生 40 次独立的摘要 LLM 调用。

### 2. 新增策略类：`PeriodicConversationSummaryMemory`

跟 `ConversationSummaryBufferMemory` 平级，放在 `j-langchain` 的 `org.salt.jlangchain.core.history.memory.periodic` 包下（新增子包，不改现有的 `summarybuffer`/`summary` 两个包，两种策略并存，调用方选哪个）：

```
触发条件：未压缩的原始轮次数 >= periodSize（例如 20）
触发动作：一次性把这 periodSize 条原始轮次，合并进（已有的）summary，
         生成新的 summary，原始轮次清空归零
下一次触发：再攒够 periodSize 条才会触发下一次
```

跟滚动式的关键区别：**摘要 LLM 调用次数从"缓冲区打满后每轮一次"变成"每 N 轮一次"**——50 轮的 session、`periodSize=20`，只会触发 2 次摘要调用，不是 40 次。跟 `ConversationSummaryBufferMemoryStorer` 一样实现 `ConversationMemoryStorerBase`，复用 `ConversationStorage.replace()` 这个已有的"整体替换"接口，不需要改 `ConversationStorage` 接口本身。

### 3. 接入方式

`RegnexeAgentBuilder` 已有的 `withSessionMemory(ConversationMemory memory)` 这个口子已经能接任意 `ConversationMemory` 实现——不需要新增 builder 方法，`regnexe-cli` 想换成周期策略，直接 `new PeriodicConversationSummaryMemory(...)` 传进去就行。默认策略（`sessionStorage`+`defaultModel` 都配置、没有显式传 `sessionMemory` 时）从 `ConversationSummaryBufferMemory` 换成 `PeriodicConversationSummaryMemory`——这一步要改 `RegnexeAgent.storeSessionRound()` 里那段兜底逻辑。

`periodSize` 默认值定为 **20**（对应 `RexConfig.AgentConfig` 新增一个配置项，比如 `sessionCompactPeriod`，默认 20，跟现有 `sessionBufferSize` 平级）。

---

## 四、`--continue`：跟 Claude Code 对齐

### 1. 现状

`CliMain` 现在只有 `--resume <name>`——**必须**给一个 session 名字，行为是"切到这个 session、尝试续跑它最后一个被暂停的任务"（Task 账本，第二层记忆），不是"接着聊上一次的对话"。

### 2. 新增 `--continue`（简写 `-c`）

不需要参数，语义是"不管叫什么名字，直接接上最近一次用过的 session"：

```sql
SELECT * FROM sessions ORDER BY updated_at DESC LIMIT 1
```

取回来的 `SessionRow` 直接当成这次启动的 `SessionContext`（工作目录、sessionId 都沿用），**不触发** `resume()`（不去找有没有暂停的任务）——单纯是接上对话历史（第一层记忆），这是跟 `--resume` 语义上的核心区别：

| | `--resume <name>` | `--continue` |
|---|---|---|
| 要不要记名字 | 要 | 不要 |
| 接的是哪一层记忆 | 第二层：Task 账本（尝试续跑暂停的任务） | 第一层：Session 记忆（接上对话历史） |
| 找不到东西时怎么办 | 报错（没有可恢复的暂停任务） | 找不到任何历史 session 就当成一次全新的启动 |

两个命令**都保留**，不是互相替代——`--resume` 现在做的"精确续跑一个中断的任务"这个能力，四家里没有对应物（案例 001/002 讨论过，pause/resume 这套协作式中断机制比"重开一个 session 接着聊"细粒度得多），不能因为对齐 Claude Code 就丢掉。

---

## 五、Skill 执行上下文：中成本方案

### 1. 改动范围

只改 **Planner 选中 Skill、走 `CapabilityExecutor.resolveCapabilities()`** 这条路径。`RegnexeAgent.executeSkill()`（`/skill名` 直调，绕过 Search/Plan）保持不变，继续用 `Skill.java` 现在的独立执行器——那条路径本来就是"单独跑一个 Skill"的场景，隔离没有问题。

不改 `j-langchain` 的 `Skill.java`。`CapabilityExecutor` 处理 `case SKILL` 时，不再调用 `Skill.from(...).build()` 走独立执行器这条路，改成直接把 `SkillConfig` 的 `systemPrompt`/`scripts`/`allowedTools` 合并进 `CapabilityExecutor` 自己那一轮正在构建的 `executorBuilder`——跟主线程共用同一个 `McpAgentExecutor`、同一份消息历史、同一个工具列表。

### 2. 工具访问策略（已修正：Skill 无条件共享基础工具，`allowedTools` 不是访问边界）

**这一节最初的设计有错，是真实验证之后才发现并改过来的**——原始版本按"`allowed-tools` 声明了就按名字解析，没声明就看这一轮 Planner 恰好选了什么"来划分，把 SubAgent 那种"访问范围声明"的语义错套到了 Skill 头上。查了 Claude Code 官方文档原文（`docs/harness/claude-code/6.Permissions Security and Supply Chain Governance.md`）才确认：

> `allowed-tools` 的作用不是新增底层工具，而是让指定工具在 Skill 被调用的当前 Turn 中无需重复请求用户批准……只在当前调用 Turn 生效，不自动变成永久授权。

也就是说 Claude Code 的 Skill 本来就跑在主 Agent 里、共享同一个上下文，天然拿到主 Agent 的**全部**工具——`allowed-tools` 完全不影响"能不能用某个工具"，只影响"用这个工具时要不要弹确认"。这跟 SubAgent 的 `tools`/`disallowedTools`（真实的访问边界，Context Quarantine 的一部分）是两种不同性质的字段，不能混为一谈。

修正后的规则：

| 场景 | 处理方式 |
|---|---|
| Skill 被选中（不管有没有声明 `allowed-tools`） | **无条件**拿到 `baseToolNames`——`RegnexeAgentBuilder.withTool(...)` 注册的那组基础工具（regnexe-cli 里是 `FileTools`/`BashTool` 那 6 个），这是 regnexe 对应 Claude Code"主 Agent 内置工具永远都在"这条不变量的实现。不依赖这一轮 Planner 是否恰好也选中了这些工具 |
| Skill 声明了 `allowedTools` | 在拿到 `baseToolNames` 之外，额外按名字从 marketplace 解析出这些具体的插件专属工具（比如 `contract_analyzer` 用到的 `analyze_clause`），加进 mcpTools——这解决的是"这个 Skill 需要一个不在基础工具集里的特定能力"这个真实问题，跟原来的机制一样，只是不再是"访问权限"的语义，纯粹是"确保这个 Skill 需要的东西这一轮一定在" |
| "跳过确认"（`allowed-tools` 在 Claude Code 里的真实作用） | **这轮不做**——regnexe 现在 `FileTools`/`BashTool` 的 `pauseAction` 逻辑没有"这次调用跳过确认"这个概念，要支持就要给确认层加一个新维度，属于独立的改动，留到后面 |

`claudeCompatMode`/`SkillWorkspaceTools` 这套代码**不删**，`executeSkill()` 那条独立执行器路径继续用它——只是 Planner 驱动这条路径不再用了。

**SubAgent 侧核查（用户要求）**：`SubAgent.collectTools()`（j-langchain）目前是 `ownTools + inheritedTools`，`inheritedTools` 只由 `McpAgentExecutor.build()` 按 `allowedTools` 过滤后通过 `injectParentTools()` 注入——没有任何隐式的"额外工具"来源，这跟 Claude Code 原文里 Plugin Agent 的 `tools`/`disallowedTools`（`3.Real Plugin and Skill.md` 第 11 节，真实控制这个 Agent 能访问哪些工具）语义是一致的、真正的访问边界。**代码已经是对的，这次没有改 SubAgent 任何逻辑**。

### 3. 多 Skill 同时被选中：先简单拼接，复杂编排推到后续 plan

查了 Claude Code 原文，它的 Skill 本来就是"展开成 prompt、进入当前上下文"，没有"两个独立执行体"这回事，"同时加载多个 Skill"对它来说就是同一个 prompt 里出现了两段指令，怎么协调交给模型自己推理，系统层面没有合并逻辑。

这轮按同样的思路做：多个 Skill 同一轮被选中时，各自的 `systemPrompt` 依次拼进这一轮的 system prompt（各自带小标题分隔，方便调试溯源），工具列表简单合并去重。**更复杂的编排优化（比如显式排序、互相感知）这次不做，留给后续排期单独讨论**——这是 Q2 明确说要往后放的部分。

### 4. 工具生命周期：每轮从零重建，不需要显式移除

`CapabilityExecutor.process()` 本来就是每一轮从头重建 `mcpTools`/`skills`/`subAgents`（局部变量，内容完全由这一轮的 `selectedCapIds` 决定）。Skill 自己的工具塞进这个列表之后沿用同一条规则：

```
第 N 轮：Skill A 被选中 → A 的 tools/scripts 被塞进这一轮的 mcpTools
第 N+1 轮：Planner 这次没选 A → 这一轮的 mcpTools 从零重建，A 的工具不在里面
                                  （不是"被移除"，是"这次压根没被加"）
```

轮内（一次 `executor.invoke()` 调用，内部可能有多轮 function-calling 迭代）工具是持续可用的，不是"调用一次就没"——这跟 Search→Plan→**Execute**→Reflect 里 Execute 阶段本来允许多轮工具调用的规则一致，不是新增的例外。

### 5. 渐进式加载：不受影响，不用重新设计

`CapabilitySearcher` 只吐 name/description 给 Planner 这条纪律不变；`SkillConfig`（`systemPrompt`+`tools`/`scripts`）只在 Skill 真的被选中的那一轮才会被 `CapabilityExecutor` 解析——这个两段式披露机制这次没有改，Skill 的工具跟着 `systemPrompt` 在同一时刻（选中的那一轮）一起进入上下文，不会因为改成共享执行器就提前全量注册。

---

## 六、不在这轮做的

| 项目 | 理由 |
|---|---|
| Claude Code 式 `#` 快捷记忆写入 | 第二节已说明，这轮先做长期记忆文件的读取/注入主线 |
| 企业级/更强约束的长期记忆层 | 03 号文档已经把治理列为独立、更远期的差距 |
| 多 Skill 编排优化（显式排序、Skill 间感知） | Q2 明确说这轮先不做，等后续 plan |
| 压缩策略的更细粒度控制（比如按 token 数而不是按轮数触发） | 这轮先解决"滚动 vs 周期"这个大方向问题，更细的策略留给用出问题以后再调 |
| `allowed-tools` 的"当前 Turn 跳过确认"语义 | Claude Code 里 `allowed-tools` 真正的作用（不是访问范围，是同一 Turn 内的预批准/免确认）——这轮只解决了访问范围那一半（Skill 无条件共享基础工具），跳过确认需要给 `FileTools`/`BashTool` 的 `pauseAction` 加一个新维度，是独立的改动，留到后面 |

---

## 七、验证方式

- **长期记忆**：`harness-testbed` 里放一个真实的 `REX.md`（项目级 + 用户级各一份，内容不同），跑一个不带任何背景信息的 prompt，确认模型的回答体现了 `REX.md` 里的内容，且项目级内容优先于用户级
- **压缩策略**：单元测试对比 `ConversationSummaryBufferMemory` 和 `PeriodicConversationSummaryMemory` 在同样一段 50 轮历史上触发的摘要 LLM 调用次数，确认周期版本明显更少
- **`--continue`**：`harness-testbed` 上先用 `--session` 建一个有对话历史的 session，不带 `--session` 只用 `--continue` 重新启动，确认接上的是刚才那个 session 的历史，且不触发 Task resume 逻辑
- **Skill 共享上下文**：用真实的 `claude-md-improver`（案例 002 用过的那个）重新跑一遍，确认它能用共享工具正确探索、找到并读取真实文件（已验证：`harness-testbed-skillctx-v3`，`bash`/`list_files`/`read_file` 找到并读取了 `CLAUDE.md`/`README.md`/`fixtures.md`/`PARITY-NOTES.md`，给出了准确报告）。写入类操作会不会弹确认这一条，这次实测中 Skill 按自己 SKILL.md 的工作流在报告后停下等待用户批准、没有实际发起写入，所以没有被直接触发——但由构造保证成立：Skill 现在用的是跟主线程完全相同的 `Tool` 对象（同一个 `FileTools.writeFile`/`BashTool.bash` 实例），而不是隔离沙盒里重新构造的等价物，所以 `pauseAction` 确认逻辑是同一份代码路径，不存在"Skill 走的是另一份没有确认逻辑的写入实现"这种可能——逻辑上不需要单独验证，但如果要拿到一次真实的确认框截图，需要一个会真正触发写入的 case（比如 `claude-md-improver` 拿到用户批准后的第二轮，或者换一个开局就会写文件的 Skill）
