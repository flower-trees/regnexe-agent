# Pull Request: dev-0.1.1 → master

## Overview

This PR merges `dev-0.1.1` into `master`. Building on **0.1.0**, it comprehensively enhances Agent-layer observability and model control: **Sub-Agent private tool injection**, **Sub-Agent independent model config & inheritance**, **model extra-parameter forwarding** (`modelKwargs`), **Sub-Agent LLM response events** (`AGENT_LLM_RESPONDED`), **capability-level token tracking** (`CAPABILITY_TOKEN_USAGE`), **task-level token summary** (`TASK_TOKEN_SUMMARY` with per-model breakdown and duration metrics), along with a `RegnexeAgentBuilder` direct-LLM fix and an upgrade to `j-langchain 1.0.17`.

6 commits relative to `master`, covering **Feature** (tool injection, model control, observability).

---

## ✨ Key Changes

### 🔧 Sub-Agent / Skill Private Tool Injection (ownTools)

- **`CapabilityDescriptor.ownTools`**: New field to attach a dedicated tool list to each Sub-Agent / Skill
- **`CapabilityExecutor`**: Automatically injects `ownTools` when building the Sub-Agent executor; tools are visible only to that capability, not to others
- Enables fine-grained tool permission control — ideal for Sub-Agents with exclusive MCP or data-access tools

### 🤖 Sub-Agent Independent Model Config & Inheritance (SubAgentConfig)

- **`SubAgentConfig.model`**: New field to assign an independent model to each Sub-Agent
- **Inheritance mechanism**: If `model` is not set, the Sub-Agent inherits the parent's LLM; if set, `ModelProvider` dynamically supplies the requested model instance
- **`CapabilityExecutor.resolveCapabilities`**: Updated signature accepts a `llmProvider` parameter for model-factory-based provisioning
- Fixed `RegnexeAgentBuilder.withDefaultModel(BaseChatModel)` overwriting `llmProvider`: introduced a `directLlm` field — `"_direct_"` spec routes to the direct LLM, all other specs go through the real `llmProvider`

### ⚙️ Model Extra-Parameter Forwarding (modelKwargs)

- **`CapabilityDescriptor.modelKwargs`**: New `Map<String, Object>` field for vendor-specific parameters (`thinking`, `temperature`, etc.)
- **`ModelSpec.kwargs`**: New `kwargs` field propagated to the LLM builder during `provide(spec)`
- **`CapabilityExecutor`**: Merges `modelKwargs` into `ModelSpec` when building the Sub-Agent LLM, enabling capability-level fine-grained model control

### 📡 Sub-Agent LLM Response Event (AGENT_LLM_RESPONDED)

- **`EventType.AGENT_LLM_RESPONDED`**: Dedicated event type for Sub-Agent LLM responses, clearly distinct from `SKILL_LLM_RESPONDED`
- **`CapabilityExecutor`**: Emits `AGENT_LLM_RESPONDED` during Sub-Agent execution, replacing the previous reuse of `SKILL_LLM_RESPONDED`
- **`ConsoleEventListener`**: New `[SubAgent LLM  ]` label for console visibility

### 📊 Capability-Level Token Tracking (CAPABILITY_TOKEN_USAGE)

- **`EventType.CAPABILITY_TOKEN_USAGE`**: New event type for per-capability token stats
- **`AgentEvent.ofCapabilityTokenUsage`**: Factory method carrying the capability name (`capName`) and `AgentTokenUsageEvent`, making it easy to attribute token costs to specific Sub-Agents
- **`CapabilityExecutor`**: Emits `CAPABILITY_TOKEN_USAGE` after Sub-Agent execution for upstream aggregation
- **`ConsoleEventListener`**: New `[Cap Token Usage]` label

### 📈 Task-Level Token Summary (TASK_TOKEN_SUMMARY)

- **`EventType.TASK_TOKEN_SUMMARY`**: New task-scoped summary event emitted just before `AGENT_COMPLETED`
- **`TokenAggregatingEventListener`**: Auto-wrapped inside `RegnexeAgentBuilder.build()` — no manual registration needed; accumulates `TOKEN_USAGE` and `CAPABILITY_TOKEN_USAGE` across all rounds, then emits the summary on `AGENT_COMPLETED`
  - **Per-model breakdown**: `by_model` shows token details per `provider:model` key
  - **tool_calls fix**: Correctly accumulates `AgentTokenUsageEvent.toolCalls` via delta-diff (was always 0 in `delta_usage`)
  - **Duration metrics**: `elapsed_ms` (wall-clock total), `llm_ms` (pure LLM inference time), `llm_ms_by_model` (per-model breakdown)
  - **total field cleanup**: `provider` / `model` cleared on the aggregated `total` to avoid misleading cross-model labelling

---

## 📋 Environment Variables

No new environment variables in 0.1.1. Runtime relies on host-application LLM key configuration via `ModelProvider`.

---

## 🧪 Testing

- `ownTools` injection: verified tools are visible only to the target Sub-Agent
- Sub-Agent independent model: verified `SubAgentConfig.model` routes to the correct `ModelProvider` branch; `withDefaultModel(BaseChatModel)` no longer overwrites
- `modelKwargs` forwarding: verified `thinking` / `temperature` are correctly merged into `ModelSpec`
- `TokenAggregatingEventListener`: verified cross-round, cross-Sub-Agent token accumulation; `tool_calls` delta diff correct; `elapsed_ms` / `llm_ms` match actual timing

---

## 📦 Version

- Release: **0.1.1** (`master` is currently **0.1.0**)
- Dependency: `j-langchain 1.0.17`
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] `pom.xml` version bumped to `0.1.1`
- [x] `j-langchain` dependency version updated to `1.0.17`
- [x] `README.md` / `README_zh.md` version updated to `0.1.1`
- [x] `CapabilityDescriptor.ownTools` complete; `CapabilityExecutor` injects correctly
- [x] `SubAgentConfig.model` inheritance mechanism implemented; `resolveCapabilities` accepts `llmProvider`
- [x] `RegnexeAgentBuilder.withDefaultModel(BaseChatModel)` no longer overwrites `llmProvider`
- [x] `CapabilityDescriptor.modelKwargs` forwarded through `ModelSpec.kwargs`
- [x] `AGENT_LLM_RESPONDED` event type independent; `ConsoleEventListener` label updated
- [x] `CAPABILITY_TOKEN_USAGE` event and factory method complete
- [x] `TASK_TOKEN_SUMMARY` event; `TokenAggregatingEventListener` auto-wrapped in Builder
- [x] `tool_calls` delta-diff fix applied; `total` provider/model cleared
- [x] Duration fields (`elapsed_ms` / `llm_ms` / `llm_ms_by_model`) complete
- [x] No breaking API changes
