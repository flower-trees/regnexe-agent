<p align="center">
  <h1 align="center">⚡ Regnexe</h1>
  <p align="center"><b>一个框架，驾驭所有任务</b></p>
  <p align="center">Java 企业级中央 Agent 框架 — 接入即推理，开箱即生产。</p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x"/>
</p>

---

大多数 LLM 集成止步于单次工具调用。Regnexe 驱动完整的 **搜索 → 规划 → 执行 → 反思** 循环——选择合适的工具、跨多轮推进计划，直到目标完成或主动交还给你。

```
用户目标
    │
    ▼
[搜索 → 规划 → 执行 → 反思] × N 轮 → AgentResult
                  │
                  ▼
         插件市场（Marketplace）
    ┌───────────────────────────────┐
    │  加载渠道：                     │
    │   @Plugin Bean 注册            │
    │   包扫描                       │
    │   文件系统目录                  │
    │   动态构建（DB / API / …）      │
    │               ↓               │
    │  CapabilityDescriptor         │  ← 统一能力抽象层
    │  ┌─────────┬───────┬────────┐ │
    │  │MCP Tool │ Skill │SubAgent│ │
    │  └─────────┴───────┴────────┘ │
    └───────────────────────────────┘
```

**与简单工具调用封装的本质区别：**
- 🔄 **多轮自主推理** — 会重新规划并重试，而不只是调一次工具
- ⏸ **暂停与恢复** — 任意线程中断，携带新上下文随时恢复
- 🧩 **插件市场** — 增删能力无需改 Agent 代码
- 🏢 **企业级** — 会话记忆、任务状态持久化、事件钩子、Spring 零侵入

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. 配置 LLM

```yaml
# application.yml — 选择其中一个
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}      # 阿里云 DashScope（支持 deepseek-v4-flash、qwen 等）
  deepseek:
    chat-key: ${DEEPSEEK_KEY}    # DeepSeek 官方 API
```

接口地址已内置，只需配置 Key。

### 3. 写插件，运行

```java
// 用两个注解定义插件
@Plugin(id = "weather", name = "天气插件", description = "天气查询")
public class WeatherPlugin {

    @AgentTool("获取指定城市今天的天气，包括温度和运动建议。")
    public String getWeather(String city) {
        return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
    }
}
```

```java
@Autowired
RegnexeAgentBuilder regnexeAgentBuilder;

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())               // ← 一行加载插件
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("查询北京今天的天气，告诉我是否适合户外跑步");

System.out.println(result.getFinalText());         // FINISHED
```

无需 `@EnableXxx`，无需 XML 配置。`RegnexeAgentBuilder` 由 Spring Boot 自动装配。

---

## 插件体系

所有插件都通过**插件市场（Marketplace）**加载。每个插件暴露一个或多个**能力**——无论来源如何，每个能力都被统一表示为 `CapabilityDescriptor`，Agent 的搜索和执行步骤对其一视同仁。这层抽象使你可以在同一个 Agent 中混合使用 Java Bean、扫描类、Shell 脚本、数据库动态定义，无需任何特殊处理。

### 四种加载方式

**方式一 — Bean 注册**（适合 Spring 服务或快速原型）

```java
// 快捷方式：Builder 自动管理 Marketplace
regnexeAgentBuilder
    .withPlugin(new WeatherPlugin(), mySpringBean)  // 可传多个 @Plugin 对象
    ...
```

**方式二 — 包扫描**（适合大型插件库）

```java
// 快捷方式：自动发现指定包下所有 @Plugin 类
regnexeAgentBuilder
    .withScanPackages("com.example.plugins")
    ...
```

**方式三 — 文件系统目录**（适合运维管理、热插拔插件）

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml              ← 元信息
    tools/
      get_weather.sh         ← .sh / .py / .groovy 脚本
      get_weather.yaml       ← 工具描述 sidecar（与脚本同名）
    skills/
      advisor/SKILL.md       ← 含系统提示词的 Skill
    subagents/
      planner/AGENT.md       ← 自主子 Agent
```

```java
// 快捷方式：扫描目录，自动加载所有有效插件文件夹
regnexeAgentBuilder
    .withDirectory("/opt/regnexe-plugins")
    ...
```

增删插件**无需改任何代码**。

<details>
<summary>目录文件格式说明</summary>

**`plugin.yaml`**
```yaml
pluginId: weather-plugin
name: 天气插件
version: 1.0
description: 目录加载的天气查询插件
tags: [weather]
```

**`tools/get_weather.yaml`**（与脚本同名的 sidecar）
```yaml
description: "获取指定城市今天的天气，包括温度和运动建议"
params: "city: String -- 城市名称"
tags: [weather]
```

**`skills/advisor/SKILL.md`**
```markdown
---
name: advisor
description: "户外活动建议。TRIGGER: 用户询问户外安排时使用。"
---
你是一个天气顾问，根据用户提供的天气信息，给出合理的户外活动建议。
```

**`subagents/planner/AGENT.md`**
```markdown
---
name: planner
description: "户外活动规划子 Agent。TRIGGER: 需要规划完整行程时使用。"
---
你是一个专业的户外活动规划师，根据天气和用户需求给出详细的活动规划。
```

</details>

**方式四 — 动态构建**（适合来自数据库、远程配置或运行时生成的插件）

当插件定义来自数据库或需要自定义加载逻辑时，直接构造 `CapabilityDescriptor` 并安装到市场：

```java
// 从任意来源（DB、远程配置、运行时逻辑）构建能力描述
CapabilityDescriptor cap = CapabilityDescriptor.builder()
    .capabilityId("db-weather.get_weather")
    .pluginId("db-weather")
    .type(CapabilityType.MCP_TOOL)
    .name("get_weather")
    .description("获取指定城市今天的天气。")
    .tool(Tool.builder()
        .name("get_weather")
        .description("获取指定城市今天的天气")
        .params("city: String -- 城市名称")
        .func(city -> callWeatherApi(city.toString()))
        .build())
    .build();

PluginDescriptor plugin = PluginDescriptor.builder()
    .pluginId("db-weather")
    .name("DB 天气插件")
    .version("1.0")
    .capabilities(List.of(cap))
    .build();

SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.install(plugin);

regnexeAgentBuilder
    .withPluginMarket(marketplace)
    ...
```

如需更精细的控制，可实现 `PluginManager` 接口，通过 `marketplace.load(yourManager)` 加载。

### 三种能力类型

每个插件能力——无论以何种方式加载——都以 `CapabilityDescriptor` 的形式暴露给 Agent。Agent 的搜索步骤按名称和描述筛选相关描述符，执行步骤按需实例化实际能力。支持三种类型：

| 类型 | 是什么 | 适用场景 |
|------|--------|---------|
| **MCP Tool** | 单个可调用函数 | 简单查询、API 调用 |
| **Skill** | 嵌套 Agent，有独立 System Prompt 和内部工具 | 多步骤领域任务 |
| **Sub-Agent** | 完全自主的子 Agent，有独立推理循环 | 复杂独立子任务 |

Skill 和 Sub-Agent 在描述符中保存配置（`SkillConfig` / `SubAgentConfig`），在执行时按需构建——加载阶段不依赖 LLM。

<details>
<summary>Skill 和 Sub-Agent 代码示例</summary>

```java
// Skill — 嵌套 Agent，有内部工具
Skill contractSkill = Skill.from(
    SkillConfig.builder()
        .name("contract_analyzer")
        .description("差旅合同条款法律风险分析。TRIGGER: 需要分析合同时使用。")
        .systemPrompt("你是合同风险分析助手，逐条调用 analyze_clause 工具后汇总建议。")
        .build(), chainActor)
    .llm(llm).tools(analyzeClauseTool).build();

// Sub-Agent — 完全自主，有独立推理循环
SubAgent travelAgent = SubAgent.from(
    SubAgentConfig.builder()
        .name("travel_planner")
        .description("出差行程规划专家。TRIGGER: 需要规划出差行程时使用。")
        .systemPrompt("你是商务出差规划师，调用景点和餐厅工具后输出3天行程。")
        .build(), chainActor)
    .llm(llm).tools(attractionsTool, restaurantsTool).build();

// 主 Agent 自动选择并组合这三种能力
AgentResult result = agent.execute(
    "下周成都出差3天：查天气、分析差旅报销合同风险、规划行程。"
);
```

</details>

---

## 暂停与恢复

```java
// 在后台线程运行
Future<AgentResult> future = executor.submit(() -> agent.execute(request));

// 任意线程暂停（线程安全）
agent.pause();
AgentResult paused = future.get();   // status == PAUSED

// 稍后携带补充信息恢复
AgentResult done = agent.resume(sessionId, "请同时考虑今天的空气质量指数。");
// done.getStatus() == FINISHED
```

> 需要配置 `withTaskStore(taskStore)` 以持久化暂停状态。

---

## 会话记忆

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withSessionStorage(new InMemoryConversationStorage())
    .build();

// 第一轮
agent.execute(request("session-123", "查询北京今天的天气"));

// 第二轮 — 记得上一轮结果，不会重复调用工具
agent.execute(request("session-123", "根据你刚才查到的天气，建议我今天穿什么？"));
```

---

## 参考文档

<details>
<summary>Builder 参数说明</summary>

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `withDefaultModel(Vendor, String)` | — | LLM 厂商 + 模型名称 |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | 自定义 LLM Provider |
| `withPlugin(Object...)` | — | 注册 `@Plugin` Bean（自动创建市场） |
| `withScanPackages(String...)` | — | 扫描包自动发现 `@Plugin` 类（自动创建市场） |
| `withDirectory(String...)` | — | 从文件系统目录加载插件（自动创建市场） |
| `withPluginMarket(Marketplace)` | 空市场 | 完整市场控制 |
| `withMaxRounds(int)` | `10` | 最大推理轮次 |
| `withEventListener(AgentEventListener)` | 无操作 | LLM 输出、工具调用、结果的事件钩子 |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | 任务状态持久化（暂停/恢复必须配置） |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | 最终答案组装策略 |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | 跨轮次会话记忆 |
| `withSessionBufferSize(int)` | `10` | 触发摘要压缩前保留的消息数 |
| `withAgentContext(AgentContext)` | `FullContext` | 上下文窗口策略 |

</details>

<details>
<summary>支持的 LLM 厂商</summary>

| 枚举值 | 厂商 | 环境变量 |
|--------|------|---------|
| `Vendor.ALIYUN` | 阿里云 DashScope | `ALIYUN_KEY` |
| `Vendor.DEEPSEEK` | DeepSeek 官方 | `DEEPSEEK_KEY` |
| `Vendor.DOUBAO` | 字节跳动豆包 | `DOUBAO_KEY` |
| `Vendor.HUNYUAN` | 腾讯混元 | `HUNYUAN_KEY` |
| `Vendor.LINGYI` | 零一万物 | `LINGYI_KEY` |
| `Vendor.MINIMAX` | MiniMax | `MINIMAX_KEY` |
| `Vendor.MOONSHOT` | Moonshot（Kimi）| `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama（本地）| `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | 百度千帆 | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | 阶跃星辰 | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | 智谱 AI（GLM）| `ZHIPU_KEY` |

所有接口地址已内置，只需设置环境变量或在 `models.*` 前缀下配置 `chat-key`。

</details>

<details>
<summary>任务状态说明</summary>

| 状态 | 含义 |
|------|------|
| `FINISHED` | 目标完成 |
| `PAUSED` | 被 `pause()` 中断，可恢复 |
| `TIMEOUT` | 达到 `maxRounds` 上限 |
| `ESCALATED` | Reflector 判断需要人工介入 |
| `FAILED` | 不可恢复的错误 |

</details>

---

如果 Regnexe 对你有帮助，欢迎点个 ⭐ Star，这是对项目最大的支持。  
有企业集成需求或定制场景，欢迎提 Issue 或直接联系。

[English README](README.md) · [License](LICENSE)
