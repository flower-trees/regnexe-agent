# Regnexe Agent

**让你的 Spring Boot 应用拥有自主推理能力。**

大多数 LLM 集成止步于单次工具调用。Regnexe Agent 给你一个生产级 Agent：它会搜索合适的能力、制定执行计划、调用工具、反思结果——循环推进，直到完成目标或主动上报给你。

接入任意 Spring Boot 3 项目，配置 LLM，三行代码写出第一个工具。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent)](https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent)

---

## 核心能力

| 能力 | 实际意义 |
|---|---|
| **Search → Plan → Execute → Reflect** | Agent 多轮推理，而不只是单次 Prompt |
| **三种能力类型** | 普通工具、嵌套 Skill、完全自主的 SubAgent 混合使用 |
| **插件市场** | 从注解 Bean、包扫描或文件系统目录加载能力，无需改代码即可增删插件 |
| **暂停与恢复** | 任意线程中断正在运行的任务，携带补充信息恢复执行 |
| **会话记忆** | 跨多次 `execute()` 调用共享滚动摘要 |
| **12 个 LLM 厂商** | 阿里云 · DeepSeek · 豆包 · 混元 · 零一 · MiniMax · Moonshot · Ollama · OpenAI · 千帆 · StepFun · 智谱 |

---

## 环境要求

- Java 17+
- Spring Boot 3.x

---

## 引入依赖

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## 快速开始

### 1. 配置 LLM

`regnexe-agent` 通过 Spring Boot 自动配置注册 `RegnexeAgentBuilder` Bean，无需任何 `@EnableXxx` 注解。

**方案 A — 阿里云 DashScope（DeepSeek 兼容接入）**

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}   # 或直接写 key，或设置环境变量 ALIYUN_KEY
```

```java
.withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
```

**方案 B — DeepSeek 官方 API**

```yaml
# application.yml
models:
  deepseek:
    chat-key: ${DEEPSEEK_KEY}   # 或设置环境变量 DEEPSEEK_KEY
```

```java
.withDefaultModel(Vendor.DEEPSEEK, "deepseek-chat")
```

接口地址已内置，只需配置 Key。

### 2. 定义工具

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("获取指定城市今天的天气，包括温度和运动建议。")
    .params("city: String -- 城市名称（中文）")
    .func(city -> {
        if (city.toString().contains("北京"))
            return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
        return city + "：多云，18°C，建议减少户外活动。";
    })
    .build();
```

### 3. 构建市场并运行

```java
@Autowired
private RegnexeAgentBuilder regnexeAgentBuilder;

public void run() {
    SimpleMarketplace marketplace = new SimpleMarketplace();
    marketplace.install(PluginDescriptor.builder()
        .pluginId("weather-plugin").version("1.0")
        .name("Weather Plugin").description("天气查询插件")
        .capabilities(List.of(
            CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .name("get_weather")
                .description("获取指定城市今天的天气")
                .tool(weatherTool)
                .build()))
        .build());

    RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withPluginMarket(marketplace)
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(5)
        .build();

    AgentResult result = agent.execute("查询北京今天的天气，告诉我是否适合户外跑步");

    System.out.println(result.getStatus());    // FINISHED
    System.out.println(result.getFinalText());
}
```

---

## 插件体系

### 能力类型

| 类型 | 说明 | 底层对象 |
|------|------|----------|
| `MCP_TOOL` | 单个可调用函数 | `Tool` |
| `SKILL` | 嵌套 Agent，拥有独立 System Prompt 和内部工具 | `Skill` / `SkillConfig` |
| `SUB_AGENT` | 完全自主的子 Agent，拥有独立推理循环 | `SubAgent` / `SubAgentConfig` |

### 加载方式一 — 注解 Bean

```java
@Plugin(id = "weather-plugin", name = "Weather Plugin",
        description = "天气查询插件", tags = {"weather"})
public class WeatherPlugin {

    @AgentTool("获取指定城市今天的天气，包括温度和运动建议。")
    public String getWeather(String city) {
        return city.contains("北京") ? "北京：晴，22°C，适合跑步。" : city + "：多云，18°C。";
    }

    @AgentTool("根据温度给出穿衣建议。")
    public String getDressAdvice(String temperature) {
        return "温度 " + temperature + "°C：建议穿轻薄外套，注意早晚温差。";
    }
}
```

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(
    new DefaultPluginManager().register(new WeatherPlugin())
);
```

### 加载方式二 — 包扫描

```java
DefaultPluginManager mgr = new DefaultPluginManager();
mgr.scanPackages("com.example.plugins");  // 自动发现所有 @Plugin 类

SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(mgr);
```

### 加载方式三 — 文件系统目录

按约定目录结构组织插件，无需改代码即可增删插件：

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml              ← 插件元信息
    tools/
      get_weather.sh         ← Shell 脚本工具
      get_weather.yaml       ← 工具描述 sidecar
    skills/
      weather_advisor/
        SKILL.md             ← 前置 frontmatter + 系统提示词
    subagents/
      outdoor_advisor/
        AGENT.md             ← 前置 frontmatter + 系统提示词
```

**`plugin.yaml`**
```yaml
pluginId: weather-plugin
name: Weather Plugin
version: 1.0
description: 目录加载的天气查询插件
tags: [weather]
```

**`tools/get_weather.yaml`**（sidecar，与脚本同名）
```yaml
description: "获取指定城市今天的天气，包括温度和运动建议"
params: "city: String -- 城市名称"
tags: [weather]
```

**`skills/weather_advisor/SKILL.md`**
```markdown
---
name: weather_advisor
description: "根据天气情况给出户外活动建议"
---
你是一个天气顾问，根据用户提供的天气信息，给出合理的户外活动建议。
```

**`subagents/outdoor_advisor/AGENT.md`**
```markdown
---
name: outdoor_advisor
description: "户外活动规划子Agent"
---
你是一个专业的户外活动规划师，根据天气和用户需求给出详细的活动规划。
```

支持的脚本类型：`.sh`、`.py`、`.groovy`

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(
    new DefaultPluginManager().addDirectory("/opt/regnexe-plugins")
);
```

### 多来源混合加载

```java
marketplace.load(
    new DefaultPluginManager()
        .addDirectory("/opt/regnexe-plugins")
        .register(mySpringBean)
        .scanPackages("com.example.plugins")
);
```

---

## 混合能力（Tool + Skill + SubAgent）

```java
// MCP Tool — 普通函数调用
Tool weatherTool = Tool.builder()
    .name("get_weather").description("查询城市天气预报。")
    .params("city: String -- 城市名称")
    .func(city -> "成都：多云转阴，22°C，注意防雨。")
    .build();

// Skill — 嵌套 Agent，拥有内部工具
Skill contractSkill = Skill.from(
    SkillConfig.builder()
        .name("contract_analyzer")
        .description("差旅合同条款法律风险分析。TRIGGER: 需要分析合同时使用。")
        .systemPrompt("你是合同风险分析助手，逐条分析并调用 analyze_clause 工具，最后汇总建议。")
        .build(), chainActor)
    .llm(llm)
    .tools(analyzeClauseTool)
    .build();

// SubAgent — 完全自主，拥有独立推理循环
SubAgent travelAgent = SubAgent.from(
    SubAgentConfig.builder()
        .name("travel_planner")
        .description("出差行程规划专家。TRIGGER: 需要规划出差行程时使用。")
        .systemPrompt("你是商务出差行程规划师。调用景点和餐厅工具后，输出3天详细行程。")
        .build(), chainActor)
    .llm(llm)
    .tools(attractionsTool, restaurantsTool)
    .build();

// 注册三种类型的插件
marketplace.install(weatherPlugin);    // MCP_TOOL
marketplace.install(legalPlugin);      // SKILL
marketplace.install(travelPlugin);     // SUB_AGENT

// Agent 自动选择并组合能力，完成复杂目标
AgentResult result = agent.execute(
    "我下周要去成都出差3天，请帮我：" +
    "1. 查询成都近期天气和穿衣建议；" +
    "2. 分析差旅报销合同条款风险；" +
    "3. 根据天气规划3天出差行程。"
);
```

---

## 暂停与恢复

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPluginMarket(marketplace)
    .withTaskStore(taskStore)      // 暂停状态需要持久化，必须配置
    .withMaxRounds(5)
    .build();

// 在后台线程中执行
ExecutorService pool = Executors.newSingleThreadExecutor();
Future<AgentResult> future = pool.submit(() -> agent.execute(request));

// 在任意线程中暂停（如用户点击取消按钮）
agent.pause();    // 线程安全，任务状态变为 PAUSED

AgentResult paused = future.get();
// paused.getStatus() == TaskStatus.PAUSED

// 稍后携带补充信息恢复执行
AgentResult resumed = agent.resume(sessionId, "请同时考虑今天的空气质量");
// resumed.getStatus() == TaskStatus.FINISHED
```

---

## 会话记忆

相同 `sessionId` 的多次 `execute()` 调用共享滚动会话摘要，Agent 每次执行前自动读取。

```java
InMemoryConversationStorage sessionStorage = new InMemoryConversationStorage();
String sessionId = UUID.randomUUID().toString();

RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPluginMarket(marketplace)
    .withSessionStorage(sessionStorage)
    .withSessionBufferSize(10)    // 触发摘要压缩前保留的消息数（默认 10）
    .build();

// 第一轮
TaskRequest req1 = new TaskRequest();
req1.setSessionId(sessionId);
req1.setGoal("查询北京今天的天气");
AgentResult r1 = agent.execute(req1);

// 第二轮 — Agent 记得上一轮结果，无需重新查询工具
TaskRequest req2 = new TaskRequest();
req2.setSessionId(sessionId);
req2.setGoal("根据你刚才查到的天气，建议我今天穿什么？");
AgentResult r2 = agent.execute(req2);
```

---

## Builder 参数说明

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `withDefaultModel(Vendor, String)` | — | LLM 厂商 + 模型名称 |
| `withDefaultModel(String)` | — | 模型名称（使用默认 Provider 路由） |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | 自定义 LLM Provider |
| `withPluginMarket(Marketplace)` | 空 `SimpleMarketplace` | 能力注册中心 |
| `withMaxRounds(int)` | `10` | 最大 Search→Plan→Execute→Reflect 轮次 |
| `withEventListener(AgentEventListener)` | 无操作 | LLM 输出、工具调用、结果的事件钩子 |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | 暂停/恢复所需的任务状态持久化 |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | 最终答案的组装策略 |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | 跨轮次会话记忆存储 |
| `withSessionBufferSize(int)` | `10` | 触发摘要压缩前保留的消息数 |
| `withAgentContext(AgentContext)` | `FullContext` | LLM 上下文窗口策略 |

---

## 支持的 LLM 厂商

| 枚举值 | 厂商 | 配置 Key | 环境变量 |
|--------|------|----------|---------|
| `Vendor.ALIYUN` | 阿里云 DashScope | `models.aliyun.chat-key` | `ALIYUN_KEY` |
| `Vendor.DEEPSEEK` | DeepSeek 官方 | `models.deepseek.chat-key` | `DEEPSEEK_KEY` |
| `Vendor.DOUBAO` | 字节跳动豆包 | `models.doubao.chat-key` | `DOUBAO_KEY` |
| `Vendor.HUNYUAN` | 腾讯混元 | `models.hunyuan.chat-key` | `HUNYUAN_KEY` |
| `Vendor.LINGYI` | 零一万物 | `models.lingyi.chat-key` | `LINGYI_KEY` |
| `Vendor.MINIMAX` | MiniMax | `models.minimax.chat-key` | `MINIMAX_KEY` |
| `Vendor.MOONSHOT` | Moonshot（Kimi）| `models.moonshot.chat-key` | `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama（本地）| `models.ollama.chat-key` | `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `models.chatgpt.chat-key` | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | 百度千帆 | `models.qianfan.chat-key` | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | 阶跃星辰 | `models.stepfun.chat-key` | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | 智谱 AI（GLM）| `models.zhipu.chat-key` | `ZHIPU_KEY` |

---

## 任务状态

| 状态 | 含义 |
|------|------|
| `FINISHED` | Agent 成功完成目标 |
| `PAUSED` | 被 `pause()` 中断，可通过 `resume()` 恢复 |
| `TIMEOUT` | 达到 `maxRounds` 上限未完成 |
| `ESCALATED` | Reflector 判断需要人工介入 |
| `FAILED` | 执行过程中发生不可恢复的错误 |

---

## 事件监听

```java
regnexeAgentBuilder
    .withEventListener(event -> {
        switch (event.getType()) {
            case LLM_RESPONDED    -> System.out.println("[LLM]  " + event.getContent());
            case TOOL_CALLED      -> System.out.println("[调用] " + event.getContent());
            case TOOL_RESULT      -> System.out.println("[结果] " + event.getContent());
            case EXECUTION_COMPLETED -> System.out.println("[完成] " + event.getContent());
        }
    })
    .build();
```

调试时可直接使用内置的 `ConsoleEventListener`。

---

## 开源协议

Apache License 2.0，详见 [LICENSE](LICENSE)。

---

[English README](README.md)
