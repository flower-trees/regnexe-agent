# 低侵入 Spring Boot 集成：让业务系统不用大改也能接入 Agent

企业最怕“为了接入一个新框架，必须大规模改造现有系统”。

现实中，很多企业 Java 系统已经基于 Spring Boot 运行多年，里面有成熟的服务、配置、Bean、接口和部署流程。

Regnexe 的集成方式尽量低侵入。

通过 `RegnexeAgentBuilder`、`@Plugin` 和 `@AgentTool`，业务系统可以在不大改架构的情况下接入 Agent 能力。

## 使用 Builder 构建 Agent

Spring Boot 项目中可以直接注入 `RegnexeAgentBuilder`。

```java
@Autowired
private RegnexeAgentBuilder regnexeAgentBuilder;
```

然后构建 Agent：

```java
RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withPlugin(new WeatherPlugin())
        .withEventListener(new ConsoleEventListener())
        .withMaxRounds(3)
        .build();
```

这符合 Spring Boot 开发者的使用习惯。

## 用注解暴露业务能力

已有业务方法可以通过注解变成 Agent 工具。

```java
@Plugin(id = "weather", name = "天气插件", description = "天气查询能力")
public class WeatherPlugin {

    @AgentTool("查询指定城市近期天气")
    public String getWeather(String city) {
        return weatherService.query(city);
    }
}
```

业务团队不需要理解复杂的 Agent 内部机制，只需要把已有服务包装成插件能力。

## 不要求业务系统重写

Regnexe 不要求企业把所有业务逻辑迁移到 Agent 框架里。

它更像是在现有系统上增加一个智能编排层：

```text
已有业务服务
    ↓
@Plugin / @AgentTool
    ↓
Marketplace
    ↓
RegnexeAgent
```

这降低了试点成本。

企业可以先选择一个低风险场景，例如天气查询、文档分析、内部助手，再逐步扩展。

## 支持显式 Marketplace

如果企业需要更强控制，也可以显式构建 Marketplace。

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(new DefaultPluginManager().register(new WeatherPlugin()));

RegnexeAgent agent = regnexeAgentBuilder
        .withPluginMarket(marketplace)
        .build();
```

这让平台团队可以统一管理插件加载逻辑。

## 商业价值

第一，降低接入成本。

Spring Boot 项目可以快速试点，不需要大规模重构。

第二，保护已有系统。

业务服务保持原样，只增加插件包装层。

第三，方便团队接受。

Builder 和注解方式符合 Java 企业开发习惯。

第四，支持渐进推广。

可以从单个插件开始，逐步扩展为企业能力市场。

第五，降低组织阻力。

低侵入意味着更容易拿到业务团队和架构团队的支持。

## 结语

企业 Agent 的落地，不能只看技术先进性，还要看接入成本。

Regnexe 通过低侵入 Spring Boot 集成，让已有 Java 系统可以快速拥有 Agent 能力。

这就是 Regnexe 的第十五项核心商业价值：

**通过 RegnexeAgentBuilder、@Plugin、@AgentTool 即可接入，不要求业务系统大规模改造。**
