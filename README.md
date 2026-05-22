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

Most LLM integrations stop at a single tool call. Regnexe runs a full **Search → Plan → Execute → Reflect** loop — selecting the right tools, replanning across multiple rounds, and adapting until the goal is met or it hands back to you.

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
    │   @Plugin bean                │
    │   package scan                │
    │   file-system directory       │
    │   programmatic (DB / API / …) │
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
- 🏢 **Enterprise-grade** — session memory, state persistence, event hooks, zero Spring intrusion

---

## Quick Start

### 1. Add dependency

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure your LLM

```yaml
# application.yml — choose one
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}      # Aliyun DashScope (deepseek-v4-flash, qwen, etc.)
  deepseek:
    chat-key: ${DEEPSEEK_KEY}    # DeepSeek official API
```

### 3. Write a plugin and run

```java
// Annotate your plugin — two annotations, that's it
@Plugin(id = "weather", name = "Weather Plugin", description = "Weather queries")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city, including temperature and activity advice.")
    public String getWeather(String city) {
        return "Beijing: Sunny, 22°C, excellent air quality. Great day for running.";
    }
}
```

```java
@Autowired
RegnexeAgentBuilder regnexeAgentBuilder;

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())               // ← one line to load a plugin
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's Beijing weather. Is it good for running?");

System.out.println(result.getFinalText());         // FINISHED
```

No `@EnableXxx`. No XML. `RegnexeAgentBuilder` is auto-configured by Spring Boot.

---

## Plugin System

All plugins are loaded through the **Plugin Marketplace**. Each loaded plugin exposes one or more **capabilities** — regardless of source, every capability is represented as a `CapabilityDescriptor` that the agent's Search and Execute steps treat uniformly. This abstraction is what lets you mix Java beans, scanned classes, shell scripts, and database-driven definitions in the same agent without any special handling.

### Four loading methods

**Method 1 — Bean registration** (best for Spring services or quick prototyping)

```java
// Shortcut: builder handles the marketplace automatically
regnexeAgentBuilder
    .withPlugin(new WeatherPlugin(), mySpringBean)  // varargs, any @Plugin object
    ...

// Equivalent explicit form
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(new DefaultPluginManager().register(new WeatherPlugin()));
regnexeAgentBuilder.withPluginMarket(marketplace) ...
```

**Method 2 — Package scan** (best for large plugin libraries)

```java
// Shortcut: auto-discovers all @Plugin classes in the given packages
regnexeAgentBuilder
    .withScanPackages("com.example.plugins")
    ...

// Equivalent explicit form
SimpleMarketplace marketplace = new SimpleMarketplace();
DefaultPluginManager mgr = new DefaultPluginManager();
mgr.scanPackages("com.example.plugins");
marketplace.load(mgr);
regnexeAgentBuilder.withPluginMarket(marketplace) ...
```

**Method 3 — File-system directory** (best for ops-managed, hot-pluggable plugins)

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
// Shortcut: scans the directory and loads all valid plugin folders
regnexeAgentBuilder
    .withDirectory("/opt/regnexe-plugins")
    ...

// Equivalent explicit form
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(new DefaultPluginManager().addDirectory("/opt/regnexe-plugins"));
regnexeAgentBuilder.withPluginMarket(marketplace) ...
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

**Method 4 — Programmatic / dynamic** (best for DB-driven or runtime-generated plugins)

When plugins come from a database, remote config, or need custom loading logic, build `CapabilityDescriptor` objects directly and install them into the marketplace:

```java
// Build capabilities from any source — DB, remote config, runtime logic
CapabilityDescriptor cap = CapabilityDescriptor.builder()
    .capabilityId("db-weather.get_weather")
    .pluginId("db-weather")
    .type(CapabilityType.MCP_TOOL)
    .name("get_weather")
    .description("Get today's weather for a city.")
    .tool(Tool.builder()
        .name("get_weather")
        .description("Get today's weather")
        .params("city: String -- city name")
        .func(city -> callWeatherApi(city.toString()))
        .build())
    .build();

PluginDescriptor plugin = PluginDescriptor.builder()
    .pluginId("db-weather")
    .name("DB Weather Plugin")
    .version("1.0")
    .capabilities(List.of(cap))
    .build();

SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.install(plugin);

regnexeAgentBuilder
    .withPluginMarket(marketplace)
    ...
```

For even more control, implement `PluginManager` directly and call `marketplace.load(yourManager)`.

### Capability types

Every plugin capability — regardless of how it was loaded — is exposed to the agent as a `CapabilityDescriptor`. The agent's Search step selects relevant descriptors by name and description; the Execute step instantiates the actual capability on demand. Three types are supported:

| Type | What it is | When to use |
|------|-----------|-------------|
| **MCP Tool** | Single callable function | Simple lookups, API calls |
| **Skill** | Nested agent — own system prompt + inner tools | Multi-step domain tasks |
| **Sub-Agent** | Fully autonomous agent with its own reasoning loop | Complex independent sub-tasks |

Skills and Sub-Agents store their configuration (`SkillConfig` / `SubAgentConfig`) in the descriptor and are built lazily at execution time — no LLM dependency at load time.

<details>
<summary>Skill and Sub-Agent code example</summary>

```java
// Skill — nested agent with inner tools
Skill contractSkill = Skill.from(
    SkillConfig.builder()
        .name("contract_analyzer")
        .description("Legal risk analysis of contract clauses. TRIGGER: use when analyzing contracts.")
        .systemPrompt("You are a contract risk analyst. Call analyze_clause for each clause, then summarize.")
        .build(), chainActor)
    .llm(llm).tools(analyzeClauseTool).build();

// Sub-Agent — autonomous with its own reasoning loop
SubAgent travelAgent = SubAgent.from(
    SubAgentConfig.builder()
        .name("travel_planner")
        .description("Business trip planner. TRIGGER: use when planning a trip itinerary.")
        .systemPrompt("You are a business travel planner. Query attractions and restaurants, then output a 3-day itinerary.")
        .build(), chainActor)
    .llm(llm).tools(attractionsTool, restaurantsTool).build();

// The master agent selects and composes all three automatically
AgentResult result = agent.execute(
    "3-day Chengdu business trip: check weather, analyze reimbursement policy risks, plan itinerary."
);
```

</details>

---

## Pause & Resume

```java
// Run in background
Future<AgentResult> future = executor.submit(() -> agent.execute(request));

// Pause from any thread (thread-safe)
agent.pause();
AgentResult paused = future.get();   // status == PAUSED

// Resume with supplemental context
AgentResult done = agent.resume(sessionId, "Also factor in today's air quality index.");
// done.getStatus() == FINISHED
```

> Requires `withTaskStore(taskStore)` to persist the paused state.

---

## Session Memory

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withSessionStorage(new InMemoryConversationStorage())
    .build();

// Turn 1
agent.execute(request("session-123", "Check today's weather in Beijing."));

// Turn 2 — recalls prior context, no redundant tool calls
agent.execute(request("session-123", "Based on that, what should I wear today?"));
```

---

## Reference

<details>
<summary>Builder options</summary>

| Method | Default | Description |
|--------|---------|-------------|
| `withDefaultModel(Vendor, String)` | — | LLM vendor + model name |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | Custom LLM provider |
| `withPlugin(Object...)` | — | Register `@Plugin` beans (creates marketplace automatically) |
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
