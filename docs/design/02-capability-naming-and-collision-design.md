# 能力命名与身份唯一性设计（Capability Naming & Collision）

- 状态：**第四节 1-3 已实现并在 `harness-testbed` 上用真实场景验证过**——见文末"实现记录"
- 涉及仓库：`regnexe-agent`（`marketplace.loader`/`marketplace.capability`/`marketplace.SimpleMarketplace`）、`regnexe-cli`（`CliMain` 的 `/plugin` 命令、`resolveSkillDirectories`）
- 关联文档：`docs/design/01-marketplace-plugin-design.md`（marketplace/plugin 目录与包结构设计——本文档建立在它已实现的基础上）；`harness-testbed/cases/002-plugin-lifecycle/results.md`（本次发现的直接触发点）
- 背景：`docs/design/01-marketplace-plugin-design.md` 第六节 Install/Cache 落地并在 `harness-testbed` 上用真实第三方插件（`claude-md-management`）验证时，连续抓到两个真 bug（`Files.walk` 不解析起点软链接、`cache/<id>/<hash>/` 加载时把哈希目录名当成了 pluginId），两次都是"同一个东西的身份在不同代码路径里算出了不同结果"这一类问题。顺着这个线索系统性地过了一遍四条能力发现路径的命名规则，发现命名/身份唯一性这件事从来没有被当作一个整体设计过——每条路径是不同时间点各自独立写的，命名规则细节不一致，`SimpleMarketplace` 的查重逻辑也有实测出来的真实漏洞。这份文档把现状摊开、把所有冲突场景过一遍、给出这轮要修的东西。
- 面向读者：下一个要动这部分代码的人或 AI

---

## 一、当前机制：capabilityId 和 name 的关系

```
capabilityId = pluginId + "." + name
```

- `name`：这个能力自己的短名字（比如 skill 叫 `haiku-poet`、tool 叫 `get_weather`），不保证全局唯一
- `capabilityId`：在短名字前面拼上所属的 `pluginId` 当命名空间，是真正用来做唯一性判断、注册表查找、Planner LLM 实际输出去选择能力的字符串——等价于 Claude Code 的 `plugin:skill` 命名空间写法，只是分隔符用的是 `.`

**例外**：`DefaultPluginManager.registerTool/Skill/SubAgent()` 这种代码直注册，每个能力被当成"自己单独一个插件"，`pluginId == name`，所以 `capabilityId` 也退化成裸 `name`（没有 `.` 分隔）——本质还是同一条公式，只是两个值相等导致看不出前缀。

### 一开始怀疑过的一个"bug"，核实后确认不存在

四条发现路径（`ManifestPluginLoader.loadTools/loadSkills/loadSubAgents`、`FlatSkillLoader.loadOne`、`AnnotationPluginLoader`、`DefaultPluginManager.registerToolCapability/registerSkillCapability/registerSubAgentCapability`）的调用点确实都没有显式调用 `CapabilityDescriptor.builder().name(...)`，一度怀疑 `name` 字段一直是空的、Planner 提示词里会出现字面上的 `(null)`。但完整看完 `CapabilityDescriptor.CapabilityDescriptorBuilder.build()` 才发现它自己有兜底：

```java
public CapabilityDescriptor build() {
    if (type == CapabilityType.MCP_TOOL && tool != null) {
        fillNameAndDescription(tool.getName(), tool.getDescription());
    } else if (type == CapabilityType.SKILL && skillConfig != null) {
        fillNameAndDescription(skillConfig.getName(), skillConfig.getDescription());
    } else if (type == CapabilityType.SUB_AGENT && subAgentConfig != null) {
        fillNameAndDescription(subAgentConfig.getName(), subAgentConfig.getDescription());
    }
    ...
}

private void fillNameAndDescription(String defaultName, String defaultDescription) {
    if (isBlank(name)) name = defaultName;
    if (isBlank(description)) description = defaultDescription;
}
```

四条路径全部都会传 `.tool(...)`/`.skillConfig(...)`/`.subAgentConfig(...)`，`build()` 时会自动从这些对象自己的 `getName()`/`getDescription()` 把 `name`/`description` 兜底填上——`name` 从来不是真的空，之前的判断是只看了调用点、没看完整个 builder 实现导致的误判。这条不需要修，从"建议修复方案"里去掉了（见第四节）。

---

## 二、四条能力发现路径，命名规则不统一

| 来源 | pluginId 怎么来 | capabilityId 怎么来 | scope 概念 |
|---|---|---|---|
| `ManifestPluginLoader`（`plugins/<id>/` 或 `cache/<id>/<hash>/`） | manifest 声明的 `pluginId`，没声明就用**调用方传入的 idFallback**兜底（原来直接用 `pluginDir` 自己的目录名，`cache/` 场景下那是哈希，已在 01 号文档的"实现记录"里修过） | `pluginId + "." + 工具/skill/subagent 自己的 name` | 有：`~/.rex/marketplaces/*` vs `<project>/.rex/marketplaces/*`，各自还能有多个 marketplace 名字 |
| `FlatSkillLoader`（`skills/<name>/`） | 目录名 | `pluginId + "." + SKILL.md 里的 name`（注意可能跟目录名不一样） | 有：`~/.rex/skills` vs `<project>/.rex/skills`，外加 `config.yml` 的 `skills.extra_dirs` |
| `AnnotationPluginLoader`（`@Plugin`/`@AgentSkill`/`@AgentSubAgent`） | 注解自己的 `id()` | `pluginId + "." + 子能力 id`（单独用 `@AgentSkill`/`@AgentSubAgent` 时直接就是 `ann.id()`，没有 `.` 前缀） | 无——纯代码，`regnexe-cli` 目前不调用这条路径 |
| `DefaultPluginManager` 代码直注册（`registerTool/Skill/SubAgent`） | 就是这个能力自己的 `name` | 裸 `name`，跟 pluginId 完全一样 | 无——`regnexe-cli` 目前不调用这条路径 |

四条路径的 `capabilityId` 拼接不是从一个共用工具函数出来的，是各自独立写的字符串拼接——这是"命名规则没有被当成一个整体设计"的直接证据。

### `SimpleMarketplace` 目前怎么处理冲突

`install()` 检查两件事：

1. `plugins.containsKey(pluginId)`——pluginId 撞了就抛 `IllegalStateException`
2. 遍历这个插件带的每个 capability，检查 `capabilityIds`（全局已装的集合）有没有撞——撞了也抛

**关键漏洞**：这两个检查都是"先整批比对全局已装的、再整批写入"——`for` 循环查重完才用 `caps.forEach(...)` 一次性写入 `capabilityIds` 集合，**同一次 `install()` 调用内部，这批 capabilities 互相之间完全不会触发查重**（查重比对的是写入前的全局集合，这批次还没写进去）。

`DefaultPluginManager.installCatching()` 把 `IllegalStateException` 整个吞掉，变成"跳过这整个插件、记一条 WARN 日志"——四条路径共用同一段兜底逻辑，行为一致，代价是**先装的赢，后装的整个插件（不只是撞车的那个能力）被丢弃**。这条 WARN 日志在打包后的 `regnexe-cli` 里既不打印到控制台也不写文件（`harness-testbed` 实测确认过），之前完全静默；`regnexe-cli` 现在给 marketplace 级别的冲突加了可见告警（见第四节 #3），但覆盖范围还不完整。

---

## 三、完整冲突分类

按"什么东西撞了"分三个层级，逐条过了一遍所有来源两两组合，确认没有遗漏。

### pluginId 级别（整个插件/技能互相顶掉）

| # | 冲突双方 | 现状 | 判定 |
|---|---|---|---|
| 1 | 同一 marketplace，`.rex/marketplaces/*/cache/`，项目 scope vs 用户 scope | 项目赢（`resolveMarketplacePluginDirectories` 里项目排前面，先扫到的赢） | ✅ 正确，已验证（`--scope project`/`--scope user` 都在 `harness-testbed` 上装过） |
| 2 | 不同 marketplace，同 pluginId | 先扫到（scope 优先、marketplace 名字母序）的赢 | ✅ 已加可见告警（`/plugin install`/`/plugin list` 触发 `warnDuplicatePluginIds`） |
| 3 | `skills/`，项目 scope vs 用户 scope | `resolveSkillDirectories()` 先加用户目录、再加项目目录——**用户赢，项目输**，跟第 1、2 条规则相反 | ❌ bug，判定为疏漏（当初写的时候注释是"order = load order, not priority"，没往优先级上想；后来第 1、2 条定下"项目赢"的规则时没有回头同步这里） |
| 4 | `skills/`（项目+用户）vs `config.yml` 的 `skills.extra_dirs` | `extra_dirs` 拼在列表最后，优先级最低 | ⚠️ 没有正式设计过，现状可以接受（extra_dirs 语义上是"补充来源"），这轮不特意处理 |
| 5 | 裸 skill（`skills/`）vs marketplace 插件，pluginId 字符串撞了 | `buildAgent()` 里 `withSkillsDirectory(...)` 排在 `withPluginDirectory(...)` 前面调用，**skill 赢，插件被跳过**——这跟第 1/2/3 条比的是不同维度（project/user 排序），这里比的是"skill 整体 vs plugin 整体"哪类先装 | ❌ 没有告警覆盖——`warnDuplicatePluginIds` 现在只扫了 `marketplaces/*/cache/`，没扫 `skills/`，这种"裸技能跟插件撞名"完全静默 |
| 6 | 注解扫描 / 代码直注册 vs 以上任意一个 | 机制上跟其他四条一致（先到先得），但 `regnexe-cli` 现在完全不调用 `scanPackages`/`withTool`/`withSkill`/`withSubAgent` 这几条路径，纯理论场景 | ⏸ 不影响当前实际使用；代码直注册即使用了也扫不到磁盘上的冲突（不落盘），是个结构性限制，记录但不处理 |

### capabilityId 级别（单个插件/注解 bean 内部）

| # | 冲突双方 | 现状 | 判定 |
|---|---|---|---|
| 7 | 同一个插件内部，任意两个能力（tool-tool、tool-skill、skill-subagent……不限于"tool 和 skill"这一种搭配）算出同一个 capabilityId | 完全不查重（见第二节"关键漏洞"）——两个都会被塞进同一个 `PluginDescriptor.capabilities` 列表，`resolveDescriptor()` 只按字符串找、不看类型，谁先加载谁赢，另一个变成永远查不到、但也不报错的死能力 | ❌ 这轮发现的最深的一个漏洞，`ManifestPluginLoader` 和 `AnnotationPluginLoader`（`@AgentTool` 方法 + 嵌套 `@AgentSkill`/`@AgentSubAgent`）都会触发，修复在 `SimpleMarketplace.install()` 一处即可覆盖两边 |

### name（短名字）级别——不是真冲突

| # | 场景 | 现状 | 判定 |
|---|---|---|---|
| 8 | 不同插件的 skill 短名字一样（pluginId 不同） | capabilityId 天然靠 pluginId 前缀隔离，Planner LLM 不受影响；人直接打 `/短名字` 时，`CliMain.resolveSkillId()` 已经做了歧义检测——撞到多个同短名会打印"Ambiguous skill name"、列出候选、要求用完整 id | ✅ 已经是对的，不需要改 |
| 9 | tool 短名字跟别的东西撞 | tool 没有 `/短名字` 直调这条路径（只有 `CapabilityType.SKILL` 才会被 `listSkills()` 收进候选），这个场景天然不存在 | ✅ 不适用 |

---

## 四、这轮建议的修复方案

按风险从低到高：

### 1. `SimpleMarketplace.install()` 加批内查重

现在的查重循环只比对"这批 capabilities vs 全局已装的"，改成额外维护一个"这批次内已经见过的 id"集合，两个集合一起查。撞了照旧走 `IllegalStateException` → `installCatching()` 接住变成"跳过整个插件 + 记警告"的老路，不引入新策略，只是把第三节 #7 那个漏洞堵上。

### 2. `resolveSkillDirectories()` 交换项目/用户顺序

对应第三节 #3。把 `~/.rex/skills` 和 `<project>/.rex/skills` 两行的添加顺序换一下，项目排前面，跟 `resolveMarketplacePluginDirectories`/`enabled.yml` 的 `ScopeResolver` 合并顺序保持一致。

### 3. `warnDuplicatePluginIds` 扩大扫描范围到 `skills/`

对应第三节 #5。现在的实现（`CliMain.listAllInstalledEntriesInScanOrder`）只扫 `marketplaces/*/cache/`，需要把 `skills/`（含 `resolveSkillDirectories` 里的 `extra_dirs`）也纳入同一次扫描、同一套告警文案，复用第 01 号文档"实现记录"里已经验证过的告警机制，不新增设计。

---

## 五、明确不做的事（及理由）

| 项目 | 是否做 | 理由 |
|---|---|---|
| 跨 marketplace 冲突从"警告"升级成"拒绝安装"，或注册表 key 加 marketplace 前缀 | ❌ 不做 | `01-marketplace-plugin-design.md` 讨论 Install/Cache 设计时明确决定过不给 `PluginDescriptor` 加 marketplaceName 字段（怕破坏"重复 id 优雅降级"这条已测过的行为）。现在"用户完全看不到冲突"这个真正的痛点已经靠告警解决了，没有新的理由再推翻这个决定 |
| `skills/` 与 marketplace 插件强制分命名空间（比如给 pluginId 加 `skill:`/`plugin:` 前缀） | ❌ 不做 | 会改变 capabilityId 本身的格式，是一次真实的破坏性变更，还会连带影响 Planner 已经在用的 capabilityId 文本。实际撞名概率也不高：`skills/` 是用户自己写的个人技能，marketplace 插件 id 一般都带发行方风格命名（`claude-md-management`），意外撞名比"同一插件装两个 marketplace"这种自己制造的场景低得多。用第四节 #3 的告警兜底即可 |
| `AgentEvent` 加结构化 `capabilityType` 字段，让执行监控能区分 skill/tool/subagent | ❌ 不做 | 讨论中确认现状是"能看到（`skill:`/`mcp_tool:`/`subagent:` 前缀打进了 `AgentEvent.text` 自由文本），但没有结构化字段可查询，只能字符串解析"——这是个真实的可观测性缺口，但用户明确说这轮"能监控看到就行，这个先不用管了"，先记录、不处理 |
| `skills.extra_dirs` 在优先级链条里的正式位置 | ❌ 不做 | 对应第三节 #4，现状（排在最后、优先级最低）没有暴露出实际问题，不提前设计 |

---

## 六、验证方式

第四节 1-3 这轮还没有一行代码落地，实现之后建议按 `harness-testbed` 一贯的方法论验证：

- **单元测试**（regnexe-agent 侧）：`SimpleMarketplace` 新增一个"同一插件内两个 capability 算出同一个 id 应该抛异常"的用例（对应 #1）
- **真实场景验证**（`harness-testbed`）：用一个真实的、`skills/` 目录名跟已装插件 pluginId 故意撞名的场景，跑一遍 `/plugin install` 确认新告警触发（对应 #3），比自己手写的 fixture 更能验证告警文案在真实输出里是否清晰可读

---

## 实现记录

第四节 1-3 已全部落地，`regnexe-agent` `mvn test` 93/93 通过（含新增 `SimpleMarketplaceTest`）：

- **#1**：`SimpleMarketplace.install()` 加了 `seenInThisPlugin` 本批次查重集合，跟全局 `capabilityIds` 一起判重。新增 `SimpleMarketplaceTest`，先确认回退掉这处改动后测试真的会失败（复现出"两个能力都装进去、后一个变成不可达"的原始问题），再确认修复后通过
- **#2**：`CliMain.resolveSkillDirectories()` 交换了项目/用户目录的添加顺序，项目排前面
- **#3**：新增 `CliMain.listAllPluginIdSourcesForConflictCheck()` / `collectSkillEntries()`，把 `skills/`（项目、用户、`extra_dirs`）纳入 `warnDuplicatePluginIds` 的扫描范围；`/plugin install` 和 `/plugin list` 都改成调用这个新的合并入口

**实现时抓到一个真 bug，已修复**：第一版 `listAllPluginIdSourcesForConflictCheck()` 把 marketplace 缓存条目排在了 skills 条目前面——但 `buildAgent()` 里 `withSkillsDirectory(...)` 实际调用在 `withPluginDirectory(...)` 之前，真实系统里是 skill 赢。顺序写反导致警告文案说反了"谁赢"（说插件赢，实际是 skill 赢），比完全没有警告更糟——一条说得斩钉截铁、内容却是错的信息。用 `harness-testbed` 真实场景（同名 `claude-md-management` 分别做成 skill 和已装插件）跑出来才发现这个问题：第一次警告显示插件赢，跟 `/skills` 实际列出的内容一对照才发现不对。修复后重新验证：警告文案与 `/skills` 实际显示的内容一致（都是 skill 版本生效），`dup-test`（项目 vs 用户同名 skill）的场景也一并验证了项目版本正确胜出。

**没有实现的**（按第五节，明确留到以后）：远程拉取、`skills/` 与 marketplace 插件强制分命名空间、`AgentEvent` 结构化 `capabilityType` 字段、`跨 marketplace` 冲突升级为拒绝安装、`extra_dirs` 正式优先级设计。
