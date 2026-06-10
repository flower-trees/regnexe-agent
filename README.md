<p align="center">
  <h1 align="center">⚡ Regnexe</h1>
  <p align="center"><b>Multi-round Agent orchestration for Java and Spring Boot</b></p>
  <p align="center">Search, plan, execute, reflect, and compose enterprise workflows from tools, skills, and sub-agents.</p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2%2B-green" alt="Spring Boot 3.2+"/>
</p>

---

Regnexe is not a thin wrapper around one LLM tool call. It runs a full **Search -> Plan -> Execute -> Reflect** loop: discover relevant capabilities, plan the next step, execute tools or nested agents, then decide whether to finish, retry, continue, pause, or escalate.

```
User Goal
    |
    v
[Search -> Plan -> Execute -> Reflect] x N rounds
    |
    v
AgentResult

Marketplace
    |
    +-- @Plugin beans
    +-- package scan
    +-- file-system plugin directories
    +-- programmatic descriptors from DB/API/runtime config
    |
    v
CapabilityDescriptor
    |
    +-- MCP_TOOL   single callable function or script
    +-- SKILL      nested agent with its own prompt and private tools
    +-- SUB_AGENT  autonomous agent with its own reasoning loop
```

## Highlights

- **Multi-round reasoning**: replans from intermediate results instead of stopping after one call.
- **Unified marketplace**: Java beans, scanned classes, script directories, and dynamic definitions all become `CapabilityDescriptor` objects.
- **Private tools for nested agents**: attach `ownTools` to a Skill or Sub-Agent without exposing those tools to the master agent.
- **Per-capability model control**: Sub-Agents can inherit the parent model or use their own model with vendor-specific kwargs.
- **Built-in observability**: event hooks for outer-loop phases, LLM responses, tool calls, per-capability token usage, and task-level token summaries.
- **Enterprise extension points**: pause/resume, task persistence, session memory, custom result composers, custom model providers, and Spring Boot auto-configuration.

## Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Plugin Loading](#plugin-loading)
- [Sub-Agent Model Control](#sub-agent-model-control)
- [Events and Token Tracking](#events-and-token-tracking)
- [Pause and Resume](#pause-and-resume)
- [Session Memory](#session-memory)
- [Reference](#reference)

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.1</version>
</dependency>
```

### 2. Configure model keys

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
```

Endpoint URLs are preconfigured. You can provide a vendor explicitly or pass a model name and let `DefaultModelProvider` route by model prefix.

### 3. Define a plugin

```java
@Plugin(id = "weather", name = "Weather Plugin", description = "Weather queries")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city, including temperature and activity advice.")
    public String getWeather(String city) {
        return "Beijing: sunny, 22°C, excellent air quality. Good for outdoor running.";
    }
}
```

### 4. Build and execute

```java
@Autowired
RegnexeAgentBuilder regnexeAgentBuilder;

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("Check today's Beijing weather. Is it good for running?");

System.out.println(result.getStatus());
System.out.println(result.getFinalText());
```

No `@EnableXxx` and no XML are required. `RegnexeAgentBuilder`, `TaskStore`, and `ResultComposer` are auto-configured by Spring Boot.

## Core Concepts

| Component | Role |
|-----------|------|
| `RegnexeAgent` | Runs the Search -> Plan -> Execute -> Reflect loop |
| `Marketplace` | Stores installed plugins and their capabilities |
| `CapabilityDescriptor` | Common descriptor used by search, planning, and execution |
| `ModelProvider` | Creates LLM instances from `ModelSpec` |
| `AgentEventListener` | Streams execution, LLM, tool, and token events |

### Capability types

| Type | What it is | Use it for |
|------|------------|------------|
| `MCP_TOOL` | One callable function or script | Lookups, calculations, API calls, business actions |
| `SKILL` | Nested agent with a system prompt and optional private tools | Domain tasks such as contract review or report generation |
| `SUB_AGENT` | Autonomous nested agent with its own reasoning loop | Complex subtasks that can plan and call tools independently |

Skill and Sub-Agent descriptors store `SkillConfig` or `SubAgentConfig` and are instantiated lazily during execution, so plugin discovery does not require an LLM.

## Plugin Loading

All capabilities are loaded through the marketplace. Use the builder shortcuts for common cases, or install descriptors yourself when plugins come from dynamic sources.

### Bean registration

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin(), mySpringBean)
    .build();
```

Equivalent explicit form:

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(new DefaultPluginManager().register(new WeatherPlugin()));

RegnexeAgent agent = regnexeAgentBuilder
    .withPluginMarket(marketplace)
    .build();
```

### Package scan

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withScanPackages("com.example.plugins")
    .build();
```

Scanned plugin classes must be instantiable, for example by exposing a public no-argument constructor.

### File-system directory

Use directory plugins for ops-managed, hot-pluggable tools, Skills, and Sub-Agents.

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml
    tools/
      get_weather.sh
      get_weather.yaml
    skills/
      advisor/
        SKILL.md
    subagents/
      planner/
        AGENT.md
```

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withDirectory("/opt/regnexe-plugins")
    .build();
```

<details>
<summary>Directory format</summary>

**`plugin.yaml`**

```yaml
pluginId: weather-plugin
name: Weather Plugin
version: 1.0
description: Directory-loaded weather plugin
tags: [weather]
```

**`tools/get_weather.yaml`**

```yaml
description: "Get today's weather for a city"
params: "city: String -- city name"
tags: [weather]
```

**`skills/advisor/SKILL.md`**

```markdown
---
name: advisor
description: "Outdoor activity advisor. TRIGGER: when the user asks about outdoor plans."
---
You are a weather advisor. Given weather data, recommend practical outdoor activities.
```

**`subagents/planner/AGENT.md`**

```markdown
---
name: planner
description: "Outdoor activity planner. TRIGGER: when planning a full itinerary."
---
You are an outdoor activity planner. Use weather and user needs to produce a detailed plan.
```

</details>

### Programmatic descriptors

Use this path when capabilities come from a database, remote config, or runtime generation.

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city.")
    .params("city: String -- city name")
    .func(city -> callWeatherApi(city.toString()))
    .build();

CapabilityDescriptor cap = CapabilityDescriptor.builder()
    .capabilityId("db-weather.get_weather")
    .pluginId("db-weather")
    .type(CapabilityType.MCP_TOOL)
    .name("get_weather")
    .description("Get today's weather for a city.")
    .tags(List.of("weather"))
    .tool(weatherTool)
    .build();

PluginDescriptor plugin = PluginDescriptor.builder()
    .pluginId("db-weather")
    .name("DB Weather Plugin")
    .version("1.0")
    .capabilities(List.of(cap))
    .build();

SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.install(plugin);

RegnexeAgent agent = regnexeAgentBuilder
    .withPluginMarket(marketplace)
    .build();
```

## Sub-Agent Model Control

In 0.1.1, a Sub-Agent can either inherit the parent agent's LLM or request its own model. Capability-level `modelKwargs` are forwarded to the LLM builder for vendor-specific options such as `temperature` or `thinking`.

```java
Tool attractionsTool = Tool.builder()
    .name("get_attractions")
    .description("Get attractions by theme.")
    .params("theme: String -- culture/nature/business")
    .func(theme -> lookupAttractions(theme.toString()))
    .build();

SubAgentConfig plannerConfig = SubAgentConfig.builder()
    .name("travel_planner")
    .description("Business trip planner. TRIGGER: use when planning travel itineraries.")
    .systemPrompt("Plan a practical business trip itinerary. Call get_attractions before finalizing.")
    .model("aliyun:qwen-max")
    .inheritModel(false)
    .build();

CapabilityDescriptor planner = CapabilityDescriptor.builder()
    .capabilityId("travel.travel_planner")
    .pluginId("travel")
    .type(CapabilityType.SUB_AGENT)
    .name("travel_planner")
    .description("Plans business trip itineraries with attraction lookup.")
    .subAgentConfig(plannerConfig)
    .ownTools(List.of(attractionsTool))
    .modelKwargs(Map.of("temperature", 0.3))
    .build();
```

`ownTools` are private to that Skill or Sub-Agent. They are injected into the nested executor but are not exposed as marketplace capabilities for the master agent to call directly.

## Events and Token Tracking

Register an `AgentEventListener` to stream runtime events to logs, SSE, tracing systems, or metrics pipelines.

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withEventListener(event -> {
        if (event.getType() == EventType.TASK_TOKEN_SUMMARY) {
            metrics.record(event.getText());
        }
    })
    .build();
```

The builder automatically wraps your listener with `TokenAggregatingEventListener`. It collects outer-loop `TOKEN_USAGE` and nested `CAPABILITY_TOKEN_USAGE`, then emits one `TASK_TOKEN_SUMMARY` before `AGENT_COMPLETED`.

Important event types:

| Event | Meaning |
|-------|---------|
| `PLAN_LLM_RESPONDED` | Planner LLM output |
| `REFLECT_LLM_RESPONDED` | Reflector LLM output |
| `SKILL_LLM_RESPONDED` | Skill inner LLM output |
| `AGENT_LLM_RESPONDED` | Sub-Agent inner LLM output |
| `TOKEN_USAGE` | Parent agent LLM token event |
| `CAPABILITY_TOKEN_USAGE` | Skill/Sub-Agent token event, attributed to capability name |
| `TASK_TOKEN_SUMMARY` | Aggregated total, per-model usage, elapsed time, and LLM time |

## Pause and Resume

```java
Future<AgentResult> future = executor.submit(() -> agent.execute(request));

agent.pause();
AgentResult paused = future.get();   // status == PAUSED

AgentResult done = agent.resume(
    paused.getState().getSessionId(),
    "Also factor in today's air quality index."
);
```

Use `withTaskStore(taskStore)` when paused state must survive outside the default in-memory store.

## Session Memory

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withSessionStorage(new InMemoryConversationStorage())
    .withSessionBufferSize(10)
    .build();

agent.execute(request("session-123", "Check today's weather in Beijing."));
agent.execute(request("session-123", "Based on that, what should I wear today?"));
```

You can also provide a custom `ConversationMemory` with `withSessionMemory(memory)`. The supplied memory instance should be scoped to one session and should not be shared across concurrent executions.

## Reference

<details>
<summary>Builder options</summary>

| Method | Default | Description |
|--------|---------|-------------|
| `withDefaultModel(String)` | - | Default model, vendor inferred by model prefix |
| `withDefaultModel(String, String)` | - | Default vendor and model name |
| `withDefaultModel(Vendor, String)` | - | Type-safe vendor and model name |
| `withDefaultModel(BaseChatModel)` | - | Use a direct LLM instance for the parent agent |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | Custom model factory |
| `withPlugin(Object...)` | - | Register `@Plugin` beans |
| `withScanPackages(String...)` | - | Scan packages for `@Plugin` classes |
| `withDirectory(String...)` | - | Load file-system plugin directories |
| `withPluginMarket(Marketplace)` | empty `SimpleMarketplace` | Full marketplace control |
| `withMaxRounds(int)` | `10` | Maximum Search -> Plan -> Execute -> Reflect rounds |
| `withMaxAgentIterations(int)` | `20` | Maximum inner Skill/Sub-Agent iterations |
| `withMaxContextOutputChars(int)` | `800` | Maximum retained output characters per context item |
| `withVerbose(boolean)` | `false` | Enable verbose inner executor output |
| `withEventListener(AgentEventListener)` | no-op | Runtime event hook |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | State persistence for pause/resume |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | Final answer assembly strategy |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | Conversation storage backend |
| `withSessionBufferSize(int)` | `10` | Messages before session summary compaction |
| `withSessionMemory(ConversationMemory)` | auto-created | Custom session memory instance |
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
| `Vendor.MOONSHOT` | Moonshot/Kimi | `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama local models | `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | Baidu Qianfan | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | StepFun | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | Zhipu AI/GLM | `ZHIPU_KEY` |

</details>

<details>
<summary>Task status</summary>

| Status | Meaning |
|--------|---------|
| `FINISHED` | Goal completed |
| `PAUSED` | Interrupted by `pause()`, resumable |
| `TIMEOUT` | Hit `maxRounds` |
| `ESCALATED` | Reflector requested human review |
| `FAILED` | Unrecoverable error |

</details>

---

[中文文档](README_zh.md) · [Release notes](docs/release/pr-0.1.1-en.md) · [License](LICENSE)
