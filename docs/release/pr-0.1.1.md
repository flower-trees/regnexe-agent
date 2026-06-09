# Pull Request: dev-0.1.1 → master

## 概述 / Overview

本 PR 将 `dev-0.1.1` 合并至 `master`，在 **0.1.0** 基础上全面增强 Agent 能力层的可观测性与模型控制：**Sub-Agent 私有工具注入**、**Sub-Agent 模型独立配置与继承**、**模型额外参数透传**（`modelKwargs`）、**Sub-Agent LLM 响应事件**（`AGENT_LLM_RESPONDED`）、**能力级 Token 统计**（`CAPABILITY_TOKEN_USAGE`）、**任务级 Token 汇总**（`TASK_TOKEN_SUMMARY`，含按模型分组统计与耗时指标），以及 `RegnexeAgentBuilder` 直连 LLM 修复与依赖升级至 `j-langchain 1.0.17`。

相对 `master` 共 **6** 个提交，涵盖 **Feature**（工具注入、模型控制、可观测性）。

---

## ✨ 主要变更 / Key Changes

### 🔧 Sub-Agent / Skill 私有工具注入 (ownTools)

- **`CapabilityDescriptor.ownTools`**：在能力描述符中新增 `ownTools` 字段，支持为每个 Sub-Agent / Skill 注入专属工具列表
- **`CapabilityExecutor`**：在构建 Sub-Agent 执行器时自动将 `ownTools` 注入，工具仅对该能力可见，不污染其他 Agent
- 适用于需要专属 MCP 工具、数据访问工具的场景，支持精细化工具权限控制

### 🤖 Sub-Agent 模型独立配置与继承 (SubAgentConfig)

- **`SubAgentConfig.model`**：Sub-Agent 配置新增 `model` 字段，支持为每个 Sub-Agent 指定独立模型
- **模型继承机制**：未配置 `model` 时自动继承父 Agent 的 LLM；配置后通过 `ModelProvider` 动态提供对应模型实例
- **`CapabilityExecutor.resolveCapabilities`**：方法签名更新，新增 `llmProvider` 参数，支持模型工厂按 `ModelSpec` 提供不同规格的 LLM
- 修复 `RegnexeAgentBuilder.withDefaultModel(BaseChatModel)` 覆盖 `llmProvider` 的问题：引入 `directLlm` 字段，`"_direct_"` spec 走直连，其他 spec 走真实 `llmProvider`

### ⚙️ 模型额外参数透传 (modelKwargs)

- **`CapabilityDescriptor.modelKwargs`**：能力描述符新增 `modelKwargs` 字段（`Map<String, Object>`），用于向模型传递厂商特定参数（如 `thinking`、`temperature`）
- **`ModelSpec.kwargs`**：`ModelSpec` 新增 `kwargs` 字段，在 `provide(spec)` 时透传给 LLM 构建器
- **`CapabilityExecutor`**：在构建 Sub-Agent LLM 时将 `modelKwargs` 合并到 `ModelSpec`，实现能力级精细化模型控制

### 📡 Sub-Agent LLM 响应事件 (AGENT_LLM_RESPONDED)

- **`EventType.AGENT_LLM_RESPONDED`**：新增 Sub-Agent 专属 LLM 响应事件类型，与 `SKILL_LLM_RESPONDED`（Skill 响应）明确区分
- **`CapabilityExecutor`**：Sub-Agent 执行时发出 `AGENT_LLM_RESPONDED` 事件，替代原来复用 `SKILL_LLM_RESPONDED` 的做法
- **`ConsoleEventListener`**：新增 `[SubAgent LLM  ]` 标签，Sub-Agent 响应可在控制台独立可视

### 📊 能力级 Token 统计 (CAPABILITY_TOKEN_USAGE)

- **`EventType.CAPABILITY_TOKEN_USAGE`**：新增能力级 Token 统计事件类型
- **`AgentEvent.ofCapabilityTokenUsage`**：工厂方法，携带能力名称（`capName`）与 `AgentTokenUsageEvent`，方便区分哪个 Sub-Agent 消耗了多少 Token
- **`CapabilityExecutor`**：Sub-Agent 执行完毕后发出 `CAPABILITY_TOKEN_USAGE` 事件，供上层汇总
- **`ConsoleEventListener`**：新增 `[Cap Token Usage]` 标签

### 📈 任务级 Token 汇总 (TASK_TOKEN_SUMMARY)

- **`EventType.TASK_TOKEN_SUMMARY`**：新增任务维度汇总事件，在 `AGENT_COMPLETED` 之前发出
- **`TokenAggregatingEventListener`**：自动包装在 `RegnexeAgentBuilder.build()` 中，无需手动注册；跨所有 Round 累积 `TOKEN_USAGE` 与 `CAPABILITY_TOKEN_USAGE`，`AGENT_COMPLETED` 时发出汇总
  - **按模型分组**：`by_model` 字段展示各模型（provider:model）的 Token 明细
  - **tool_calls 修复**：正确累计 `AgentTokenUsageEvent.toolCalls`（累计值差分），`delta_usage.toolCalls` 始终为 0 的问题已修复
  - **耗时统计**：`elapsed_ms`（挂钟总耗时）、`llm_ms`（纯 LLM 推理耗时）、`llm_ms_by_model`（按模型分组耗时）
  - **total 字段清理**：汇总 `total` 中的 `provider` / `model` 字段清空，避免跨模型汇总时展示错误模型名

---

## 📋 环境变量 (Environment Variables)

与 0.1.0 保持一致，无新增环境变量。运行时依赖宿主应用配置 LLM 密钥（通过 `ModelProvider` 注入）。

---

## 🧪 测试 (Testing)

- `ownTools` 私有工具注入：验证工具仅对目标 Sub-Agent 可见
- Sub-Agent 独立模型：验证 `SubAgentConfig.model` 指定后走独立 `ModelProvider` 分支，`withDefaultModel(BaseChatModel)` 不再覆盖
- `modelKwargs` 透传：验证 `thinking` / `temperature` 等参数正确合并到 `ModelSpec`
- `TokenAggregatingEventListener`：验证跨 Round、跨 Sub-Agent 的 Token 累积正确，`tool_calls` 差分计算正确，`elapsed_ms` / `llm_ms` 与实际耗时一致

---

## 📦 版本 (Version)

- 发布版本：**0.1.1**（`master` 当前为 **0.1.0**）
- 依赖：`j-langchain 1.0.17`
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] `pom.xml` 版本号已更新至 `0.1.1`
- [x] `j-langchain` 依赖版本已更新至 `1.0.17`
- [x] `README.md` / `README_zh.md` 依赖版本号已更新至 `0.1.1`
- [x] `CapabilityDescriptor.ownTools` 实现完整，CapabilityExecutor 正确注入
- [x] `SubAgentConfig.model` 继承机制实现，`resolveCapabilities` 接受 `llmProvider`
- [x] `RegnexeAgentBuilder.withDefaultModel(BaseChatModel)` 不再覆盖 `llmProvider`
- [x] `CapabilityDescriptor.modelKwargs` 透传至 `ModelSpec.kwargs`
- [x] `AGENT_LLM_RESPONDED` 事件类型独立，ConsoleEventListener 标签已更新
- [x] `CAPABILITY_TOKEN_USAGE` 事件与工厂方法完整
- [x] `TASK_TOKEN_SUMMARY` 事件，`TokenAggregatingEventListener` 自动包装于 Builder
- [x] `tool_calls` 差分累计修复，`total` 字段 provider/model 清空
- [x] 耗时统计（`elapsed_ms` / `llm_ms` / `llm_ms_by_model`）字段完整
- [x] 无破坏性 API 变更
