# Regnexe 的插件化能力市场 Marketplace：像管理插件一样管理 Agent 能力

企业 Agent 真正难的，不是接入一个工具，而是长期管理一组不断增长的能力。

在早期 Demo 中，我们通常会把几个工具直接写进 Agent：

- 一个天气查询工具
- 一个数据库查询工具
- 一个文档分析工具
- 一个报表生成工具

这种方式在演示阶段没有问题。但进入企业场景后，能力数量会迅速增长：不同部门有不同系统，不同业务线有不同流程，不同团队有不同脚本和 API。能力来源越来越多，管理难度也会越来越高。

如果没有统一的能力管理机制，Agent 系统很容易变成一堆散落在代码里的工具调用。

Regnexe 的 Marketplace，解决的正是这个问题。

它把工具、Skill、SubAgent 统一封装成 `CapabilityDescriptor`，让企业可以像管理插件一样管理 Agent 能力。

## 为什么企业需要能力市场

企业内部的能力不是静态的。

一个真实的 Agent 平台，通常会面对这些问题：

- 新业务上线后，需要快速接入新的 API
- 某个旧工具不可用时，需要临时禁用或替换
- 不同团队希望维护自己的能力包
- 安全团队需要知道 Agent 能调用哪些能力
- 运维团队希望通过目录或配置动态加载工具
- 平台团队希望统一治理能力命名、描述、标签和权限

如果所有能力都写死在 Agent 代码里，每新增一个能力都要改代码、发版、重启，维护成本会越来越高。

Marketplace 的价值，是把能力从 Agent 主流程里解耦出来。

Agent 不直接关心能力来自哪里。它只面对一个统一市场：

```text
Marketplace
  ├── Plugin A
  │   ├── Capability 1
  │   └── Capability 2
  ├── Plugin B
  │   ├── Capability 3
  │   └── Capability 4
  └── Plugin C
      └── Capability 5
```

Search、Plan、Execute 都基于统一的 `CapabilityDescriptor` 工作。

## CapabilityDescriptor：统一能力抽象

Regnexe 中，所有能力最终都会被描述成 `CapabilityDescriptor`。

这意味着不管能力底层是什么形式，Agent 看到的都是统一结构：

- 能力 ID
- 插件 ID
- 能力类型
- 名称
- 描述
- 标签
- 具体执行配置

能力类型主要包括三类：

```text
MCP_TOOL  → 单个工具函数
SKILL     → 带系统提示词和继承工具的领域能力
SUB_AGENT → 拥有自己推理循环和工具集的子 Agent
```

这种统一抽象非常重要。

因为企业内部能力来源复杂，但 Agent 的调度逻辑不应该被复杂来源拖垮。无论能力来自 Java Bean、脚本、目录、数据库还是远程配置，进入 Marketplace 后，都可以用同一套 Search、Plan、Execute 机制调度。

## 四种能力加载方式

Regnexe 支持多种插件加载方式，适配不同企业场景。

第一种是 Bean 注册，适合业务系统内已有的 Spring 服务或 Java 对象。

```java
regnexeAgentBuilder
        .withPlugin(new WeatherPlugin())
        .build();
```

第二种是包扫描，适合企业内部维护一组标准插件库。

```java
regnexeAgentBuilder
        .withScanPackages("com.example.agent.plugins")
        .build();
```

第三种是文件系统目录，适合运维或平台团队管理可插拔能力。

```java
regnexeAgentBuilder
        .withDirectory("/opt/regnexe-plugins")
        .build();
```

第四种是编程式安装，适合数据库、远程配置中心或动态能力管理平台。

```java
CapabilityDescriptor cap = CapabilityDescriptor.builder()
        .capabilityId("db-weather.get_weather")
        .pluginId("db-weather")
        .type(CapabilityType.MCP_TOOL)
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
```

这四种方式覆盖了从快速开发到企业治理的不同阶段。

早期可以用 Bean 快速接入，规模化后可以用包扫描和目录加载，平台化后可以通过数据库或远程配置动态安装。

## Tool、Skill、SubAgent 都能统一管理

很多框架只把“工具”当成可管理对象。

但企业 Agent 的能力不只是工具。

一个简单查询可以是 Tool；一个合同分析任务更适合封装成 Skill；一个旅行规划或代码工作区助手，则可能需要 SubAgent 自己进行多轮工具调用。

Regnexe 的 Marketplace 可以统一管理这三类能力。

```text
CapabilityDescriptor
  ├── Tool: get_weather
  ├── Skill: contract_analyzer
  └── SubAgent: travel_planner
```

这带来的商业价值是：企业可以按照任务复杂度建设能力，而不是把所有东西都硬塞成工具函数。

简单能力简单接，复杂能力复杂封装。

## 插件化带来的治理能力

企业落地 Agent，一定会遇到治理问题。

谁能新增能力？谁能禁用能力？哪个能力属于哪个业务线？哪个能力可以调用哪些工具？能力描述是否准确？是否能被搜索到？是否应该暴露给所有 Agent？

Marketplace 为这些治理问题提供了基础。

通过插件和能力描述，企业可以建立一套标准化管理方式：

- 用 pluginId 区分业务域或团队
- 用 capabilityId 唯一标识能力
- 用 type 区分 Tool、Skill、SubAgent
- 用 description 提升搜索和规划准确性
- 用 tags 做分类和筛选
- 用 allowedTools 和 ownTools 控制工具边界

这意味着能力不再只是代码里的方法，而是可以被登记、审计、分类、扩展和替换的企业资产。

## 从代码看能力市场如何工作

一个插件可以暴露多个能力，每个能力通过 `CapabilityDescriptor` 进入 Marketplace：

```java
SimpleMarketplace marketplace = new SimpleMarketplace();

marketplace.install(PluginDescriptor.builder()
        .pluginId("business-trip-plugin")
        .name("Business Trip Plugin")
        .version("1.0")
        .capabilities(List.of(
                weatherCap,
                contractAnalyzerCap,
                travelPlannerCap
        ))
        .build());

RegnexeAgent agent = regnexeAgentBuilder
        .withPluginMarket(marketplace)
        .withMaxRounds(3)
        .build();
```

运行时，Agent 会从 Marketplace 中搜索当前任务需要的能力：

```text
[Search Result ] R1 Found 3 capabilities: get_weather, contract_analyzer, travel_planner
[Plan Result   ] R1 Selected: [get_weather, contract_analyzer, travel_planner] | Strategy: SYNTHESIZE
```

这说明能力并没有写死在执行流程里，而是由 Marketplace 提供，再由 Search 和 Plan 动态选择。

## 商业价值：能力管理从代码问题变成平台问题

Marketplace 的商业价值，在于它把 Agent 能力管理从“代码问题”升级成“平台问题”。

第一，它提升扩展速度。

新增能力不一定要改 Agent 主流程。只要安装新插件，Marketplace 就能把新能力纳入搜索和规划范围。

第二，它降低维护成本。

能力按插件组织后，企业可以按业务域、团队、系统边界分别维护，不必把所有工具混在一个 Agent 里。

第三，它增强治理能力。

能力有 ID、有描述、有类型、有标签，后续可以接权限、审计、灰度、版本管理和禁用策略。

第四，它支持复杂能力演进。

企业可以从 Tool 起步，逐步升级到 Skill 和 SubAgent，而不需要推翻原有架构。

第五，它让 Agent 平台具备规模化基础。

当能力数量从几十个增长到几百个时，Marketplace 是能力组织、发现和治理的基础设施。

## 结语

企业 Agent 不是一个模型加几个工具就能长期运行的系统。

随着业务场景增加，真正决定系统能否规模化的，是能力能否被统一管理。

Regnexe 的 Marketplace 让工具、Skill、SubAgent 都进入同一个能力市场，通过 `CapabilityDescriptor` 统一描述，再交给 Search、Plan、Execute 统一调度。

这就是 Regnexe 的第二项核心商业价值：

**它让企业可以像管理插件一样管理 Agent 能力，把分散工具变成可治理、可扩展、可复用的能力资产。**
