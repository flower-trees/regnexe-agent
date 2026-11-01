# Pull Request: dev-0.1.3 → master

## 概述

基于 0.1.2，完善插件市场加载、任务上下文与恢复、规划校验和事件调用树。目标版本 **0.1.3**，依赖 **j-langchain 1.0.19**。当前为发布准备，尚未发布。

## 主要变更

- **插件市场**：拆分注解、Manifest、独立 Skill 加载器；增加缓存安装与加载、启用状态读写和作用域解析，改进能力 ID 冲突检查。支持 `plugin.yaml` 与 Claude 风格 `.claude-plugin/plugin.json`。USER / PROJECT 已有落盘约定，LOCAL / MANAGED 仍为预留作用域。
- **Skill 执行**：支持兼容工作区及 `executeSkill(capabilityId, args, sessionId, displayGoal)` 直接执行，绕过 Search / Plan / Reflect。该路径不建立任务账本，也不支持通过 `pause()` 暂停。
- **上下文与恢复**：跨轮保留共享执行步骤及压缩摘要，改进轮次交接；`resume()` 继续该会话最近更新的非 FINISHED 任务，并接收补充指令。
- **规划与反思**：使用 JSON 输出模式，改进规划解析重试、迭代次数提示、历史记录与实际执行结果核对；增加能力 ID 校验。
- **任务清单**：增加模型驱动的 `write_todos`，待办保存在任务状态中并跨轮保留；不代表框架自动完成或强制执行每一项。
- **可观测性**：支持多监听器、Plan / Reflect 模型输出及 Token 用量事件；新增 `spanId` / `parentSpanId` 构建调用树。

## 兼容性变更与迁移

### 插件市场包结构

原 `org.salt.regnexe.agent.core.market` 迁移至 `core.marketplace`，并拆分子包：

| 类型 | 新包（前缀为 `org.salt.regnexe.agent.core`） |
|---|---|
| `Marketplace`、`SimpleMarketplace` | `marketplace` |
| `CapabilityDescriptor`、`CapabilityType` | `marketplace.capability` |
| `PluginManager`、`DefaultPluginManager` | `marketplace.loader` |
| `Plugin`、`AgentSkill`、`AgentSubAgent`、`PluginDescriptor` | `marketplace.plugin` |

更新业务代码 import、自定义实现和序列化类型信息，重新编译依赖项目。

### 规划与存储数据

- 删除 `ResultStrategy` 及 `PlanOutput.resultStrategy`、`PlanOutput.reasoning`；新增 `iterationsHint`。自定义 Planner、模板和消费者需同步调整，不再读取旧字段。
- 删除 `TaskRequest.supplementInput`；恢复指令通过 `resume(sessionId, supplementInput)` 传入并追加至任务目标。
- 跟随 j-langchain，`ConversationStorage` 的 app/user/session ID 参数使用 `String`；自定义存储需更新签名，并保持持久化键转换一致。
- 持久化任务需支持当前 `TaskExecutionState`，包括 `priorSteps`、`priorStepsSummary`、`lastToolResult` 和 `todos`；对旧数据显式处理缺失字段和类型变化。

### 恢复与事件语义

- `TaskStore.listResumable(sessionId)` 应返回除 `FINISHED` 外的任务，包括 RUNNING / PAUSED / FAILED / ESCALATED / TIMEOUT。恢复按 `updatedAt` 选择最近任务。
- `InMemoryTaskStore` 不支持跨进程恢复；需自行实现持久化存储。恢复可能重试外部工具调用，业务侧应处理幂等性。
- `withEventListener(...)` 从单监听器配置扩展为累加注册。多监听器按注册顺序派发并隔离运行时异常；单监听器仍保留原异常传播行为。
- 自定义事件消费端可使用新增 span 字段构建调用树，并兼容无 span 信息的手工事件。

## 验证与发布检查

- [x] 项目版本设为 `0.1.3`，依赖设为 `j-langchain 1.0.19`。
- [x] 中英文 README 及四个平台入门文章依赖版本同步。
- [x] 原 SNAPSHOT 依赖及临时覆盖为正式依赖的本地构建各通过 41 项测试，无失败、错误或跳过；主 JAR、源码包、Javadoc 包生成成功。
- [x] 使用最终 POM 的 `mvn -o clean verify -Plocal-tests -Dgpg.skip=true` 验证通过，41 项测试无失败、错误或跳过，三类 JAR 生成成功。
- [ ] 真实模型下验收 Search / Plan / Execute / Reflect、直接 Skill 执行、会话记忆与暂停恢复；未声称全量测试通过。
- [x] 用户确认 j-langchain 已发布，独立请求 Maven Central 的 `j-langchain/1.0.19/j-langchain-1.0.19.pom` 返回 HTTP 200。
- [ ] GPG 签名：已尝试，当前执行环境禁止访问本机 `.gnupg` 锁文件、trustdb 和 gpg-agent；需在有权限的本机终端完成。
- [x] 用户确认正式仓库为 `flower-trees/regnexe-agent`，origin 已同步为 `git@github.com:flower-trees/regnexe-agent.git`。
- [ ] 检查 regnexe-cli 兼容性；提交、合并、创建标签、正式发布。

本地验证配置和验收命令见 [发布验证说明](../guide/release-validation.md)。历史发布文档保留原版本及描述。

## 本轮真实接口检查

- `ExampleReadme01MultiToolTest` 未通过：Java 直连解析 DashScope 域名失败，显式使用本机代理后出现 `SocketException: Operation not permitted`。最终 TIMEOUT 是网络失败后的轮次耗尽，不能作为模型执行逻辑错误或额度不足的证据。
- 通过独立 HTTPS 请求检查同一 Key：测试配置中的 `deepseek-v4-flash` 返回 HTTP 404 / `model_not_found`；`qwen-plus` 返回 HTTP 200 和正常文本响应。
- 后续已确认 DeepSeek 官方接口支持 `deepseek-v4-flash` 和 `deepseek-chat`，均返回 HTTP 200 和正常文本。测试和文档中的主模型统一为 `Vendor.DEEPSEEK` + `deepseek-v4-flash`；独立子 Agent 示例使用 `deepseek:deepseek-chat`，密钥为 `DEEPSEEK_KEY`。仍需在允许 Java 联网的环境重跑完整验收；接口成功不等于集成验收通过。
