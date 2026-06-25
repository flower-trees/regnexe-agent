# Pull Request: dev-0.1.2 → master

## 概述 / Overview

本 PR 将 `dev-0.1.2` 合并至 `master`，在 **0.1.1** 基础上重点完善了**插件市场的可扩展性**与**任务执行的可恢复性/可观测性**：插件描述符改为手写 Builder 并支持**代码优先能力注册**（`withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)`），新增 **`@AgentSkill`/`@AgentSubAgent` 注解**（可嵌套进 `@Plugin` 一次性打包 tool+skill+subagent），新增**能力工具依赖（`allowedTools`）自动展开**机制，**事件系统重构**为统一的过滤/分发模型，以及**任务执行记录、断点续传、结果策略**等一系列任务执行链路增强。同时新增 20 篇核心技术文档与覆盖全部新能力的可运行示例测试套件，并按新能力顺序重排了 `README.md`/`README_zh.md`。

相对 `master` 共 **18** 个提交，涵盖 **Feature**（插件注册、注解扩展、工具依赖管理、执行记录与续传）、**Refactor**（事件系统、插件描述符、会话历史）、**Docs**（技术文章、README 重排）与 **Style**（事件日志前缀）。

---

## ✨ 主要变更 / Key Changes

### 🧩 插件市场：自定义 Builder 与代码优先注册

- **`PluginDescriptor` / `CapabilityDescriptor`**：移除 Lombok `@Builder`，手写 Builder 以支持自定义逻辑与校验
- **`PluginDescriptor.builder().tool(...)/.skillConfig(...)/.subAgentConfig(...)`**：一次调用即可把 `Tool`/`SkillConfig`/`SubAgentConfig` 自动包装成 `CapabilityDescriptor`，id 统一为 `pluginId + "." + name`，无需再手动逐个构造
- **`RegnexeAgentBuilder` 新方法**：`withTool(Tool...)`、`withSkill(SkillConfig...)`、`withSubAgent(SubAgentConfig...)`、`withPlugin(PluginDescriptor...)`——均无需手动构造 `Marketplace`
- **`DefaultPluginManager`**：新增 `registerTool`/`registerSkill`/`registerSubAgent`，对应代码优先注册路径；capabilityId 默认取自配置自身的 `name`
- **`SimpleMarketplace.install()`**：新增 `pluginId`/`capabilityId` 重复检测，检测到冲突直接抛出异常而不是静默覆盖——`capabilityId` 会被原文展示给 Planner LLM 用于精确匹配选择，冲突必须显式暴露

### 🏷️ `@AgentSkill` / `@AgentSubAgent` 注解

- **`@AgentSkill`**：纯标记注解——Skill 永远继承父 Agent 模型、不持有私有工具，注解类无需任何方法
- **`@AgentSubAgent`**：复用现有 `@AgentTool` 扫描机制，类内的 `@AgentTool` 方法自动成为该 Sub-Agent 的私有 `ownTools`，对外层 Agent 不可见
- **`DefaultPluginManager`**：`register(...)`/`scanPackages(...)` 自动识别并分发 `@Plugin`/`@AgentSkill`/`@AgentSubAgent` 三种注解
- **嵌套打包**：`@AgentSkill`/`@AgentSubAgent` 可作为 `@Plugin` 类的 `public static` 内部类嵌套，与该插件的 `@AgentTool` 方法共享同一个 `pluginId`，一次 `withPlugin(...)` 调用即可注册 tool+skill+subagent 全部能力；也可单独使用，独立成一个单能力插件

### 🔗 能力工具依赖管理（allowedTools）

- **`CapabilityCandidate.allowedTools`**：能力候选新增 `allowedTools` 字段
- **`TaskPlanner`**：规划阶段自动把被选能力的 `allowedTools` 依赖一并展开注入执行计划，确保 Skill/SubAgent 依赖的共享工具不会因未被显式选中而缺失
- **`SimpleMarketplace`**：补全 `allowedTools` 的读取与维护逻辑
- **依赖升级**：`j-langchain` 升级至 `1.0.18-SNAPSHOT`

### 📡 事件系统：过滤与分发重构

- **`shouldHandle` + `dispatch`**：事件监听器新增按类型过滤的入口，所有事件生产者统一改为调用 `dispatch` 而非直接 `onEvent`
- **新事件类型**：`SEARCH_STARTED`、`PLAN_STARTED`、`EXECUTION_STARTED`、`REFLECTION_STARTED`，与既有的 `*_COMPLETED` 配对，覆盖循环每一步的起止
- **`AbstractEventListener`**：新增基类，统一 Token/LLM 事件过滤与事件格式化（`format` 方法从 `ConsoleEventListener` 上移），`ConsoleEventListener` 改为继承该基类
- **日志前缀统一**：如 `[Agent Start]`、`[Search Result]`、`[Plan Result]`、`[TOOL Call]`/`[TOOL Result]`、`[Reflect Result]`、`[Agent Done]`、`[Task Token Usage]` 等，跨监听器保持一致

### ⏯️ 任务执行与恢复机制增强

- **`ToolExecutionRecord`**：记录每次工具调用的详细信息；`resumeMode` 支持断点续传时把历史执行记录（经 `ExecutionRecordFormatter` 统一格式化）注入续传 Prompt
- **`ResultStrategy`**（`RETURN_LAST` / `SYNTHESIZE`）：`PlanOutput` 新增结果返回策略与 `finalAnswerRequirements` 字段，控制执行器直接返回末次工具结果还是综合多个观测结果
- **`TaskExecutionState.lastToolResult`**：记录最近一次工具结果；`Reflector` 在 LLM 输出为空时回退使用该结果
- **`TaskRequest.displayGoal`**：会话历史展示优先使用更可读的 `displayGoal`，为空时回退到原始 `goal`
- **`TaskPlanner`**：历史消息改为真实的 Human/AI 消息对（而非拼接文本），避免 LLM 模仿历史格式；新增唯一 ID 与交叉校验规则，确保规划阶段不会重复选择同一能力、执行结果符合目标约束
- **`CapabilityExecutor`**：新增能力去重注册机制，防止重复能力被多次注入执行器
- 上下文输出字符上限默认值由 `800` 调整为 `2000`

### 📚 文档与示例

- **`docs/article/001/`**：新增 20 篇 Regnexe 核心技术文章，覆盖 Search→Plan→Execute→Reflect 循环、插件市场、多能力编排、Skill/SubAgent 分层抽象、私有能力接入、可控工具调用、暂停恢复、TaskStore 持久化、会话记忆、事件可观测性、结果策略、企业级治理等主题
- **`README.md` / `README_zh.md`**：按新能力重新组织为八段式叙事——多 tool 入门 → Skill/SubAgent → 插件概念与打包 → `@Plugin` 及其他注解方式（含包扫描） → 文件系统目录加载 → Marketplace → 三层上下文记忆 → 暂停恢复；每个代码块均对应一个可运行的 `ExampleReadme*Test`
- **`Example00`～`Example08`**：覆盖入门、天气预报、合同分析（Skill）、行程规划（SubAgent）、商务出行综合场景、插件加载（三种方式）、会话记忆、暂停恢复、注解化 Skill/SubAgent
- **`ExampleReadme01`～`ExampleReadme08`**：与 README 八个章节逐一对应的可运行示例代码

---

## 📋 环境变量 (Environment Variables)

与 0.1.1 保持一致，无新增环境变量。

---

## 🧪 测试 (Testing)

- `withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)`：验证各注册路径生成的 `CapabilityDescriptor` 类型、id 与依赖关系正确
- `SimpleMarketplace` 重复 id 检测：验证重复 `pluginId`/`capabilityId` 注册时正确抛出异常而非静默覆盖
- `@AgentSkill`/`@AgentSubAgent`：验证独立注册与嵌套于 `@Plugin` 内打包注册两种路径均能正确生成对应能力类型，子类 `@AgentTool` 方法正确转为私有 `ownTools`
- `allowedTools` 自动展开：验证 Planner 选中 Skill/SubAgent 后其依赖工具被自动注入执行器
- 事件分发与过滤：验证 `shouldHandle`/`dispatch` 正确路由，新增的 `*_STARTED` 事件按预期时机触发
- 任务续传：验证 `resumeMode` 下历史执行记录正确格式化注入，`ResultStrategy.RETURN_LAST` 下末次工具结果被直接返回
- 全部 `Example00`～`08`、`ExampleReadme01`～`08` 测试套件针对真实 LLM 调用通过

---

## 📦 版本 (Version)

- 发布版本：**0.1.2**（`master` 当前为 **0.1.1**）
- 依赖：`j-langchain 1.0.18-SNAPSHOT`（⚠️ 见下方检查清单，发布前需确认是否升级为正式版本）
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [ ] `pom.xml` 版本号由 `0.1.2-SNAPSHOT` 更新至 `0.1.2`
- [ ] `j-langchain` 依赖确认升级为正式发布版本（当前为 `1.0.18-SNAPSHOT`，不建议发布版本依赖 SNAPSHOT）
- [ ] `README.md` / `README_zh.md` 依赖版本号同步更新至 `0.1.2`
- [x] `PluginDescriptor`/`CapabilityDescriptor` 自定义 Builder 实现完整，`tool()/skillConfig()/subAgentConfig()` 行为符合预期
- [x] `withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)` 注册路径与 `SimpleMarketplace` 重复检测验证通过
- [x] `@AgentSkill`/`@AgentSubAgent` 独立注册与嵌套打包两种路径均验证通过
- [x] `allowedTools` 自动展开机制验证通过
- [x] 事件系统 `shouldHandle`/`dispatch`/`AbstractEventListener` 重构验证通过，日志前缀统一
- [x] 任务执行记录、`resumeMode` 续传、`ResultStrategy`、`displayGoal` 等功能验证通过
- [x] `docs/article` 20 篇文档完整，README 八段式重排与示例测试一一对应
- [x] 无破坏性 API 变更（新增方法均为追加，未删除既有公开 API）
