# Regnexe Agent

**Your Spring Boot app, now with autonomous reasoning.**

Most LLM integrations stop at a single tool call. Regnexe Agent gives you a production-ready agent that thinks in loops: it searches for the right capabilities, plans an execution strategy, calls tools, and reflects on the result — repeating until the goal is achieved or it escalates to you.

Drop it into any Spring Boot 3 project. Connect your LLM. Write your first tool in three lines.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent)](https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent)

---

## What makes it different

| Capability | What it means in practice |
|---|---|
| **Search → Plan → Execute → Reflect** | The agent reasons across multiple rounds, not just a single prompt |
| **Three capability types** | Mix plain tools, nested skills, and fully autonomous sub-agents in one task |
| **Plugin marketplace** | Load capabilities from annotated beans, package scan, or a file-system directory — no code changes needed to add a plugin |
| **Pause & Resume** | Interrupt a running task from any thread; resume it later with supplemental context |
| **Session memory** | Rolling conversation summary shared across sequential `execute()` calls |
| **12 LLM vendors** | Aliyun · DeepSeek · Doubao · Hunyuan · Lingyi · Minimax · Moonshot · Ollama · OpenAI · Qianfan · Stepfun · Zhipu |

---

## Requirements

- Java 17+
- Spring Boot 3.x

---

## Installation

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## Quick Start

### 1. Configure your LLM

`regnexe-agent` auto-configures `RegnexeAgentBuilder` as a Spring bean — no `@EnableXxx` needed.

**Option A — Aliyun DashScope (DeepSeek via compatible endpoint)**

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}   # or set env var ALIYUN_KEY
```

```java
.withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
```

**Option B — DeepSeek official API**

```yaml
# application.yml
models:
  deepseek:
    chat-key: ${DEEPSEEK_KEY}   # or set env var DEEPSEEK_KEY
```

```java
.withDefaultModel(Vendor.DEEPSEEK, "deepseek-chat")
```

The API endpoint URLs are pre-configured. You only need to supply the key.

### 2. Define a tool

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get today's weather for a city, including temperature and activity advice.")
    .params("city: String -- city name")
    .func(city -> {
        if (city.toString().contains("Beijing"))
            return "Beijing: Sunny, 22°C, excellent air quality. Great day for outdoor running.";
        return city + ": Cloudy, 18°C. Reduce strenuous outdoor activity.";
    })
    .build();
```

### 3. Build a marketplace and run

```java
@Autowired
private RegnexeAgentBuilder regnexeAgentBuilder;

public void run() {
    SimpleMarketplace marketplace = new SimpleMarketplace();
    marketplace.install(PluginDescriptor.builder()
        .pluginId("weather-plugin").version("1.0")
        .name("Weather Plugin").description("Real-time weather queries")
        .capabilities(List.of(
            CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .name("get_weather")
                .description("Get today's weather for a city")
                .tool(weatherTool)
                .build()))
        .build());

    RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withPluginMarket(marketplace)
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(5)
        .build();

    AgentResult result = agent.execute("Check today's weather in Beijing and tell me if it's good for running.");

    System.out.println(result.getStatus());    // FINISHED
    System.out.println(result.getFinalText());
}
```

---

## Plugin System

### Capability types

| Type | Description | Backed by |
|------|-------------|-----------|
| `MCP_TOOL` | A single callable function | `Tool` |
| `SKILL` | A nested agent with its own system prompt and inner tools | `Skill` / `SkillConfig` |
| `SUB_AGENT` | A fully autonomous sub-agent with its own reasoning loop | `SubAgent` / `SubAgentConfig` |

### Loading method 1 — annotated bean

```java
@Plugin(id = "weather-plugin", name = "Weather Plugin",
        description = "Real-time weather query plugin", tags = {"weather"})
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city, including temperature and activity advice.")
    public String getWeather(String city) {
        return city.contains("Beijing") ? "Beijing: Sunny, 22°C. Great for running." : city + ": Cloudy, 18°C.";
    }

    @AgentTool("Get clothing advice based on temperature.")
    public String getDressAdvice(String temperature) {
        return "At " + temperature + "°C: light jacket recommended. Watch out for the morning/evening chill.";
    }
}
```

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(
    new DefaultPluginManager().register(new WeatherPlugin())
);
```

### Loading method 2 — package scan

```java
DefaultPluginManager mgr = new DefaultPluginManager();
mgr.scanPackages("com.example.plugins");  // discovers all @Plugin classes

SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(mgr);
```

### Loading method 3 — file-system directory

Organize plugins as a directory tree — no code changes needed to add or remove a plugin:

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml              ← plugin metadata
    tools/
      get_weather.sh         ← shell script tool
      get_weather.yaml       ← sidecar: description and params
    skills/
      weather_advisor/
        SKILL.md             ← frontmatter + system prompt
    subagents/
      outdoor_advisor/
        AGENT.md             ← frontmatter + system prompt
```

**`plugin.yaml`**
```yaml
pluginId: weather-plugin
name: Weather Plugin
version: 1.0
description: Directory-loaded weather query plugin
tags: [weather]
```

**`tools/get_weather.yaml`** (sidecar)
```yaml
description: "Get today's weather for a city, including temperature and activity advice"
params: "city: String -- city name"
tags: [weather]
```

**`skills/weather_advisor/SKILL.md`**
```markdown
---
name: weather_advisor
description: "Outdoor activity advisor based on current weather conditions"
---
You are a weather advisor. Given weather information, provide practical outdoor activity recommendations.
```

**`subagents/outdoor_advisor/AGENT.md`**
```markdown
---
name: outdoor_advisor
description: "Outdoor activity planning sub-agent"
---
You are a professional outdoor activity planner. Given weather and user preferences, produce a detailed activity plan.
```

Supported script types: `.sh`, `.py`, `.groovy`

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(
    new DefaultPluginManager().addDirectory("/opt/regnexe-plugins")
);
```

### Combining multiple sources

```java
marketplace.load(
    new DefaultPluginManager()
        .addDirectory("/opt/regnexe-plugins")
        .register(mySpringBean)
        .scanPackages("com.example.plugins")
);
```

---

## Mixed Capabilities (Tool + Skill + SubAgent)

```java
// MCP Tool — plain function call
Tool weatherTool = Tool.builder()
    .name("get_weather").description("Query weather forecast for a city.")
    .params("city: String -- city name")
    .func(city -> "Chengdu: Cloudy, 22°C. Bring an umbrella.")
    .build();

// Skill — nested agent with its own inner tool
Skill contractSkill = Skill.from(
    SkillConfig.builder()
        .name("contract_analyzer")
        .description("Legal risk analysis of travel reimbursement clauses. TRIGGER: use when analyzing contracts.")
        .systemPrompt("You are a contract risk analyst. Call analyze_clause for each clause and summarize the risks.")
        .build(), chainActor)
    .llm(llm)
    .tools(analyzeClauseTool)
    .build();

// SubAgent — fully autonomous with its own reasoning loop
SubAgent travelAgent = SubAgent.from(
    SubAgentConfig.builder()
        .name("travel_planner")
        .description("Business trip itinerary planner. TRIGGER: use when planning a trip.")
        .systemPrompt("You are a business travel planner. Call the attractions and restaurant tools, then output a 3-day itinerary.")
        .build(), chainActor)
    .llm(llm)
    .tools(attractionsTool, restaurantsTool)
    .build();

// Register all three in the marketplace
marketplace.install(weatherPlugin);    // MCP_TOOL
marketplace.install(legalPlugin);      // SKILL
marketplace.install(travelPlugin);     // SUB_AGENT

// The agent automatically selects and composes them to answer a complex goal
AgentResult result = agent.execute(
    "I'm going to Chengdu on a 3-day business trip next week. " +
    "Check the weather, analyze our reimbursement policy risks, and plan my itinerary."
);
```

---

## Pause & Resume

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPluginMarket(marketplace)
    .withTaskStore(taskStore)      // required to persist PAUSED state
    .withMaxRounds(5)
    .build();

// Execute in a background thread
ExecutorService pool = Executors.newSingleThreadExecutor();
Future<AgentResult> future = pool.submit(() -> agent.execute(request));

// Pause from any thread (e.g., user clicks Cancel)
agent.pause();    // thread-safe; transitions the task to PAUSED

AgentResult paused = future.get();
// paused.getStatus() == TaskStatus.PAUSED

// Resume later, optionally with supplemental context
AgentResult resumed = agent.resume(sessionId, "Also factor in today's air quality.");
// resumed.getStatus() == TaskStatus.FINISHED
```

---

## Session Memory

Tasks sharing the same `sessionId` accumulate a rolling conversation summary.  
The agent reads this summary automatically before each `execute()`.

```java
InMemoryConversationStorage sessionStorage = new InMemoryConversationStorage();
String sessionId = UUID.randomUUID().toString();

RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPluginMarket(marketplace)
    .withSessionStorage(sessionStorage)
    .withSessionBufferSize(10)    // messages kept before summarization (default: 10)
    .build();

// Turn 1
TaskRequest req1 = new TaskRequest();
req1.setSessionId(sessionId);
req1.setGoal("Check today's weather in Beijing.");
AgentResult r1 = agent.execute(req1);

// Turn 2 — agent recalls the prior result without re-querying the tool
TaskRequest req2 = new TaskRequest();
req2.setSessionId(sessionId);
req2.setGoal("Based on what you just found, what should I wear today?");
AgentResult r2 = agent.execute(req2);
```

---

## Builder Reference

| Method | Default | Description |
|--------|---------|-------------|
| `withDefaultModel(Vendor, String)` | — | LLM vendor + model name |
| `withDefaultModel(String)` | — | Model name (uses default provider routing) |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | Custom LLM provider |
| `withPluginMarket(Marketplace)` | empty `SimpleMarketplace` | Capability registry |
| `withMaxRounds(int)` | `10` | Max Search→Plan→Execute→Reflect iterations |
| `withEventListener(AgentEventListener)` | no-op | Hook for LLM output, tool calls, and results |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | State persistence for pause/resume |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | How the final answer is assembled |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | Cross-turn session memory |
| `withSessionBufferSize(int)` | `10` | Messages kept before summarization |
| `withAgentContext(AgentContext)` | `FullContext` | LLM context window strategy |

---

## Supported LLM Vendors

| Enum | Provider | Config key | Env var |
|------|----------|-----------|---------|
| `Vendor.ALIYUN` | Alibaba Cloud DashScope | `models.aliyun.chat-key` | `ALIYUN_KEY` |
| `Vendor.DEEPSEEK` | DeepSeek | `models.deepseek.chat-key` | `DEEPSEEK_KEY` |
| `Vendor.DOUBAO` | ByteDance Doubao | `models.doubao.chat-key` | `DOUBAO_KEY` |
| `Vendor.HUNYUAN` | Tencent Hunyuan | `models.hunyuan.chat-key` | `HUNYUAN_KEY` |
| `Vendor.LINGYI` | 01.AI (Yi series) | `models.lingyi.chat-key` | `LINGYI_KEY` |
| `Vendor.MINIMAX` | MiniMax | `models.minimax.chat-key` | `MINIMAX_KEY` |
| `Vendor.MOONSHOT` | Moonshot (Kimi) | `models.moonshot.chat-key` | `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama (local) | `models.ollama.chat-key` | `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `models.chatgpt.chat-key` | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | Baidu Qianfan | `models.qianfan.chat-key` | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | StepFun | `models.stepfun.chat-key` | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | Zhipu AI (GLM) | `models.zhipu.chat-key` | `ZHIPU_KEY` |

---

## Task Status

| Status | Meaning |
|--------|---------|
| `FINISHED` | Agent completed the goal |
| `PAUSED` | Interrupted by `pause()`, resumable via `resume()` |
| `TIMEOUT` | Reached `maxRounds` without finishing |
| `ESCALATED` | Reflector decided the task needs human input |
| `FAILED` | Unrecoverable error |

---

## Events

```java
regnexeAgentBuilder
    .withEventListener(event -> {
        switch (event.getType()) {
            case LLM_RESPONDED    -> System.out.println("[LLM]  " + event.getContent());
            case TOOL_CALLED      -> System.out.println("[CALL] " + event.getContent());
            case TOOL_RESULT      -> System.out.println("[OBS]  " + event.getContent());
            case EXECUTION_COMPLETED -> System.out.println("[DONE] " + event.getContent());
        }
    })
    .build();
```

Use the built-in `ConsoleEventListener` for quick debugging.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

[中文文档](README_zh.md)
