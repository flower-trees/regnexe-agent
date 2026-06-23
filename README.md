<p align="center">
  <h1 align="center">⚡ Regnexe</h1>
  <p align="center"><b>One Regnexe to Rule All Tasks</b></p>
  <p align="center">Java Enterprise Agent Framework — plug in, reason, ship.</p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x"/>
</p>

---

Most LLM integrations stop at a single tool call. Regnexe runs a full **Search → Plan → Execute → Reflect** loop — selecting the right capabilities, replanning across multiple rounds, and adapting until the goal is met or it hands back to you.

```
User Goal
    │
    ▼
[Search → Plan → Execute → Reflect] × N rounds → AgentResult
                    │
                    ▼
          Plugin Marketplace
    ┌───────────────────────────────┐
    │  Loading channels:            │
    │   code-first (tool/skill/     │
    │   subagent) · @Plugin bean ·  │
    │   package scan · file dir     │
    │               ↓               │
    │  CapabilityDescriptor         │  ← unified capability API
    │  ┌─────────┬───────┬────────┐ │
    │  │MCP Tool │ Skill │SubAgent│ │
    │  └─────────┴───────┴────────┘ │
    └───────────────────────────────┘
```

**What sets it apart:**
- 🔄 **Multi-round reasoning** — replans and retries, not just one tool call
- ⏸ **Pause & Resume** — interrupt from any thread, continue with new context
- 🧩 **Plugin marketplace** — add/remove capabilities without touching agent code
- 🏢 **Enterprise-grade** — session memory, task ledger, swappable context strategy, zero Spring intrusion

This README goes from "one tool call" to the full framework, one layer at a time. Every code block below is a real, runnable test under [`src/test/java/.../example/readme/ExampleReadme*Test.java`](src/test/java/org/salt/regnexe/agent/core/example/readme).

---

## 1. Quick Start: multiple tools, one loop

### Add dependency

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Configure your LLM

```yaml
# application.yml — choose one
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}      # Aliyun DashScope (deepseek-v4-flash, qwen, etc.)
  deepseek:
    chat-key: ${DEEPSEEK_KEY}    # DeepSeek official API
```

### Register tools and run

`withTool(...)` registers pre-built `Tool` objects directly — no class, no annotation, fastest path to a running agent. See [`ExampleReadme01MultiToolTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java).

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String -- city name")
    .func(city -> "Beijing: sunny, 22 C.")
    .build();

Tool airQualityTool = Tool.builder()
    .name("get_air_quality")
    .description("Get today's air quality index (AQI) for a city.")
    .params("city: String -- city name")
    .func(city -> "Beijing: AQI 35, excellent air quality.")
    .build();

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool, airQualityTool)          // ← varargs, register as many as you need
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

System.out.println(result.getFinalText());          // FINISHED
```

No `@EnableXxx`. No XML. `RegnexeAgentBuilder` is auto-configured by Spring Boot.

`ConsoleEventListener` prints every step of the loop — Search finds candidates, Plan picks and orders them, Execute calls the tools, Reflect decides whether to finish:

```
[Agent Start   ] R0 Goal: Check today's weather and air quality in Beijing... | maxRounds: 3
[Search Result ] R1 Found 2 capabilities: get_weather, get_air_quality
[Plan Result   ] R1 Selected: [get_weather, get_air_quality] | Strategy: SYNTHESIZE | ...
[TOOL Call     ] R1 get_weather {"city": "Beijing"}
[TOOL Result   ] R1 get_weather -> Beijing: sunny, 22 C.
[TOOL Call     ] R1 get_air_quality {"city": "Beijing"}
[TOOL Result   ] R1 get_air_quality -> Beijing: AQI 35, excellent air quality.
[Execute Result] R1 SUCCESS | Sunny, 22°C, AQI 35 — great conditions for a run.
[Reflect Result] R1 FINISH — both readings obtained and the goal is fully answered.
[Agent Done    ] R1 Status: FINISHED | Rounds: 1
```

---

## 2. Going deeper: Skill vs Sub-Agent

A single tool call only goes so far. Two richer capability types let you compose multi-step behavior, and they make opposite tradeoffs on purpose.

### Skill — shares the parent's model and tools

`SkillConfig` has no model field at all: a Skill **always inherits the parent agent's model**, and its `allowedTools` are capability ids that must already exist in the marketplace — it borrows, it doesn't own. Use a Skill for a focused, repeatable sub-workflow that should stay cheap and stay in lockstep with the main agent's model. See [`ExampleReadme02SkillTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java).

```java
SkillConfig travelAdvisor = SkillConfig.builder()
    .name("travel_advisor")
    .description("Calls get_weather for the city the user mentions and gives outdoor-activity advice based on the current weather. " +
                 "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
    .systemPrompt("""
            You are an outdoor-activity advisor.
            1. Call get_weather for the city the user mentions.
            2. Based on the result, give a short, direct go/no-go recommendation.
            """)
    .allowedTools(List.of("get_weather"))   // borrowed by id, not owned
    .build();

RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withTool(weatherTool)     // the tool a Skill borrows must already be in the marketplace
    .withSkill(travelAdvisor)
    .build();
```

### Sub-Agent — owns its own model and private tools

`SubAgentConfig.model(...)` can name a *different* model than the parent (or `"inherit"` to share it), and `ownTools` are **private** — never registered in the marketplace, so the outer agent can never call them directly. Use a Sub-Agent for an independent sub-task that needs its own reasoning loop, its own tools, or a cheaper/faster model. See [`ExampleReadme03SubAgentTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java).

```java
SubAgentConfig expenseEstimator = SubAgentConfig.builder()
    .name("expense_estimator")
    .description("Estimates the total cost of a business trip. " +
                 "TRIGGER: Use when the user asks for a trip budget or cost estimate.")
    .model("aliyun:qwen-plus")        // its own model, independent of the parent's default model
    .systemPrompt("""
            You are a travel expense estimator.
            1. Call estimate_trip_cost with the trip length and destination.
            2. Report the total and a one-line breakdown.
            """)
    .ownTools(List.of(estimateCostTool))   // private — invisible to the outer agent
    .build();

RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withSubAgent(expenseEstimator)
    .build();
```

### Which one?

| | Skill | Sub-Agent |
|---|---|---|
| Model | Always inherits the parent's | Own model, or `"inherit"` |
| Tools | Borrowed by capability id (`allowedTools`) | Private (`ownTools`), invisible outside |
| Best for | Cheap, repeatable sub-workflows tightly coupled to the main agent | Independent sub-tasks that need isolation or a different model |

---

## 3. Plugins: concept and packaging

A plugin is a named, versioned, taggable bundle of one or more capabilities — tools, skills, and sub-agents alike — sharing a single `pluginId`. Every loading channel in this README (code-first, `@Plugin` annotations, package scan, file-system directories) ultimately builds the same thing: a `PluginDescriptor` holding one or more `CapabilityDescriptor`s. The most explicit way to build one by hand is `PluginDescriptor.builder()`, which has `tool(...)`, `skillConfig(...)`, and `subAgentConfig(...)` — each wraps the raw config into a `CapabilityDescriptor` automatically, id'd as `pluginId + "." + name`. One call bundles a whole mixed-type plugin instead of hand-building each `CapabilityDescriptor` separately. See [`ExampleReadme05PluginPackagingTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme05PluginPackagingTest.java).

```java
PluginDescriptor tripPlugin = PluginDescriptor.builder()
    .pluginId("trip-plugin")
    .version("1.0")
    .name("Trip Plugin")
    .description("Bundles a tool, a skill, and a sub-agent for trip planning")
    .tool(weatherTool)                                    // -> trip-plugin.get_weather
    .skillConfig(travelAdvisor)                           // -> trip-plugin.travel_advisor
    .subAgentConfig(expenseEstimator)                      // -> trip-plugin.expense_estimator
    .build();

regnexeAgentBuilder.withPlugin(tripPlugin) ...
```

> A Skill's `allowedTools` must reference the tool's *full* capability id. If the tool and the skill share a `pluginId` here, that id is `"trip-plugin.get_weather"`, not bare `"get_weather"`.

---

## 4. `@Plugin` and its annotation siblings

For Java classes, annotations build the same `PluginDescriptor` without manual `.tool()`/`.skillConfig()` calls. The two getting-started tools become `@AgentTool` methods on one `@Plugin` class. `@AgentSkill` and `@AgentSubAgent` — the same Skill and Sub-Agent from section 2, as annotations instead of `SkillConfig`/`SubAgentConfig` builders — nest as `public static` inner classes of that same `@Plugin` class, bundling everything under one `pluginId`: one `withPlugin(new WeatherPlugin())` call registers two tools, a skill, and a sub-agent at once. `@AgentSkill` is a pure marker (a Skill never owns tools, so no methods needed); `@AgentSubAgent` reuses `@AgentTool` for its private `ownTools`, exactly like the outer `@Plugin` does for MCP_TOOL. See [`ExampleReadme04PluginAnnotationTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java).

```java
@Plugin(id = "weather", name = "Weather Plugin",
        description = "Weather, air quality, travel advice, and trip cost estimation")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city.")
    public String getWeather(String city) {
        return "Beijing: sunny, 22 C.";
    }

    @AgentTool("Get today's air quality index (AQI) for a city.")
    public String getAirQuality(String city) {
        return "Beijing: AQI 35, excellent air quality.";
    }

    @AgentSkill(
        id = "travel_advisor",
        description = "Gives outdoor-activity advice based on the current weather for a city. " +
                       "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.",
        systemPrompt = """
                You are an outdoor-activity advisor.
                1. Call get_weather for the city the user mentions.
                2. Based on the result, give a short, direct go/no-go recommendation.
                """,
        allowedTools = {"weather.get_weather"}   // full capability id within this plugin
    )
    public static class TravelAdvisorSkill {
        // No @AgentTool methods — a Skill can't own private tools.
    }

    @AgentSubAgent(
        id = "expense_estimator",
        description = "Estimates the total cost of a business trip. " +
                       "TRIGGER: Use when the user asks for a trip budget or cost estimate.",
        model = "aliyun:qwen-plus",
        systemPrompt = """
                You are a travel expense estimator.
                1. Call estimate_trip_cost with the trip length and destination.
                2. Report the total and a one-line breakdown.
                """
    )
    public static class ExpenseEstimatorSubAgent {

        @AgentTool("Estimates total cost for a multi-day business trip.")
        public String estimateTripCost(int days, String city) {
            return "3-day Chengdu trip estimate: 3600 CNY total.";
        }
    }
}
```

```java
AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .build()
    .execute("Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");
```

`@AgentSkill`/`@AgentSubAgent` also work standalone (not nested) — `withPlugin(new TravelAdvisorSkill())` on its own registers it as its own single-capability plugin, the same way the code-first `withSkill(SkillConfig)`/`withSubAgent(SubAgentConfig)` from section 2 do.

### Package scan

Auto-discover `@Plugin`/`@AgentSkill`/`@AgentSubAgent` classes on the classpath instead of constructing them by hand (best for large plugin libraries):

```java
regnexeAgentBuilder.withScanPackages("com.example.plugins") ...
```

---

## 5. File-system directory loading

Best for ops-managed, hot-pluggable plugins — no annotations or code at all, just files on disk:

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml              ← metadata
    tools/
      get_weather.sh         ← .sh / .py / .groovy script
      get_weather.yaml       ← sidecar: description + params
    skills/
      advisor/SKILL.md       ← skill with system prompt
    subagents/
      planner/AGENT.md       ← autonomous sub-agent
```

```java
regnexeAgentBuilder.withDirectory("/opt/regnexe-plugins") ...
```

Add or remove plugin folders — no code changes required.

<details>
<summary>Directory file format reference</summary>

**`plugin.yaml`**
```yaml
pluginId: weather-plugin
name: Weather Plugin
version: 1.0
description: Directory-loaded weather plugin
tags: [weather]
```

**`tools/get_weather.yaml`** (same base name as the script)
```yaml
description: "Get today's weather for a city"
params: "city: String -- city name"
tags: [weather]
```

**`skills/advisor/SKILL.md`**
```markdown
---
name: advisor
description: "Outdoor activity advisor. TRIGGER: when user asks about outdoor plans."
---
You are a weather advisor. Given weather data, recommend practical outdoor activities.
```

**`subagents/planner/AGENT.md`**
```markdown
---
name: planner
description: "Outdoor activity planner sub-agent. TRIGGER: when planning a full itinerary."
---
You are an outdoor activity planner. Use weather and user needs to produce a detailed plan.
```

</details>

---

## 6. Marketplace

Every loading channel above ends the same way: capabilities land in a `Marketplace`. The default `SimpleMarketplace` is an in-memory index — install, search, resolve. See [`ExampleReadme06MarketplaceTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java).

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.install(PluginDescriptor.builder()
    .pluginId("weather-plugin").version("1.0")
    .name("Weather Plugin").description("Weather queries")
    .tool(weatherTool)
    .build());

CapabilitySearchResult result = marketplace.search(searchQuery);   // candidates for the Planner
CapabilityDescriptor cap = marketplace.resolveDescriptor("weather-plugin.get_weather");
```

`Marketplace` is just an interface — implement your own (DB-backed, ES-backed, tenant-aware, semantic search...) and pass it to `withPluginMarket(...)`. No other agent code changes:

```java
class DbBackedMarketplace implements Marketplace {
    // install()/uninstall()/enable()/disable()/search()/resolveDescriptor()/listEnabled()
    // backed by a JPA repository or JDBC template instead of a Map.
    // Add whatever extra query methods your ops/admin tooling needs (e.g. findByTag).
}

regnexeAgentBuilder.withPluginMarket(new DbBackedMarketplace()) ...
```

---

## 7. Three layers of context memory

Three independent, independently-replaceable layers, each solving a different problem. See [`ExampleReadme07ThreeLayerMemoryTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme07ThreeLayerMemoryTest.java).

| Layer | Question it answers | Config knob | Default |
|---|---|---|---|
| Session memory | "What did this user say in earlier tasks?" | `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` |
| Task ledger | "What happened, round by round, in this `execute()`/`resume()`?" | `withTaskStore(TaskStore)` | `InMemoryTaskStore` |
| Agent context | "How much history does one tool-calling loop carry?" | `withAgentContext(AgentContext)` | `FullContext` (no compression) |

**Session memory** — cross-task history keyed by `sessionId`:

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool)
    .withSessionStorage(new InMemoryConversationStorage())
    .build();

agent.execute(request("session-123", "Check today's weather in Beijing."));
agent.execute(request("session-123", "Based on that, what should I wear today?"));  // recalls prior context
```

**Task ledger** — every round's Search/Plan/Execute/Reflect result, persisted for audit or resume:

```java
InMemoryTaskStore taskStore = new InMemoryTaskStore();
RegnexeAgent agent = regnexeAgentBuilder.withTool(weatherTool).withTaskStore(taskStore).build();

AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");
TaskExecutionState ledger = taskStore.load(result.getTaskId()).orElseThrow();
ledger.getRounds().forEach(r -> System.out.println(r.getRoundNumber() + ": " + r.getReflection()));
```

**Agent context** — swap the default unbounded `FullContext` for a bounded strategy when tool-calling traces get long:

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool)
    .withAgentContext(SlidingWindowContext.builder().windowSize(5).build())
    .build();
```

---

## 8. Pause & Resume

`pause()` is thread-safe and can be called from any thread while a task is running. The task transitions to `PAUSED` and persists in the configured `TaskStore`; `resume()` picks up the most recent resumable task for that session and continues with extra context. See [`ExampleReadme08PauseResumeTest`](src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme08PauseResumeTest.java).

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withTool(weatherTool)
    .withTaskStore(new InMemoryTaskStore())   // required: pause/resume needs persisted state
    .build();

Future<AgentResult> future = executor.submit(() -> agent.execute(request));

agent.pause();                       // safe to call from any thread
AgentResult paused = future.get();   // status == PAUSED

AgentResult done = agent.resume(sessionId, "Also factor in today's air quality index.");
// done.getStatus() == FINISHED
```

---

## Reference

<details>
<summary>Builder options</summary>

| Method | Default | Description |
|--------|---------|--------------|
| `withDefaultModel(Vendor, String)` | — | LLM vendor + model name |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | Custom LLM provider |
| `withTool(Tool...)` | — | Register pre-built tools as MCP_TOOL capabilities (creates marketplace automatically) |
| `withSkill(SkillConfig...)` | — | Register SKILL capabilities directly (creates marketplace automatically) |
| `withSubAgent(SubAgentConfig...)` | — | Register SUB_AGENT capabilities directly (creates marketplace automatically) |
| `withPlugin(Object...)` | — | Register `@Plugin` beans (creates marketplace automatically) |
| `withPlugin(PluginDescriptor...)` | — | Install pre-built `PluginDescriptor` objects (creates marketplace automatically) |
| `withScanPackages(String...)` | — | Scan packages for `@Plugin` classes (creates marketplace automatically) |
| `withDirectory(String...)` | — | Load plugins from file-system directories (creates marketplace automatically) |
| `withPluginMarket(Marketplace)` | empty marketplace | Full marketplace control |
| `withMaxRounds(int)` | `10` | Max reasoning iterations |
| `withEventListener(AgentEventListener)` | no-op | Hook for LLM output, tool calls, results |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | State persistence for pause/resume |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | Final answer assembly strategy |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | Cross-turn memory |
| `withSessionBufferSize(int)` | `10` | Messages before summarization triggers |
| `withAgentContext(AgentContext)` | `FullContext` | Context window strategy |

</details>

<details>
<summary>Supported LLM vendors</summary>

| Enum | Provider | Env var |
|------|----------|---------|
| `Vendor.ALIYUN` | Alibaba Cloud DashScope | `ALIYUN_KEY` |
| `Vendor.DEEPSEEK` | DeepSeek | `DEEPSEEK_KEY` |
| `Vendor.DOUBAO` | ByteDance Doubao | `DOUBAO_KEY` |
| `Vendor.HUNYUAN` | Tencent Hunyuan | `HUNYUAN_KEY` |
| `Vendor.LINGYI` | 01.AI | `LINGYI_KEY` |
| `Vendor.MINIMAX` | MiniMax | `MINIMAX_KEY` |
| `Vendor.MOONSHOT` | Moonshot (Kimi) | `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama (local) | `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | Baidu Qianfan | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | StepFun | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | Zhipu AI (GLM) | `ZHIPU_KEY` |

All API endpoint URLs are pre-configured. Set the env var or add `chat-key` under the matching `models.*` prefix.

</details>

<details>
<summary>Task status</summary>

| Status | Meaning |
|--------|---------|
| `FINISHED` | Goal completed |
| `PAUSED` | Interrupted by `pause()`, resumable |
| `TIMEOUT` | Hit `maxRounds` limit |
| `ESCALATED` | Reflector flagged for human review |
| `FAILED` | Unrecoverable error |

</details>

---

If Regnexe saves you time, a ⭐ on GitHub goes a long way.  
For enterprise integrations or custom requirements, open an issue or reach out directly.

[中文文档](README_zh.md) · [License](LICENSE)
