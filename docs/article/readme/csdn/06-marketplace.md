# 能力市场换成数据库要改多少代码？答案：一个接口，零侵入

> 「Regnexe 实战系列」第 6 篇（共 10 篇），对应仓库 [`ExampleReadme06MarketplaceTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java)。上一篇：[05. 插件打包的三种姿势](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/05-plugin-packaging.md)。

## 一个容易被忽略的架构问题

前 5 篇无论用哪种方式注册能力——`withTool`、`@Plugin`、包扫描、目录加载——最终都会落到同一个对象上：`Marketplace`。这是 Regnexe 里所有能力的统一索引：装能力、按目标搜能力、按 id 解析能力。

默认实现是 `SimpleMarketplace`，纯内存 `Map`。能跑 demo，但企业场景里很快会遇到真实需求：

- 插件数量上百个，需要按租户、按权限过滤
- 想用 ES 做关键词检索，或者用向量库做语义召回
- 能力定义本身就存在数据库里，不是内存里 new 出来的

如果 `Marketplace` 是个具体类而不是接口，这些需求基本意味着要改框架源码。Regnexe 这套 harness 的做法是把它设计成一个**纯接口**，剩下的交给你。

## 默认实现长什么样

`SimpleMarketplace` 的核心方法就三个：

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

`install` 装能力，`search` 给 Planner 提供候选集，`resolveDescriptor` 按 id 精确取出。仓库测试里还验证了一个细节：

```java
CapabilitySearchResult searchResult = marketplace.search(query);
Assert.assertEquals(1, searchResult.getCandidates().size());
Assert.assertEquals("weather-plugin.get_weather", searchResult.getCandidates().get(0).getCapabilityId());
```

## 换成自己的实现：接口长这样

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

**没有改一行框架源码，Agent 主流程毫无感知**。Search → Plan → Execute → Reflect 该怎么跑还怎么跑，只是背后查能力的实现换了。

## 划重点：接口之外的方法你可以随便加

注意上面示例里的 `findByTag(String tag)`——这个方法**不在 `Marketplace` 接口里**，是 `DbBackedMarketplace` 自己额外加的。运维后台想按标签筛插件、想加一个"插件使用频率统计"，直接在自己的实现类上扩展即可，完全不受接口约束。

## 这个设计解决了什么问题

很多框架的"可扩展点"停留在文档层面——理论上能换，实操起来发现到处都是具体类型依赖。Regnexe 这里做得比较克制：`RegnexeAgentBuilder.withPluginMarket(Marketplace)` 接受的就是接口类型，框架内部从 Search 节点到 Execute 节点，全部只依赖这个接口。

也就是说：**从内存 Demo 跑到真实生产，能力市场这一层的迁移成本几乎为零**——你需要做的只是写一个实现类，业务代码和 Agent 主循环一行都不用改。

---

📌 上一篇：[05. 插件打包的三种姿势](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/05-plugin-packaging.md) ｜ 下一篇：[07. 三层记忆模型，互不打扰才是关键](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/07-three-layer-memory.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
