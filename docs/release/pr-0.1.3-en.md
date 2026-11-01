# Pull Request: dev-0.1.3 → master

## Overview

Building on 0.1.2, improve marketplace loading, task context and recovery, plan validation, and event call trees. Target version: **0.1.3**, depending on **j-langchain 1.0.19**. Release preparation is in progress; publication has not occurred.

## Key Changes

- **Marketplace**: split annotation, manifest, and standalone Skill loaders; add cache installation/loading, enabled-state persistence, scope resolution, and improved capability ID collision checks. Support `plugin.yaml` and Claude-style `.claude-plugin/plugin.json`. USER / PROJECT have disk locations; LOCAL / MANAGED remain reserved scopes.
- **Skill execution**: support compatibility workspaces and direct `executeSkill(capabilityId, args, sessionId, displayGoal)` calls, bypassing Search / Plan / Reflect. This path creates no task ledger and cannot be paused through `pause()`.
- **Context and recovery**: retain execution steps and compacted summaries across rounds, improve round handoff, and resume the session's most recently updated non-FINISHED task with additional instructions.
- **Planning and reflection**: use JSON output mode, improve parse retries, iteration hints, historical context, and checks against actual execution; validate capability IDs.
- **Task checklist**: add model-driven `write_todos`, retained in task state across rounds. The framework does not automatically complete or enforce every item.
- **Observability**: support multiple listeners, Plan / Reflect model output and token events, and `spanId` / `parentSpanId` call trees.

## Breaking Changes and Migration

### Marketplace packages

Move `org.salt.regnexe.agent.core.market` to `core.marketplace`, with new subpackages:

| Types | New package (prefixed by `org.salt.regnexe.agent.core`) |
|---|---|
| `Marketplace`, `SimpleMarketplace` | `marketplace` |
| `CapabilityDescriptor`, `CapabilityType` | `marketplace.capability` |
| `PluginManager`, `DefaultPluginManager` | `marketplace.loader` |
| `Plugin`, `AgentSkill`, `AgentSubAgent`, `PluginDescriptor` | `marketplace.plugin` |

Update imports, custom implementations, and serialized type information; rebuild downstream applications.

### Planning and storage data

- Remove `ResultStrategy`, `PlanOutput.resultStrategy`, and `PlanOutput.reasoning`; add `iterationsHint`. Update custom planners, templates, and consumers to stop reading removed fields.
- Remove `TaskRequest.supplementInput`. Pass extra instructions through `resume(sessionId, supplementInput)`, which appends them to the goal.
- Follow j-langchain's `String` app/user/session IDs in `ConversationStorage`. Update custom signatures and keep persistent key conversion consistent.
- Persist the current `TaskExecutionState`, including `priorSteps`, `priorStepsSummary`, `lastToolResult`, and `todos`. Handle missing fields and type changes explicitly when migrating old data.

### Recovery and event behavior

- `TaskStore.listResumable(sessionId)` should return all non-`FINISHED` tasks, including RUNNING / PAUSED / FAILED / ESCALATED / TIMEOUT. Recovery selects the latest by `updatedAt`.
- `InMemoryTaskStore` cannot recover across process restarts; supply persistent storage. Resuming may repeat external tool calls, so handle idempotency in business integrations.
- `withEventListener(...)` now accumulates registrations. Multiple listeners receive events in registration order with runtime exception isolation; a single listener retains exception propagation.
- Event consumers can use span fields to build call trees and should accept manually created events without spans.

## Validation and Release Checklist

- [x] Set project version to `0.1.3` and dependency to `j-langchain 1.0.19`.
- [x] Update both READMEs and dependency examples in four platform introduction articles.
- [x] Both the original SNAPSHOT dependency and a temporary stable-dependency override passed 41 local tests without failures, errors, or skips; main, source, and Javadoc JARs generated.
- [x] Final POM verified with `mvn -o clean verify -Plocal-tests -Dgpg.skip=true`: 41 tests, no failures, errors, or skips; all three JARs generated.
- [ ] Accept real-model Search / Plan / Execute / Reflect, direct Skill execution, session memory, and pause/resume. The full suite has not been declared passing.
- [x] The user confirmed j-langchain publication; an independent request for its 1.0.19 POM on Maven Central returned HTTP 200.
- [ ] GPG signing was attempted, but the execution environment denied access to local `.gnupg` locks, trustdb, and gpg-agent. Complete this in a local terminal with access.
- [x] The user confirmed `flower-trees/regnexe-agent`; origin now points to `git@github.com:flower-trees/regnexe-agent.git`.
- [ ] Check regnexe-cli compatibility; commit, merge, tag, and publish.

Commands and requirements are in the [release validation guide (Chinese)](../guide/release-validation.md). Historical release notes retain their original versions and descriptions.

## Live API Checks in This Run

- `ExampleReadme01MultiToolTest` did not pass: direct Java requests could not resolve the DashScope host, and explicitly using the local proxy resulted in `SocketException: Operation not permitted`. The eventual TIMEOUT reflects exhausted rounds after network failures, not evidence of incorrect model execution or insufficient quota.
- Independent HTTPS probes with the same key returned HTTP 404 / `model_not_found` for the configured `deepseek-v4-flash`, and HTTP 200 with normal text output for `qwen-plus`.
- Subsequent probes confirmed that the official DeepSeek endpoint accepts both `deepseek-v4-flash` and `deepseek-chat`, returning HTTP 200 and normal text. Tests and documentation now use `Vendor.DEEPSEEK` with `deepseek-v4-flash`; independent subagent examples use `deepseek:deepseek-chat`, with `DEEPSEEK_KEY`. Full Java integration acceptance still requires network access; API probes alone do not establish acceptance.
