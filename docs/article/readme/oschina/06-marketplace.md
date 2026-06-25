# 能力市场换成数据库要改多少代码？开源框架 Regnexe 的接口设计

> 「Regnexe 实战系列」第 6 篇（共 10 篇），对应仓库 [`ExampleReadme06MarketplaceTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java)。上一篇：[05. 插件加载方式](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/05-plugin-packaging.md)。

## 一个容易被忽略的架构问题

前 5 篇无论用哪种方式注册能力——`withTool`、`@Plugin`、包扫描、目录加载——最终都会落到同一个对象上：`Marketplace`。这是所有能力的统一索引：装能力、按目标搜能力、按 id 解析能力。

默认实现是 `SimpleMarketplace`，纯内存 `Map`。能跑 demo，但企业场景里很快会遇到真实需求：插件数量上百个需要按租户/权限过滤，想用 ES 做关键词检索或者向量库做语义召回，能力定义本身就存在数据库里。如果 `Marketplace` 是个具体类而不是接口，这些需求基本意味着要改框架源码。Regnexe 的做法是把它设计成一个纯接口，剩下的交给你。

## 默认实现

```java
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.install(PluginDescriptor.builder()
    .pluginId("weather-plugin").version("1.0")
    .name("Weather Plugin").description("Weather queries")
    .tool(weatherTool)
    .build());

CapabilitySearchResult result = marketplace.search(searchQuery);   // 给 Planner 的候选能力
CapabilityDescriptor cap = marketplace.resolveDescriptor("weather-plugin.get_weather");
```

`install` 装能力，`search` 给 Planner 提供候选集，`resolveDescriptor` 按 id 精确取出。

## 自定义实现

`Marketplace` 接口要求实现 `install`/`uninstall`/`enable`/`disable`/`search`/`resolveDescriptor`/`listEnabled` 七个方法。仓库测试里写了一个最小的"数据库版"示例（用 `Map` 模拟一张表，真实场景换成 JPA Repository 或 JdbcTemplate 就行）：

```java
class DbBackedMarketplace implements Marketplace {
    private final Map<String, PluginDescriptor> table = new LinkedHashMap<>();
    private final Set<String> enabledIds = new HashSet<>();

    @Override
    public void install(PluginDescriptor plugin) {
        table.put(plugin.getPluginId(), plugin);
        enabledIds.add(plugin.getPluginId());
    }

    @Override
    public CapabilitySearchResult search(SearchQuery query) {
        // 真实实现这里换成 SQL 查询 / ES 检索 / 向量召回
        ...
    }

    @Override
    public CapabilityDescriptor resolveDescriptor(String capabilityId) { ... }

    // 运维后台需要的额外查询方法，自由加，不受接口约束
    List<PluginDescriptor> findByTag(String tag) {
        return table.values().stream()
                .filter(p -> p.getTags() != null && p.getTags().contains(tag))
                .toList();
    }
}
```

接入只有一行：

```java
regnexeAgentBuilder.withPluginMarket(new DbBackedMarketplace()) ...
```

没有改一行框架源码，Agent 主流程毫无感知，Search → Plan → Execute → Reflect 该怎么跑还怎么跑，只是这套 harness 背后查能力的实现换了。

## 接口之外的方法可以自由扩展

示例里的 `findByTag(String tag)` 不在 `Marketplace` 接口里，是 `DbBackedMarketplace` 自己额外加的。运维后台想按标签筛插件、想加个使用频率统计，直接在自己的实现类上扩展即可，完全不受接口约束。

## 这个设计解决了什么问题

很多框架的"可扩展点"停留在文档层面，理论上能换，实操起来发现到处都是具体类型依赖。Regnexe 这里做得比较克制：`RegnexeAgentBuilder.withPluginMarket(Marketplace)` 接受的就是接口类型，框架内部从 Search 节点到 Execute 节点，全部只依赖这个接口。从内存 Demo 跑到真实生产，能力市场这一层的迁移成本几乎为零。

---

这套接口隔离的设计如果对你的架构选型有参考价值，欢迎去仓库给个 Star，也欢迎提 PR 一起完善企业级扩展能力。

📌 上一篇：[05. 插件加载方式](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/05-plugin-packaging.md) ｜ 下一篇：[07. Agent 记忆为什么要拆三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/07-three-layer-memory.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
