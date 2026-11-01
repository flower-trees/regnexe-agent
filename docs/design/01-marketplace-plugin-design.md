# Marketplace / Plugin 目录与包结构设计

- 状态：**第二节（regnexe-agent 包结构）、第六节（Install/Cache/Uninstall/enabled.yml 写入 + CLI 接线）均已实现**——见下面"实现记录"
- 后续文档：能力命名规则与冲突处理专门拆到了 `docs/design/02-capability-naming-and-collision-design.md`（跨 marketplace/scope/skill 撞名的完整分类和修复方案）；跟四家 Harness 重新对比一轮之后的差距清单在 `docs/design/03-harness-parity-gap-analysis.md`；长期记忆、会话压缩策略、`--continue`、Skill 执行上下文这四项的具体设计在 `docs/design/04-session-memory-and-skill-context-design.md`；Plan 阶段跨轮次任务分解（对齐 Claude Code TodoWrite / Codex update_plan）的方向记录在 `docs/design/05-task-roadmap-multi-round-planning.md`
- 涉及仓库：`j-langchain` → `regnexe-agent` → `regnexe-cli`（依赖方向从左到右）
- 背景资料：`docs/harness/claude-code/`、`docs/harness/codex/`、`docs/harness/deepseek/` —— 本设计是在读完这三家 Harness 的 Marketplace/Plugin 分析后，对照 regnexe 现状做的差距梳理和重新设计
- 面向读者：下一个要实现这部分改动的人或 AI；目的是不用重新走一遍讨论过程就能知道"最终定了什么、为什么这么定、还有哪些明确没做"

## 实现记录

regnexe-agent 这一侧（第二节全部内容）已经按设计落地，`mvn install` 通过、`mvn test`（除真实 LLM 调用的 example 测试外）21/21 通过：

- `market` 包整体改名为 `marketplace`（regnexe-cli、j-langchain 经核实都没有引用这个包，不是跨仓库 breaking change）
- `CapabilityType`（原 `common.enums`）、`CapabilityDescriptor`（原 `marketplace.plugin`）挪进新的 `marketplace.capability`
- 新增 `marketplace.plugin.PluginManifest`——manifest 的"声明"层，和归一化后的 `PluginDescriptor` 分开
- `DefaultPluginManager` 拆成三个 loader：`ManifestPluginLoader`（manifest-based 插件）、`FlatSkillLoader`（新的 `skills/` 专用扫描器）、`AnnotationPluginLoader`（`@Plugin`/`@AgentSkill`/`@AgentSubAgent` 扫描），`DefaultPluginManager` 变成组合三者的门面
- **行为变化**：`addDirectory()` 不再对无 manifest 的目录做扁平技能兜底了——那个兜底行为整体挪到了新的 `addSkillsDirectory()` / `PluginManager.loadSkillsFromDirectory()`。对应更新了 `DefaultPluginManagerManifestCompatTest`（`directoryWithBareSkillMdAtRoot_loadsAsSingleSkillPlugin` 拆成了 `skillsDirectoryWithBareSkillMd_loadsAsSingleSkillPlugin` + `addDirectoryNoLongerFallsBackToFlatSkillLoading` 两个用例）
- 新增 `marketplace.scope` 包：`Scope`（MANAGED/PROJECT/LOCAL/USER，MANAGED 和 LOCAL 目前还没有对应的磁盘位置，先占枚举值）、`ScopedEnabledState`、`EnabledStateLoader`（解析 `enabled.yml`）、`ScopeResolver`（纯 Map 合并逻辑，**不内置优先级顺序**——第五节①的开放问题还没定，谁先谁后由调用方传参数决定）

第六节（Install/Cache/Uninstall）也已按拍板方案落地，`mvn test` 30/30 通过（含新增的 `PluginCacheInstallerTest`、`EnabledStateWriterTest`）：

- 新增 `marketplace.loader.PluginCacheInstaller`：本地路径 `install`/`uninstall`，SHA-256 内容哈希（前 12 位）、`CURRENT` 纯文本指针、`.git/` 排除、uninstall 立即整目录删除
- 新增 `marketplace.scope.EnabledStateWriter`：`EnabledStateLoader` 的写入侧，原子写回（temp + rename）
- `loader/PluginManager` 新增 `loadPluginDirectory`（单个已解析插件目录，不做子目录枚举）、`DefaultPluginManager` 对应新增 `addPluginDirectory`，`RegnexeAgentBuilder.Builder` 新增 `withPluginDirectory(...)` 和 `withEnabledState(Map<Scope,Path>, List<Scope>)`
- regnexe-cli：`resolveMarketplacePluginDirectories()` 改成只解析 `cache/<plugin-id>/CURRENT`（不再碰 `plugins/`，见 §6.3）；新增 `/plugin install|uninstall|enable|disable|list` 五个子命令；`buildAgent()` 里接了 `withEnabledState`，User/Project 两层，**Project 覆盖 User** 是这轮定的临时默认值（第五节①最终优先级顺序仍未正式拍板，这只是让 enable/disable 有地方生效的务实选择，不是对开放问题的最终回答）

**验证时抓到两个真 bug，均已修复**：

1. `PluginCacheInstaller.install()` 最初直接对 `source` 调 `Files.walk()` 算哈希/拷贝——但 `Files.walk()` 只在**递归过程中**跟随符号链接，不会解析作为**起点**传入的那个符号链接本身。用 `harness-testbed` 案例 002 那个真实场景（`plugins/<id>` 是指向真实第三方插件的软链接）实测时，第一次跑出来的 hash 是 `e3b0c44298fc`——查了一下就是 SHA-256 对空输入的哈希，说明什么都没读到，cache 目录建出来是空的。修复：`install()` 一开始就对 `source` 调 `toRealPath()` 解析这一跳，再传给哈希/拷贝逻辑。补了回归测试 `install_sourceIsSymlinkToDirectory_resolvesRealContent`，并在 `harness-testbed` 里用真实的 `claude-md-management` 符号链接重新跑了一遍 `/plugin install` 确认修复生效（cache 目录里能看到 `plugin.json`/`skills/`/`commands/` 等真实文件）。
2. `ManifestPluginLoader.load(pluginDir, manifest)` 在 manifest 没声明 `pluginId` 时用 `pluginDir` 自己的目录名兜底——这条规则对 `plugins/<id>/`（目录名就是 id）是对的，但 `DefaultPluginManager.loadSinglePluginDirectory` 从 `cache/<id>/<hash>/` 加载时，传的 `pluginDir` 是**哈希目录**，兜底出来的 pluginId 变成了哈希本身。真实的 `claude-md-management` 插件 `.claude-plugin/plugin.json` 只写了 `name`、没写 `pluginId`（Claude Code 插件的常见写法），于是被注册成了 pluginId `cc3ff912a41e`，导致 `/plugin disable claude-md-management@...` 精确地切换了一个不存在的 id——UI 显示成功，运行时完全没生效。修复：`ManifestPluginLoader.load` 加了 `(Path, PluginManifest, String idFallback)` 重载，`DefaultPluginManager` 从 cache 加载时改用**父目录名**（真正的 id）做兜底。补了 `CachedPluginLoadingTest`，先确认它在修复前会失败（复现出同样的 `[cc3ff912a41e]` 错误 id），再确认修复后通过。

两次都是"纯手写的测试 fixture 测不出来、只有拿真实的、字段不规范的第三方插件（`claude-md-management`）走一遍完整链路才暴露"的同一种模式——`harness-testbed` 案例 002 的完整记录见其 `results.md`。

**这轮之后又发现并修复了一个设计缺口**（不是 bug，是原设计没覆盖到的场景）：同一个 pluginId 装进不同 marketplace 名字、或者同时装在 user/project 两个 scope，`SimpleMarketplace` 的注册表只按裸 `pluginId` 去重（不带 marketplace 命名空间——参见前面"Scope-creep self-correction"那条决定），运行时只有先扫到的生效，其余的被 `DefaultPluginManager` 的"先到先得"规则静默跳过，且这条 WARN 级日志在打包后的 CLI 里既不打印到控制台也不写文件，用户完全看不到。`/plugin list` 原本也只显示"磁盘上装没装"，不反映真正生效的是哪一个，容易造成"看着两个都装好了、实际只有一个在跑"的误判。

修复（regnexe-cli 侧，不改 regnexe-agent 的"先到先得"机制本身）：
- `/plugin install` 成功后、`/plugin list` 每次都会检测是否存在 pluginId 冲突，冲突时打印形如 `[warn] plugin id 'X' is installed in more than one place — only X@A (project) is actually active; X@B (project) is silently shadowed` 的提示，把原本完全静默的行为变成可见信息
- `/plugin list` 显示的 enabled 状态改成真正跑一遍 `ScopeResolver`（User→Project 合并，跟 `buildAgent()` 用的是同一段代码 `resolveEnabledAcrossScopes`），不再只读当前这一个 scope 自己的 `enabled.yml`——之前如果同一个 key 在两个 scope 都有声明，`/plugin list` 显示的可能跟实际生效的状态对不上

这不是"解决"冲突（依然是先到先得，没有改成报错拒绝或者强制加 marketplace 命名空间），只是把之前完全不可见的行为变得可诊断——真要禁止冲突或者改注册表 key 结构，是更大的改动，这轮没做。

**仍未做的**：远程拉取（§6.1 明确排除）、Local/Managed scope 磁盘位置、企业级治理（第五节②④⑤未变）、真正禁止/阻止 pluginId 冲突（这轮只做到了让冲突可见，没有改变"先到先得"的底层机制）。

---

## 一、背景：现状和差距

### 1. regnexe 现在的三层栈

```
j-langchain    → Skill/SubAgent 执行引擎，SKILL.md 兼容加载器（claudeCompatMode）
regnexe-agent  → Search→Plan→Execute→Reflect 循环 + Marketplace/Plugin 能力注册层
regnexe-cli    → CLI Harness（session/tools/UI/config），本应消费 regnexe-agent 的 marketplace 能力
```

这套栈已经在做 Claude Code 同款的事情：SKILL.md 格式兼容、`capabilityId = pluginId + "." + name` 命名空间（等价于 `plugin:skill`）、渐进式加载（`CapabilitySearcher` 只吐 name/description，正文在真正执行时才展开）。这些不算差距，是已经做对的部分。

### 2. 已知差距（对照 Claude Code / Codex 分析出来的）

- **只有一个全局 `SimpleMarketplace` 实例**，没有 Managed/Project/User/Local 作用域分层；现在靠"目录扫描顺序 + 先到先得"模拟优先级
- **没有 Plugin 安装/版本/缓存机制**——`DefaultPluginManager` 直接 `Files.list()` 扫目录，扫到什么就是什么，没有"从 marketplace 装进本地"这一步
- **`enabled` 状态是纯内存的**——`Marketplace.enable()/disable()` 接口有，但 `SimpleMarketplace` 里的 `Set<String> enabled` 每次进程重启就丢，也没有跨 scope 覆盖的能力
- **`DefaultPluginManager` 是个 600+ 行的上帝类**，manifest 解析、扁平 Skill 兜底、注解扫描三种发现机制全揉在一起
- **regnexe-cli 这一层的 CLI 接线代码实际丢失了**——`/skill` 分发、`/skills` 列表、marketplace 目录扫描只有一份开发日志（`docs/log/2026-07-21-skill-slash-invocation.md`）描述过设计，代码没有真正提交进这个仓库，当前 `CliMain.java` 还是最原始的三态 `SlashResult`

这份文档不解决最后一条（CLI 接线属于另一轮工作），聚焦前四条：**Marketplace/Plugin 的包结构和磁盘约定怎么重新设计**。

---

## 二、Java 包结构（regnexe-agent）

### 1. 包名：`market` → `marketplace`

跟文档、讨论、Claude Code/Codex 的术语保持一致。这是一次性的 breaking rename，`regnexe-cli`、`j-langchain` 里所有 `import org.salt.regnexe.agent.core.market.*` 都要跟着改。

### 2. 目标结构

```
org.salt.regnexe.agent.core.marketplace
│
├── Marketplace.java                              # 接口不变：install/uninstall/enable/disable/search/resolve
├── SimpleMarketplace.java
│
├── scope/                                        # 新增：作用域解析
│   ├── Scope.java                  # enum: MANAGED, PROJECT, LOCAL, USER（顺序即优先级，具体顺序见第三节）
│   ├── ScopedSource.java           # 一条来源 + 它所属的 scope
│   └── ScopeResolver.java          # 合并各 scope 的 enabled.yml，算出最终 enabled 状态（见第三节 4）
│
├── plugin/                                       # 沿用现有子包，补 manifest 层
│   ├── Plugin.java / AgentSkill.java / AgentSubAgent.java   （现有注解，不变）
│   ├── PluginManifest.java         # 新增：plugin.yaml / .claude-plugin/plugin.json 解析后的原始数据
│   │                                #   （"声明"层，区别于下面归一化后的 PluginDescriptor）
│   └── PluginDescriptor.java
│
├── capability/                                   # 新增：把能力模型收拢进来
│   ├── CapabilityDescriptor.java   # 从 market.plugin 挪过来
│   └── CapabilityType.java         # 从 common.enums 挪过来（SKILL/SUB_AGENT/MCP_TOOL，
│                                    #   未来加 HOOK 之类新类型就在这里加枚举值）
│
└── loader/                                       # 新增：拆分 DefaultPluginManager 现在做的三件事
    ├── PluginManager.java / DefaultPluginManager.java   （门面，组合下面三个 loader）
    ├── ManifestPluginLoader.java   # plugin.yaml / .claude-plugin/plugin.json + tools/skills/subagents 子目录
    ├── FlatSkillLoader.java        # 专职扫 skills/（用户级、项目级各一份），不再是 marketplaces/*/plugins/ 里的兜底
    └── AnnotationPluginLoader.java # @Plugin/@AgentSkill/@AgentSubAgent 包扫描
```

`task/` 整棵子树不动——Search→Plan→Execute→Reflect 循环跟这次的 Marketplace/Plugin 分层是两件事。

### 3. 关于 Cache

> **这节内容已被第六节取代**——最初设想是 cache 作为可选装饰器"自动镜像 `plugins/`"，第六节拍板后改成了 `plugins/`（可安装清单，不加载）→ `cache/`（唯一加载入口，只能通过 `/plugin install` 显式写入）两级模型，理由和具体设计见第六节。`regnexe-cli` 侧调用方式不变，还是 `.withDirectory(...)`，只是现在传进去的只会是 `cache/` 解析出的目录，不会再是 `plugins/`。

---

## 三、磁盘约定（regnexe-cli 消费）

### 1. 两个基本原则

1. **`skills/` 和 `marketplaces/*/plugins/` 职责分开，且加载方式不同**：前者是活跃编辑的个人/项目技能（无 manifest，skill-creator 这类场景直接写这里，永远直读不进 cache——对齐 Claude Code 自己的行为：`.claude/skills/` 也是直读）；后者是"发行版"插件包（带 `plugin.yaml`），但**不直接加载**——`plugins/` 只是这个 marketplace 里"可以装什么"的清单，真正被 `DefaultPluginManager` 扫描加载的只有 `cache/`（`/plugin install` 的产物）。这一条是第六节拍板后对齐 Claude Code/Codex 真实模型定下来的：两家的 marketplace 目录本身也只是源/清单，只有走过 install 的东西才会进 cache 被加载，没有"marketplace 目录里的东西直接生效"这种路径。
2. **"声明"和"物理位置"解耦**：一个插件启不启用，不应该只能由它物理所在的那个目录说了算——否则项目永远没法压制一个装在用户级的插件。所以 enabled 状态不跟着 marketplace 目录走，单独提到每个 scope 的根目录。
3. **不为不存在的功能预先搭骨架**。`source/` 中间层、`installed_plugins.json` 等价物、`known_marketplaces.json` 等价物这一版都不做，理由见第四节。

### 2. 目录结构

```
~/.rex/                              project/.rex/
├── config.yml                       ├── config.yml
├── enabled.yml                      ├── enabled.yml
├── skills/                          ├── skills/
│   └── <name>/SKILL.md              │   └── <name>/SKILL.md
└── marketplaces/                    └── marketplaces/
    └── <name>/                          └── default/
        └── plugins/（可安装清单，不加载）      └── plugins/
            └── <plugin-id>/                     └── <plugin-id>/
                ├── plugin.yaml                      ├── plugin.yaml
                ├── tools/                            ├── tools/
                └── skills/                           └── skills/
        └── cache/（唯一加载入口，见第六节）        └── cache/
            └── <plugin-id>/                     └── <plugin-id>/
                ├── CURRENT                          ├── CURRENT
                └── <hash>/                          └── <hash>/
```

`enabled.yml` 是每个 scope 唯一保留的状态文件，挪到了各自根目录（不再嵌套在 `marketplaces/<name>/` 下面），key 用全局唯一的 `<marketplace>/<plugin-id>`：

```yaml
# project/.rex/enabled.yml
weather-tools@personal: false     # 覆盖一个物理上住在用户级 ~/.rex/marketplaces/personal/ 的插件
security-review@default: true
```

这样项目级文件可以引用任何 scope 下任何 marketplace 里的插件，不再被"只能管自己目录底下的东西"卡住。

### 3. `enabled.yml`：disable ≠ uninstall

这份文件控制的是**软开关**，不是安装状态：

- **禁用（disable）**：插件文件原地不动（`marketplaces/<name>/plugins/<id>/` 或 cache 里那份都不删），只是 `enabled.yml` 写 `false`。效果是 `Marketplace.search()` / `resolveDescriptor()` 不再把它交出去——Planner 自动选不到，`executeSkill()` 直调也应该拒绝。`SimpleMarketplace` 现在已经有 `if (!enabled.contains(...)) continue` 这行判断，这次改动只是把这个状态从纯内存变成能持久化、能跨 scope 覆盖的真实开关。
- **卸载（uninstall）**：真正把插件文件从磁盘删掉。`Marketplace.uninstall()` 接口已经有，但"物理删除哪个目录、cache 里的历史版本要不要一起清"这件事还没设计——见第五节开放问题。

### 4. `ScopeResolver` 的合并规则

它要解决的是"同一件事在多个 scope 都有说法、且说法不一致时听谁的"，具体到 regnexe 这版设计，实际只剩一个问题需要合并——**enabled 状态**：

```
User:    weather-tools@personal = true   （默认启用）
Project: weather-tools@personal = false  （这个项目里不想用）
最终：   false（Project 覆盖 User）
```

（同名能力冲突这个 Claude Code 里存在的另一类问题，regnexe 这边基本不会出现——`capabilityId` 本身带 `pluginId` 命名空间，`SimpleMarketplace.install()` 撞到真正重复的 capabilityId 是直接抛异常，不是"选一个赢家"，所以不需要单独一条覆盖规则。）

`ScopeResolver` 的实现就是按优先级顺序读各 scope 的 `enabled.yml`，同一个 key 后面的覆盖前面的。**这一版的优先级顺序、以及要不要加 Local 层，还没有最终拍板**，见第五节。

---

## 四、明确不做的事（及理由）

| 项目 | 是否做 | 理由 |
| --- | --- | --- |
| `source/` 中间层（把 `plugins/` 套一层 `source/` 跟 `cache/` 对称） | ❌ 不做 | 现在没有 git 远程 marketplace 支持，唯一形态就是本地目录，`source/` 这个名字纯属为"以后可能是 git checkout"预留占位。真要支持 `rex marketplace add <git-url>` 时，`plugins/` 目录本身就可以是那个 git checkout（`.git/` 和插件目录平级，跟 Claude Code 真实结构一致），到时候自然会有 |
| `installed_plugins.json` 等价物 | ❌ 不做 | Claude Code 需要它是因为要追踪多 scope 安装记录、版本解析结果、GC 用的 orphaned 标记。我们没有多 scope 安装、没有 GC，"是否已装"这件事已经完全体现在 `cache/<plugin-id>/CURRENT` 是否存在 + 里面的 hash，额外维护一份 JSON 只是制造一个可能跟目录状态不一致的镜像 |
| `known_marketplaces.json` 等价物 | ❌ 不做 | 存在的唯一理由是支撑 `rex marketplace add <source>` 命令——需要个地方记"用户申明过要用这个远程来源"。这个命令现在还不存在，`RexConfig.yml` 里配置的目录列表本身就是"已知 marketplace"的全部内容，没必要另外维护一份索引。等真做这个命令时再补 |
| Plugin 版本化 Cache | 🟡 可选，本文档已给出设计，但不是这一轮的必做项 | 收益主要针对"运行中 session 读到半成品"这个问题，regnexe-cli 现在是单用户本地交互式 CLI，没有 Claude Code 那种"后台自动更新 + 多 session 并发"场景，风险敞口小。设计先留着，具体要不要实现看下一轮排期 |
| Hook / Plugin 级 MCP Server 作为组件类型 | ❌ 不做 | `capability/CapabilityType` 现在只有 SKILL/SUB_AGENT/MCP_TOOL 三种。真要加 Hook，是在这个 enum 里加一个值、`loader/` 里加对应加载逻辑，不需要动 `Marketplace` 接口——这次的包结构已经把这条扩展路径留出来了 |
| DeepSeek 风格的 Bundle/Profile/Everything-is-a-Plugin | ❌ 不做 | 那是把 Agent Loop 本身也做成可替换插件的激进设计。`task/` 那条固定的 Search-Plan-Execute-Reflect 循环没有理由做成可组合的，属于过度设计 |

---

## 五、遗留的开放问题（留给后续轮次）

1. **`enabled.yml` 跨 scope 的最终优先级顺序**——`CliMain.buildAgent()` 现在按 Project 覆盖 User 接了（见"实现记录"），但这只是让 `/plugin enable|disable` 有地方生效的临时默认值，不是正式拍板：用户仍然没有任何办法对抗一个项目强制打开/关闭的插件。要不要加一层 **Local**（`project/.rex/enabled.local.yml`，不进 git，只对当前开发者生效，专门承接"项目要求开、但我这台机器想关"），仍然没定。
2. ~~**Uninstall 具体怎么实现**~~ ——已在第六节拍板，见下。
3. **`regnexe-cli` 的 CLI 接线怎么按新约定重建**——`resolveSkillDirectories()`、`/skills` 命令、skill-creator 的写入目标都要从"塞进某个 marketplace 的 plugins 目录"改成"写到 `skills/`"；`/plugin enable|disable` 命令的接线见第六节 5，但这两个命令本身（以及 `/skills` 列表展示）还没实现。
4. ~~**Cache 要不要这一轮就做**~~ ——已拍板要做，见第六节。
5. **企业级治理**（Marketplace Allowlist、Plugin 权限 Manifest、供应链审计）——这次完全没碰，是更远期的话题，`docs/harness/claude-code/7.Permissions...` 里的思路可以留到那时候再回来参考。

---

## 六、Install / Cache / Uninstall 实现设计（本轮拍板）

背景：`harness-testbed` 案例 002（四家 Plugin 生命周期对比）跑完后确认，regnexe 目前"装/卸"完全不存在——`.rex/marketplaces/*/plugins/` 里放什么就是什么，没有 install 命令，也没有 cache 目录。这一节把这个 gap 的具体实现方式定下来。

### 1. 范围：只做本地路径，不做远程拉取

`rex plugin install <本地路径>`——source 是本机磁盘上已经存在的一个目录（比如用户自己 `git clone` 下来的、或者从别处复制来的插件包），不支持 `install <git-url>` / `install <npm包名>`。原因：

- Claude Code / Codex 的 `install <marketplace>/<name>` 之所以能这么简洁，是因为背后有 `known_marketplaces.json` 等价物记着"这个 marketplace 源在哪、怎么拉取"——第四节已经明确决定这版不做这层索引，没有它就没法支持"仅凭一个名字"去 install
- 网络拉取要处理失败重试、鉴权、版本更新检测，是独立一大块工作，跟这次"补上 cache/uninstall 缺口"的目标不匹配
- 本地路径模式已经能覆盖 `harness-testbed` 案例 002 里那个真实场景（"引用一个我们没写过的第三方插件"），且给以后升级成远程拉取留了口子——`install` 内部只要多加一步"先把远程源下载到本地临时目录"，后面的 hash/拷贝/登记逻辑完全不用变

### 2. cache 目录结构与版本标识：内容 hash

```
marketplaces/<name>/
├── plugins/                    # 可安装清单：人工摆放，install 常见 source，但本身不被扫描加载（见第 3 点）
│   └── <plugin-id>/...
└── cache/                      # 唯一加载入口：只有走 install 命令的插件才会出现在这里
    └── <plugin-id>/
        ├── CURRENT              # 纯文本文件，内容就是当前生效的 hash 字符串（不用符号链接——
        │                        #   案例 002 已经验证过 SkillWorkspaceTools 会拒绝跟随指向沙盒外
        │                        #   的软链接，用文本指针能绕开这类边界情况，也更好审计/git diff）
        └── <hash>/               # hash = 对 source 目录内容算 SHA-256，取前 12 位十六进制
            ├── plugin.yaml
            └── ...
```

- hash 只对**内容**算，不读 manifest 里的 `version` 字段——案例 002 观察到的真实第三方插件（`commit-commands`、`jam`、`opencode-wakatime`）字段规范程度参差不齐，不能假设作者规范维护了 `version`
- 好处是天然幂等：同一份内容重复 `install`，hash 不变，直接跳过拷贝、只刷新 `CURRENT`（如果它当前指向别的 hash）——不会在 cache 里堆出一堆内容相同的目录
- 计算范围：拷贝/哈希时跳过 `.git/`（VCS 元数据不该进 cache），其余原样全拷贝

### 3. 唯一加载入口是 `cache/`，`plugins/` 不参与加载

对齐 Claude Code/Codex 的真实模型后拍板：`resolveMarketplacePluginDirectories()`（regnexe-cli）**只扫描每个 marketplace 的 `cache/`**（解析 `CURRENT` 后的实际目录），不再把 `plugins/` 传给 `DefaultPluginManager`。`plugins/` 纯粹是"这个 marketplace 里有什么可以装"的清单，`/plugin install` 的 source 参数常见来源就是这里面某个子目录，但清单本身不会被自动加载——跟两家真实产品一致：marketplace 源目录/仓库从来不直接生效，必须走一遍 install。

这是对更早草稿（"`plugins/` 优先于 `cache/`，两条路径并存，谁先扫到谁赢"）的推翻，不是补充——那版草稿是照抄现有实现打的兼容补丁，不是照真实两家的模型设计的，讨论后确认应该对齐真实模型。**这是一次真实的破坏性行为变更**：现有 `CliMain.resolveMarketplacePluginDirectories()` 直接扫 `plugins/` 加载的逻辑要整个换成扫 `cache/`；`harness-testbed` 案例 002 里"往 `plugins/` 底下放一个软链接就能生效"的测试方式，实现落地后需要改成先 `/plugin install <软链接路径>` 再验证，案例记录到时候要补一条"设计变更后重新验证"的说明，不能保留旧结论。

### 4. Uninstall：立即物理删除，无宽限期

`rex plugin uninstall <plugin-id>@<marketplace>`：

1. 删除整个 `cache/<plugin-id>/` 目录（含 `CURRENT` 和所有历史 hash 版本，不保留）
2. 删除该 scope `enabled.yml` 里对应的 `<plugin-id>@<marketplace>` key（不留 `false` 残留项）
3. 不影响 `plugins/<plugin-id>/`——那是清单/待装来源，本来就不参与加载（见第 3 点），`uninstall` 只管 `cache/`，`plugins/` 下的东西要删用户自己手动删目录

不做 Claude Code 式的 7 天宽限期：那个机制是为了防止"后台自动更新/多 session 并发时，正在运行的 session 读到一半文件被换掉"，regnexe-cli 是单进程交互式 REPL，没有这个并发场景（案例 002 小结已经论证过这一点）。以后如果 regnexe 长出后台常驻/多会话并发能力，需要重新评估这条。

### 5. `enabled.yml` 从"只读解析器"补上写入能力

`scope/EnabledStateLoader`（现状：纯 `load(Path) -> Map<String,Boolean>`）旁边新增 `scope/EnabledStateWriter`，提供：

- `setEnabled(Path enabledYml, String globalId, boolean value)`——读现有 map、改一个 key、原子写回（先写临时文件再 rename，避免 REPL 异常退出时截断文件）
- `remove(Path enabledYml, String globalId)`——uninstall 用

`install` 成功后，显式写入 `<plugin-id>@<marketplace>: true`（哪怕 `ScopeResolver` 对未声明的 key 默认就是 enabled）——目的是让 `enabled.yml` 完整记录"这是一个通过 install 走进来的插件"，而不是只能靠 cache 目录是否存在去反推，两份状态分开存但语义一致，便于以后人工审计/`git diff` 这个文件看装了什么。

### 6. CLI 命令面（regnexe-cli 新增）

| 命令 | 行为 |
| --- | --- |
| `/plugin install <本地路径> [--marketplace <name>] [--scope user\|project]` | 不传 `--marketplace` 默认 `default`；不传 `--scope` 默认 `project`（跟 `resolveSkillDirectories` 现在的默认取向一致：项目级优先可见） |
| `/plugin uninstall <plugin-id>@<marketplace> [--scope user\|project]` | 第 4 点描述的立即删除 |
| `/plugin enable <plugin-id>@<marketplace> [--scope ...]` / `/plugin disable ...` | 调用 `EnabledStateWriter.setEnabled(..., true/false)`——这两个命令跟 install/uninstall 是分开的缺口（`ScopeResolver`/`SimpleMarketplace` 的 enable/disable 判断逻辑本身已经在，只是没人写 `enabled.yml`），顺带一起补上 |
| `/plugin list` | 遍历所有 marketplace 的 `cache/`（不含 `plugins/`——那是清单，见 §6.3，`/plugin list` 目前不展示"能装但没装"的东西，只展示已装的），标出每个插件真正生效的 enabled 状态（`ScopeResolver` 合并 User/Project 后的结果，不是单看当前 scope 自己的 `enabled.yml`），并在检测到同一个 pluginId 装了不止一处时打印冲突警告（见"实现记录"） |

regnexe-agent 侧新增一个不依赖 CLI/session 概念的纯 API 类 `marketplace.loader.PluginCacheInstaller`（`install(Path source, Path marketplaceRoot) -> InstallResult`、`uninstall(Path marketplaceRoot, String pluginId) -> boolean`），CliMain 的 `/plugin` 命令只做参数解析和路径拼接，实际拷贝/哈希/`CURRENT` 读写都在这个类里——保持"regnexe-agent 是库、regnexe-cli 是壳"的既有分工。

### 7. 不在这轮做的

- 远程拉取（第 1 点已说明）
- `plugins/` 本身的版本化/GC——它现在的定位是"人工维护的可安装清单"，不参与加载也不进 cache，天然没有版本冲突问题
- Uninstall 的宽限期/GC（第 4 点已说明理由）
- 多 marketplace 间同 plugin-id 冲突的专门处理——现有"先扫到的赢，跳过重复"规则已经覆盖，这次不新增规则
