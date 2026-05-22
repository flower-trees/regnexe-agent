<p align="center">
  <h1 align="center">⚡ Regnexe</h1>
  <p align="center"><b>面向 Java / Spring Boot 的多轮 Agent 编排框架</b></p>
  <p align="center">用统一插件市场管理 Tool、Skill 和 Sub-Agent，让 Agent 能搜索、规划、执行并反思。</p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x"/>
</p>

---

Regnexe 不是一次 LLM 工具调用的简单封装。它围绕一个目标运行完整的 **Search -> Plan -> Execute -> Reflect** 循环：先从插件市场中搜索可用能力，再规划执行步骤，调用工具或子 Agent，最后根据结果判断是否完成、重试、继续规划或交给人工处理。

```
用户目标
  |
  v
[Search -> Plan -> Execute -> Reflect] x N 轮
  |
  v
AgentResult

能力来源统一进入 Marketplace：

@Plugin Bean / 包扫描 / 文件系统目录 / 动态构建
  |
  v
CapabilityDescriptor
  |
  +-- MCP Tool
  +-- Skill
  +-- Sub-Agent
```

## 为什么用 Regnexe

- **多轮推理**：Agent 会基于中间结果继续规划，而不是只调用一次工具。
- **统一能力市场**：Java Bean、扫描类、脚本目录、数据库动态定义都可以转成 `CapabilityDescriptor`。
- **插件化扩展**：业务能力可以独立增删，不需要改 Agent 主流程。
- **暂停与恢复**：运行中的任务可被 `pause()` 中断，并通过 `resume()` 带着新上下文继续。
- **会话记忆**：支持按 session 存储对话摘要，让后续任务复用历史信息。
- **Spring Boot 友好**：`RegnexeAgentBuilder` 自动装配，无需 `@EnableXxx` 或 XML 配置。

## 目录

- [快速开始](#快速开始)
- [核心模型](#核心模型)
- [插件加载方式](#插件加载方式)
- [企业级扩展点](#企业级扩展点)
- [暂停与恢复](#暂停与恢复)
- [会话记忆](#会话记忆)
- [参考文档](#参考文档)

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>regnexe-agent</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. 配置模型 Key

```yaml
# application.yml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
```

接口地址已内置。你可以显式指定厂商，也可以只传模型名，让 `DefaultModelProvider` 按模型名前缀自动路由。

### 3. 定义一个插件

```java
@Plugin(id = "weather", name = "天气插件", description = "天气查询")
public class WeatherPlugin {

    @AgentTool("获取指定城市今天的天气，包括温度和运动建议。")
    public String getWeather(String city) {
        return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
    }
}
```

### 4. 构建 Agent 并执行

```java
@Autowired
RegnexeAgentBuilder regnexeAgentBuilder;

AgentResult result = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withEventListener(new ConsoleEventListener())
    .build()
    .execute("查询北京今天的天气，告诉我是否适合户外跑步");

System.out.println(result.getStatus());
System.out.println(result.getFinalText());
```

## 核心模型

Regnexe 的运行时由三层组成：

| 层级 | 作用 |
|------|------|
| `RegnexeAgent` | 执行 Search -> Plan -> Execute -> Reflect 循环 |
| `Marketplace` | 管理所有插件和能力描述 |
| `CapabilityDescriptor` | 对 Tool、Skill、Sub-Agent 的统一抽象 |

### 三种能力类型

| 类型 | 是什么 | 适合什么场景 |
|------|--------|--------------|
| `MCP_TOOL` | 单个可调用函数或脚本 | 查询、计算、API 调用、业务动作 |
| `SKILL` | 带独立 System Prompt 和内部工具的嵌套 Agent | 合同分析、报告生成、垂直领域任务 |
| `SUB_AGENT` | 有独立推理循环的自主子 Agent | 可拆分的复杂子任务 |

加载阶段只需要把能力注册成 `CapabilityDescriptor`。Skill 和 Sub-Agent 的实际对象会在执行阶段按需构建，因此插件发现不依赖 LLM。

## 插件加载方式

所有能力都通过插件市场加载。你可以使用 Builder 的快捷方法，也可以手动构造 `Marketplace` 获得更细的控制。

### 方式一：Bean 注册

适合已有 Spring 服务、快速原型或手动传入插件对象。

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin(), mySpringBean)
    .build();
```

等价的显式写法：

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(new DefaultPluginManager().register(new WeatherPlugin()));

RegnexeAgent agent = regnexeAgentBuilder
    .withPluginMarket(marketplace)
    .build();
```

### 方式二：包扫描

适合插件数量较多、希望按包自动发现 `@Plugin` 类的场景。被扫描的插件类需要可被实例化，例如提供 public 无参构造器。

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withScanPackages("com.example.plugins")
    .build();
```

### 方式三：文件系统目录

适合运维管理、热插拔插件、脚本工具、Skill 和 Sub-Agent 文件化配置。

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

增删目录下的插件，不需要改 Agent 代码。

<details>
<summary>目录文件格式</summary>

**`plugin.yaml`**

```yaml
pluginId: weather-plugin
name: 天气插件
version: 1.0
description: 目录加载的天气查询插件
tags: [weather]
```

**`tools/get_weather.yaml`**

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

### 方式四：动态构建

适合插件定义来自数据库、远程配置、租户配置或运行时逻辑的场景。你可以直接构造 `CapabilityDescriptor` 并安装到市场。

```java
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

RegnexeAgent agent = regnexeAgentBuilder
    .withPluginMarket(marketplace)
    .build();
```

如果需要统一管理多个来源，也可以实现 `PluginManager`，再通过 `marketplace.load(yourManager)` 加载。

## 企业级扩展点

Regnexe 默认用内存实现，方便本地开发和快速接入。企业项目通常会把两个地方替换掉：**能力市场怎么搜索**，以及 **执行过程怎么存储**。

### 1. 能力市场：从“内存列表”扩展到“可搜索的能力库”

任务第一轮会调用 `Marketplace.search(SearchQuery)` 查找可用能力；后续如果 Reflector 判断需要重新搜索，会带着 `reflectionHint` 和 `excludeIds` 再搜一次，否则复用上一轮候选能力。默认的 `SimpleMarketplace` 只是从内存插件列表里返回候选能力；在企业项目中，可以把 `Marketplace` 换成自己的实现，让能力来自 DB、ES、向量库或多个来源的组合。

```
用户目标
  |
  v
SearchQuery(goal, reflectionHint, excludeIds, topK)
  |
  v
自定义 Marketplace
  |
  +-- DB：插件元数据、租户、权限、启停状态
  +-- ES：名称、描述、标签关键词检索
  +-- 向量库：按语义召回最相关能力
  +-- 配置中心 / API：动态下发能力定义
  |
  v
CapabilitySearchResult -> Planner 选择要执行的能力
```

| 场景 | 做法 |
|------|------|
| 插件很多，需要治理 | 把 `PluginDescriptor` / `CapabilityDescriptor` 存入 DB |
| 需要租户和权限隔离 | 在 `search()` 中按 tenant、role、scope 过滤 |
| 需要更准地找能力 | 用 ES 做关键词检索，或用向量库做语义召回 |
| 需要综合排序 | 组合权限过滤、关键词分数、向量分数和业务权重 |

`PluginManager` 更适合做“把外部来源加载进市场”的适配器；`Marketplace` 更适合做“运行时搜索和解析能力”的核心入口。能力规模较小时可以只扩展 `PluginManager`，能力规模大或需要复杂检索时建议直接实现 `Marketplace`。

### 2. 执行存储：把 Agent 过程变成可恢复、可审计的任务账本

一次 `execute()` 不是一个黑盒调用。Regnexe 会把任务拆成多轮，每一轮都有 Search、Plan、Execute、Reflect 的结果。默认 `TaskStore` 是内存实现；生产环境可以替换为 DB 实现，把每一轮过程保存下来。

```
TaskExecutionState
  |
  +-- taskId / sessionId / status / currentRound
  +-- searchResults
  +-- rounds[]
       |
       +-- search
       +-- plan
       +-- executionResult
       +-- reflection
```

这样做的价值是：

- **暂停恢复**：任务暂停后，可以从 `TaskStore` 找到同一 session 下最近的可恢复任务。
- **过程审计**：可以追踪 Agent 每轮搜到了什么能力、为什么选择、执行结果是什么、为什么继续或结束。
- **问题排查**：失败时能看到是搜索不准、规划错误、工具失败，还是反思判断不合理。
- **异步任务追踪**：长任务可以落库后由后台执行，前端按 `taskId` 查询状态和中间结果。

### 3. 三层上下文：分别解决不同问题

```
Session 记忆       多次任务之间共享的历史
  |
  v
Task 执行账本      一次 execute()/resume() 的多轮循环记录
  |
  v
Agent 执行上下文   单个 Agent 调工具时使用的上下文窗口
```

| 层级 | 说明 | 扩展方式 |
|------|------|----------|
| Session 记忆 | 用户会话级历史，用于让下一次任务知道之前聊过什么 | 替换 `ConversationStorage` |
| Task 执行账本 | 一次目标执行的完整轮次记录，用于恢复、审计和排查 | 替换 `TaskStore` |
| Agent 执行上下文 | 单个 Agent 执行工具时的上下文窗口策略 | 通过 `withAgentContext(...)` 配置 |

简单理解：`ConversationStorage` 解决“这个用户之前说过什么”，`TaskStore` 解决“这个任务执行到哪一步”，`AgentContext` 解决“单次 Agent 调工具时带多少上下文”。三者可以独立替换，不需要改 Agent 主循环。

## 暂停与恢复

`pause()` 可以从任意线程调用。当前执行会被标记为 `PAUSED`，之后可按 session 恢复最近一个可恢复任务。

```java
TaskRequest request = new TaskRequest();
request.setGoal("生成一份北京户外跑步建议");
request.setSessionId("session-123");

Future<AgentResult> future = executor.submit(() -> agent.execute(request));

agent.pause();
AgentResult paused = future.get();

AgentResult done = agent.resume(
    paused.getState().getSessionId(),
    "请同时考虑今天的空气质量指数。"
);
```

默认 `TaskStore` 是 `InMemoryTaskStore`。如果需要跨进程或服务重启后恢复任务，请通过 `withTaskStore(taskStore)` 接入你自己的持久化实现。

## 会话记忆

Regnexe 可以按 `sessionId` 记录对话摘要，后续任务会看到同一会话中的历史信息。

```java
RegnexeAgent agent = regnexeAgentBuilder
    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
    .withPlugin(new WeatherPlugin())
    .withSessionStorage(new InMemoryConversationStorage())
    .build();

TaskRequest first = new TaskRequest();
first.setSessionId("session-123");
first.setGoal("查询北京今天的天气");
agent.execute(first);

TaskRequest second = new TaskRequest();
second.setSessionId("session-123");
second.setGoal("根据你刚才查到的天气，建议我今天穿什么？");
agent.execute(second);
```

默认会话存储也是内存实现。生产环境中如需长期记忆或多实例共享，需要替换为自己的 `ConversationStorage`。

## 参考文档

<details>
<summary>Builder 参数</summary>

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `withDefaultModel(Vendor, String)` | - | 指定 LLM 厂商和模型名称 |
| `withDefaultModel(String)` | - | 只指定模型名，由 `DefaultModelProvider` 按前缀路由 |
| `withLlmProvider(ModelProvider)` | `DefaultModelProvider` | 自定义模型提供者 |
| `withPlugin(Object...)` | - | 注册一个或多个 `@Plugin` 对象 |
| `withScanPackages(String...)` | - | 扫描包并发现 `@Plugin` 类 |
| `withDirectory(String...)` | - | 从文件系统目录加载插件 |
| `withPluginMarket(Marketplace)` | 空 `SimpleMarketplace` | 完全接管插件市场 |
| `withMaxRounds(int)` | `10` | 最大推理轮次 |
| `withEventListener(AgentEventListener)` | `NO_OP` | 监听 Agent 启动、执行、完成等事件 |
| `withTaskStore(TaskStore)` | `InMemoryTaskStore` | 任务状态存储，影响暂停和恢复 |
| `withResultComposer(ResultComposer)` | `DefaultResultComposer` | 最终答案组装策略 |
| `withSessionStorage(ConversationStorage)` | `InMemoryConversationStorage` | 会话记忆存储 |
| `withSessionBufferSize(int)` | `10` | 触发摘要压缩前保留的消息数 |
| `withAgentContext(AgentContext)` | `FullContext` | 上下文窗口策略 |

</details>

<details>
<summary>支持的 LLM 厂商</summary>

| 枚举值 | 厂商 | 环境变量 |
|--------|------|----------|
| `Vendor.ALIYUN` | 阿里云 DashScope | `ALIYUN_KEY` |
| `Vendor.DEEPSEEK` | DeepSeek 官方 | `DEEPSEEK_KEY` |
| `Vendor.DOUBAO` | 字节跳动豆包 | `DOUBAO_KEY` |
| `Vendor.HUNYUAN` | 腾讯混元 | `HUNYUAN_KEY` |
| `Vendor.LINGYI` | 零一万物 | `LINGYI_KEY` |
| `Vendor.MINIMAX` | MiniMax | `MINIMAX_KEY` |
| `Vendor.MOONSHOT` | Moonshot / Kimi | `MOONSHOT_KEY` |
| `Vendor.OLLAMA` | Ollama 本地模型 | `OLLAMA_KEY1` |
| `Vendor.OPENAI` | OpenAI | `CHATGPT_KEY` |
| `Vendor.QIANFAN` | 百度千帆 | `QIANFAN_KEY` |
| `Vendor.STEPFUN` | 阶跃星辰 | `STEPFUN_KEY` |
| `Vendor.ZHIPU` | 智谱 AI / GLM | `ZHIPU_KEY` |

</details>

<details>
<summary>任务状态</summary>

| 状态 | 含义 |
|------|------|
| `FINISHED` | 目标完成 |
| `PAUSED` | 被 `pause()` 中断，可恢复 |
| `TIMEOUT` | 达到 `maxRounds` 上限 |
| `ESCALATED` | Reflector 判断需要人工介入 |
| `FAILED` | 不可恢复错误 |

</details>

---

如果 Regnexe 对你有帮助，欢迎点个 Star。  
有企业集成需求、定制场景或问题反馈，欢迎提 Issue。

[English README](README.md) · [License](LICENSE)
