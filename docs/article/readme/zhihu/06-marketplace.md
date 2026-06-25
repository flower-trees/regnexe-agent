# 能力市场换成数据库，对架构设计意味着什么？

先说结论：如果你的"能力市场"一开始就被设计成接口而不是具体类，换成数据库几乎是零成本的事；如果一开始就是个具体类，换的时候你会发现到处都是隐藏的类型依赖。这是个挺典型的"早期设计决定后期成本"的案例。

> 「Regnexe 实战系列」第 6 篇（共 10 篇），对应仓库 [`ExampleReadme06MarketplaceTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java)。上一篇：[05. 插件该怎么加载](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/05-plugin-packaging.md)。

## 这个问题为什么值得提前想清楚

前 5 篇无论用哪种方式注册能力——`withTool`、`@Plugin`、包扫描、目录加载——最终都会落到同一个对象上：`Marketplace`。这是所有能力的统一索引：装能力、按目标搜能力、按 id 解析能力。

默认实现是 `SimpleMarketplace`，纯内存 `Map`。能跑 demo，但企业场景里很快会遇到真实需求：插件数量上百个需要按租户/权限过滤，想用 ES 做关键词检索或者向量库做语义召回，能力定义本身就存在数据库里。

如果 `Marketplace` 是个具体类而不是接口，这些需求基本意味着要改框架源码——这是很多"看起来可扩展"的框架实际落地时最容易暴露的问题。

## Regnexe 的处理方式：纯接口

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

`install` 装能力，`search` 给 Planner 提供候选集，`resolveDescriptor` 按 id 精确取出——三个方法，职责很清楚。

## 换成自己的实现，长这样

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

## 划重点：接口之外的方法你可以随便加

注意示例里的 `findByTag(String tag)`——这个方法不在 `Marketplace` 接口里，是 `DbBackedMarketplace` 自己额外加的。这一点我觉得是很多框架设计者容易忽略的地方：**接口约束的是"框架怎么用你的实现"，不应该限制"你的实现还能多做什么"**。运维后台想按标签筛插件，直接在自己的实现类上扩展即可。

## 这个设计到底解决了什么问题

很多框架的"可扩展点"停留在文档层面——理论上能换，实操起来发现到处都是具体类型依赖。Regnexe 这里做得比较克制：`RegnexeAgentBuilder.withPluginMarket(Marketplace)` 接受的就是接口类型，框架内部从 Search 节点到 Execute 节点，全部只依赖这个接口。

也就是说：从内存 Demo 跑到真实生产，能力市场这一层的迁移成本几乎为零——你需要做的只是写一个实现类，业务代码和 Agent 主循环一行都不用改。回到开头那句话：**这是设计阶段就决定好的事，不是后期"重构"出来的**。

---

你们项目里有没有类似"看起来能扩展，实际换实现成本很高"的设计？欢迎吐槽一下。

📌 上一篇：[05. 插件该怎么加载](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/05-plugin-packaging.md) ｜ 下一篇：[07. Agent 的记忆为什么要拆成三层](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/07-three-layer-memory.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
