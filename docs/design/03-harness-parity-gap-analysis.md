# Harness 对齐差距梳理（Parity Gap Analysis）

- 状态：**差距梳理已完成，具体每一项怎么做还没设计**——这是一份跟踪/路线图文档，不是实现方案，后续挑一项就展开一节具体设计（或者拆成独立的 04/05... 号文档）
- 涉及仓库：`regnexe-agent`（主要，`marketplace.capability.CapabilityType` 等）、`regnexe-cli`
- 关联文档：`01-marketplace-plugin-design.md`（包结构 + Install/Cache）、`02-capability-naming-and-collision-design.md`（命名与冲突）；`docs/harness/claude-code/`、`docs/harness/codex/`、`docs/harness/deepseek/`、`docs/harness/opencode/`（四家分析原文）；`harness-testbed/cases/001-haiku-skill/`、`cases/002-plugin-lifecycle/`（实测结论，不是纯理论对比）
- 背景：01、02 号文档落地之后，regnexe 在 Install/Cache/Uninstall/Enable-Disable/命名冲突这几块已经追平了大部分之前的差距（尤其是 Codex 那套"内容哈希 + 立即删除"的模型，已经在 `harness-testbed` 上实测对齐）。这份文档是在这个基础上，重新对着四家 Harness 的分析文章过一遍，看现在还差什么、哪些是真要补的差距、哪些是主动砍掉不做的范围。
- 面向读者：下一个要挑一项差距开始做设计/实现的人或 AI

---

## 一、方法论

比较依据是 `docs/harness/` 下四家的分析文章，加上 `harness-testbed` 的实测结论（案例 001 测 Skill 发现/路由、案例 002 测 Plugin 生命周期）——不是单纯读文档做纸面对比，凡是能实测的都实测过。

---

## 二、已经追平的（不是这份文档的重点，简单列一下）

- **Install/Cache/Uninstall**：内容哈希版本化 + 立即删除，跟 Codex 是同一套模型，`harness-testbed` 上装过真实的 `claude-md-management` 插件验证过
- **`enabled.yml` 软开关**：跟 Codex 的 `config.toml` 里 `[plugins."x@y"] enabled` 是同构设计，disable ≠ uninstall 这条 Claude Code 的核心原则也验证过
- **`/skills` 列表 + `/<name>` 直调**：跟 Claude Code 的"显式调用 Skill"、Codex 的渐进式加载在体验上已经接近

---

## 三、差距分类

### 1. 组件类型覆盖——最大的结构性差距

| Harness | 组件类型 |
|---|---|
| Claude Code | Skill、**Command**、SubAgent、**Hook**、**MCP Server**、**LSP Server**（6 种，见 `docs/harness/claude-code/1.Directory and Configuration Structure.md`） |
| Codex | Skill/Workflow、Tool、**App/Connector 映射层**、`.mcp.json`（见 `docs/harness/codex/4.Plugin, Marketplace, and the Universal Plugin Directory.md`） |
| regnexe | `marketplace.capability.CapabilityType` 只有 **SKILL / SUB_AGENT / MCP_TOOL**（3 种） |

缺的三类：

- **Command**——能展开成 prompt 的斜杠命令。Claude Code 里 Command 不是独立进程、调用后可以继续触发其他能力（见该文档"六、Command 如何展开为 Prompt"）
- **Hook**——生命周期事件钩子，比 Skill 更确定也更危险，可能在事件发生时直接执行 Shell/HTTP（见该文档"八、Hook 如何接入生命周期 Runtime"）
- **LSP Server**

`CapabilityType` 枚举本身留了扩展口子（01 号文档"实现记录"写过"加 Hook 只需要加枚举值、`loader/` 里加对应加载逻辑，不需要动 `Marketplace` 接口"），但目前一个都没做。"MCP Server 作为一个连接/映射层"而不是"每个 tool 单独注册"这种更贴近真实 MCP 协议的建模方式，也还没有。

### 2. 远程分发——明确不做，不是差距

Claude Code 和 Codex 的 marketplace 都能是远程 git 仓库，OpenCode 直接走 npm 公共包管理器。regnexe 现在 `/plugin install` 只认本地路径——这是 01 号文档 §6.1 明确定的范围，主动砍掉的复杂度，不算这份文档要处理的差距，列在这里只是为了对比完整。

### 3. 治理与安全——完全空白

Claude Code 专门有一篇分析文章覆盖插件权限声明、供应链审计（`docs/harness/claude-code/6.Permissions Security and Supply Chain Governance.md` + `Appendix1.Plugin Agent Permission Boundary and Security Design.md`）。regnexe 这块一个字都没写，01 号文档 §5 第 5 条明确列为"更远期话题"。

### 4. Scope 分层——只做了一半

Claude Code 有 Managed（企业级）这一层。regnexe 的 `Scope` 枚举里 `MANAGED`/`LOCAL` 都只是占位值（`Scope.java` 注释写得很清楚：目前没有对应的磁盘位置），只有 `USER`/`PROJECT` 真正落地。

### 5. 可观测性细节

| 差异点 | 现状 |
|---|---|
| Claude Code 用廉价路由模型（haiku）先筛一遍再上主模型 | regnexe 没有，Planner 直接用主模型 |
| Codex 的渐进式加载能被亲眼看到——案例 001 实测观察到它用 `sed` 读 SKILL.md 正文 | regnexe 的渐进式加载是隐式的，没有对应的可观测时刻 |
| OpenCode 有内置的 `opencode stats` 审计命令 | regnexe 没有对应命令 |
| — | `AgentEvent` 不带结构化的 capability 类型字段（讨论过，02 号文档 §5 已记录为这轮明确不做） |

### 6. 命名与冲突治理——大部分已经在 02 号文档里解决，剩下的是主动不做的部分

跟四家比，regnexe 现在还没有的：远程 marketplace 场景才需要的 `known_marketplaces.json` 等价物（因为不支持远程，暂时用不上）、注册表按 marketplace 命名空间隔离、装的时候主动拒绝冲突而不是只警告——这三项 02 号文档 §5 都已经过了一遍、明确这轮不做，不重复展开，详见该文档。

---

## 四、优先级建议

- **Command 和 Hook**——差距最大也最容易感知，四家里三家都有，regnexe 一个没有，建议优先
- **治理与安全**——最容易被忽略，但真要给别人用（哪怕只是给团队内部用）会最先被问到，建议其次
- **远程分发、企业级 Scope**——偏"规模化以后才需要"，不着急
- **可观测性细节**——锦上添花，优先级最低，且 §3.5 里已经有一半是明确决定不做的

---

## 五、后续怎么处理

这份文档只做梳理，不做设计。约定：挑中某一项要开始做的时候，要么在这份文档里补一节具体设计（类似 01/02 号文档"这轮拍板"的写法），要么单独拆一份新的 `04-xxx-design.md`——看这一项本身的体量决定，体量大（比如 Command/Hook 这种要动 `CapabilityType`、多个 loader、`Marketplace` 接口的）建议拆独立文档，体量小的可以直接在这份文档里追加。
