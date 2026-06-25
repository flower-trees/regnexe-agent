# Pull Request: dev-0.1.2 → master

## Overview

This PR merges `dev-0.1.2` into `master`. Building on **0.1.1**, it focuses on **plugin marketplace extensibility** and **task execution resumability/observability**: plugin descriptors are now hand-rolled builders with **code-first capability registration** (`withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)`), new **`@AgentSkill`/`@AgentSubAgent` annotations** (nestable inside `@Plugin` to bundle a tool+skill+sub-agent in one call), a new **capability tool-dependency (`allowedTools`) auto-expansion** mechanism, an **event system refactor** into a unified filter/dispatch model, and a series of **execution-record tracking, resume, and result-strategy** improvements across the task pipeline. It also adds 20 core technical articles and a runnable example suite covering every new capability, and reorganizes `README.md`/`README_zh.md` to match the new capability set.

18 commits relative to `master`, covering **Feature** (plugin registration, annotation support, tool-dependency management, execution recording & resume), **Refactor** (event system, plugin descriptors, session history), **Docs** (technical articles, README reorg), and **Style** (event log prefixes).

---

## ✨ Key Changes

### 🧩 Plugin Marketplace: Custom Builders & Code-First Registration

- **`PluginDescriptor` / `CapabilityDescriptor`**: Lombok `@Builder` removed in favor of hand-rolled builders that support custom logic and validation
- **`PluginDescriptor.builder().tool(...)/.skillConfig(...)/.subAgentConfig(...)`**: one call wraps a `Tool`/`SkillConfig`/`SubAgentConfig` into a `CapabilityDescriptor` automatically, id'd as `pluginId + "." + name` — no more hand-building each `CapabilityDescriptor`
- **New `RegnexeAgentBuilder` methods**: `withTool(Tool...)`, `withSkill(SkillConfig...)`, `withSubAgent(SubAgentConfig...)`, `withPlugin(PluginDescriptor...)` — none require constructing a `Marketplace` manually
- **`DefaultPluginManager`**: new `registerTool`/`registerSkill`/`registerSubAgent` for the code-first path; capabilityId defaults to the config's own `name`
- **`SimpleMarketplace.install()`**: now rejects duplicate `pluginId`/`capabilityId` instead of silently overwriting — `capabilityId` is echoed verbatim to the planner LLM for exact-match selection, so a silent collision must be surfaced, not swallowed

### 🏷️ `@AgentSkill` / `@AgentSubAgent` Annotations

- **`@AgentSkill`**: a pure marker — a Skill always inherits the parent model and never owns private tools, so the annotated class needs no methods
- **`@AgentSubAgent`**: reuses the existing `@AgentTool` scanner — any `@AgentTool` method on the class becomes that sub-agent's private `ownTool`, invisible to the outer agent
- **`DefaultPluginManager`**: `register(...)`/`scanPackages(...)` now auto-detect and dispatch all three annotations: `@Plugin`/`@AgentSkill`/`@AgentSubAgent`
- **Nested bundling**: `@AgentSkill`/`@AgentSubAgent` can nest as `public static` inner classes of a `@Plugin` class, sharing that plugin's `pluginId` with its `@AgentTool` methods — one `withPlugin(...)` call registers a tool, a skill, and a sub-agent together; they also still work standalone as their own single-capability plugin

### 🔗 Capability Tool-Dependency Management (allowedTools)

- **`CapabilityCandidate.allowedTools`**: new field on capability candidates
- **`TaskPlanner`**: automatically expands a selected capability's `allowedTools` dependencies into the execution plan, so a Skill/SubAgent's shared tool isn't missing just because it wasn't explicitly selected
- **`SimpleMarketplace`**: completes `allowedTools` read/maintenance logic
- **Dependency bump**: `j-langchain` upgraded to `1.0.18-SNAPSHOT`

### 📡 Event System: Filtering & Dispatch Refactor

- **`shouldHandle` + `dispatch`**: listeners can now selectively handle event types; all event producers switched to calling `dispatch` instead of `onEvent` directly
- **New event types**: `SEARCH_STARTED`, `PLAN_STARTED`, `EXECUTION_STARTED`, `REFLECTION_STARTED`, pairing with the existing `*_COMPLETED` events to cover both ends of each loop step
- **`AbstractEventListener`**: new base class providing shared token/LLM event filtering and event formatting (the `format` method moved up from `ConsoleEventListener`); `ConsoleEventListener` now extends it
- **Unified log prefixes**: e.g. `[Agent Start]`, `[Search Result]`, `[Plan Result]`, `[TOOL Call]`/`[TOOL Result]`, `[Reflect Result]`, `[Agent Done]`, `[Task Token Usage]`, consistent across listeners

### ⏯️ Task Execution & Resume Enhancements

- **`ToolExecutionRecord`**: records details of every tool call; `resumeMode` injects prior execution records (formatted via `ExecutionRecordFormatter`) into the resume prompt
- **`ResultStrategy`** (`RETURN_LAST` / `SYNTHESIZE`): `PlanOutput` gains a result strategy plus `finalAnswerRequirements`, controlling whether the executor returns the last tool result directly or synthesizes multiple observations
- **`TaskExecutionState.lastToolResult`**: tracks the most recent tool result; `Reflector` falls back to it when the LLM output is empty
- **`TaskRequest.displayGoal`**: session history now prefers the more readable `displayGoal`, falling back to the raw `goal` when unset
- **`TaskPlanner`**: historical messages are now real Human/AI message pairs instead of concatenated text, preventing the LLM from mimicking prior formatting; added unique-id and cross-check rules so planning doesn't re-select the same capability and execution results satisfy the goal's constraints
- **`CapabilityExecutor`**: new dedup mechanism prevents the same capability from being injected into the executor more than once
- Default context-output character limit raised from `800` to `2000`

### 📚 Docs & Examples

- **`docs/article/001/`**: 20 new core technical articles covering the Search→Plan→Execute→Reflect loop, the plugin marketplace, multi-capability orchestration, Skill/SubAgent layered abstraction, private capability integration, controlled tool calling, pause/resume, TaskStore persistence, session memory, event observability, result strategy, and enterprise governance
- **`README.md` / `README_zh.md`**: reorganized into an eight-part narrative matching the new capabilities — multi-tool quickstart → Skill/SubAgent → plugin concept & packaging → `@Plugin` and its annotation siblings (incl. package scan) → file-system directory loading → Marketplace → three-layer context memory → pause/resume; every code block links to a runnable `ExampleReadme*Test`
- **`Example00`–`Example08`**: getting started, weather forecast, contract analysis (Skill), travel planning (Sub-Agent), a combined business-trip scenario, three plugin-loading paths, session memory, pause/resume, and annotated Skill/SubAgent
- **`ExampleReadme01`–`ExampleReadme08`**: runnable examples mapping 1:1 to the README's eight sections

---

## 📋 Environment Variables

No new environment variables relative to 0.1.1.

---

## 🧪 Testing

- `withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)`: verified the `CapabilityDescriptor` type, id, and dependencies produced by each registration path
- `SimpleMarketplace` duplicate-id detection: verified duplicate `pluginId`/`capabilityId` registration throws instead of silently overwriting
- `@AgentSkill`/`@AgentSubAgent`: verified both standalone and nested-inside-`@Plugin` registration produce the correct capability type, and nested `@AgentTool` methods become private `ownTools`
- `allowedTools` auto-expansion: verified a selected Skill/SubAgent's dependent tools are injected into the executor automatically
- Event dispatch & filtering: verified `shouldHandle`/`dispatch` routing and that the new `*_STARTED` events fire at the expected points
- Task resume: verified prior execution records are correctly formatted and injected under `resumeMode`, and `ResultStrategy.RETURN_LAST` returns the last tool result directly
- Full `Example00`–`08` and `ExampleReadme01`–`08` suites pass against a real LLM

---

## 📦 Version

- Release: **0.1.2** (`master` is currently **0.1.1**)
- Dependency: `j-langchain 1.0.18-SNAPSHOT` (⚠️ see checklist below — confirm whether this should be a released version before publishing)
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [ ] `pom.xml` version bumped from `0.1.2-SNAPSHOT` to `0.1.2`
- [ ] `j-langchain` dependency confirmed as a released version (currently `1.0.18-SNAPSHOT` — a published release should not depend on a SNAPSHOT)
- [ ] `README.md` / `README_zh.md` dependency version strings updated to `0.1.2`
- [x] `PluginDescriptor`/`CapabilityDescriptor` custom builders complete; `tool()/skillConfig()/subAgentConfig()` behave as expected
- [x] `withTool`/`withSkill`/`withSubAgent`/`withPlugin(PluginDescriptor)` registration paths and `SimpleMarketplace` duplicate detection verified
- [x] `@AgentSkill`/`@AgentSubAgent` standalone and nested-bundling paths both verified
- [x] `allowedTools` auto-expansion verified
- [x] Event system `shouldHandle`/`dispatch`/`AbstractEventListener` refactor verified; log prefixes unified
- [x] Execution-record tracking, `resumeMode`, `ResultStrategy`, `displayGoal` verified
- [x] `docs/article` 20 articles complete; README's eight-part reorg matches the example suite
- [x] No breaking API changes (all additions are purely additive; no existing public API removed)
