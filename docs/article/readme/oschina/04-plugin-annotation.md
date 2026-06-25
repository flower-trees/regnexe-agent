# 一个注解打包 4 种能力类型：这个开源框架的注解设计

> 「Regnexe 实战系列」第 4 篇（共 10 篇），对应仓库 [`ExampleReadme04PluginAnnotationTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java)。上一篇：[03. Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/03-subagent.md)。

## 装配代码膨胀是个真实的工程问题

前三篇分别用 `withTool`、`withSkill`、`withSubAgent` 注册了工具、Skill、Sub-Agent。如果一个业务模块同时需要这三种能力，按这个写法得分别 `new` 三个对象，再分三次 `with*` 调用——能力一多，装配代码很容易比业务逻辑本身还长。

Regnexe 的解法：把 Skill 和 Sub-Agent 也变成注解，而且可以嵌套进同一个类，共享同一个 `pluginId`。

## 先看老写法 @Plugin

```java
@Plugin(id = "weather", name = "Weather Plugin", description = "天气查询")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city.")
    public String getWeather(String city) {
        return "Beijing: sunny, 22 C.";
    }
}
```

这个用法没变。变化在于：`@AgentSkill` 和 `@AgentSubAgent` 现在可以作为这个类的内部静态类，跟 `@AgentTool` 方法长在一起。

## 完整代码：四种能力，一个类，一次注册

```java
@Plugin(id = "weather", name = "Weather Plugin",
        description = "Weather, air quality, travel advice, and trip cost estimation")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city.")
    public String getWeather(String city) {
        return "Beijing: sunny, 22 C.";
    }

    @AgentTool("Get today's air quality index (AQI) for a city.")
    public String getAirQuality(String city) {
        return "Beijing: AQI 35, excellent air quality.";
    }

    @AgentSkill(
            id = "travel_advisor",
            description = "Gives outdoor-activity advice based on the current weather for a city. " +
                          "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.",
            systemPrompt = """
                    You are an outdoor-activity advisor.
                    1. Call get_weather for the city the user mentions.
                    2. Based on the result, give a short, direct go/no-go recommendation.
                    """,
            allowedTools = {"weather.get_weather"}   // 注意：插件内的完整能力 id
    )
    public static class TravelAdvisorSkill {
        // 不需要 @AgentTool 方法——Skill 不能拥有私有工具
    }

    @AgentSubAgent(
            id = "expense_estimator",
            description = "Estimates the total cost of a business trip. " +
                          "TRIGGER: Use when the user asks for a trip budget or cost estimate.",
            model = "aliyun:qwen-plus",
            systemPrompt = """
                    You are a travel expense estimator.
                    1. Call estimate_trip_cost with the trip length and destination.
                    2. Report the total and a one-line breakdown.
                    """
    )
    public static class ExpenseEstimatorSubAgent {

        @AgentTool("Estimates total cost for a multi-day business trip.")
        public String estimateTripCost(int days, String city) {
            return "3-day Chengdu trip estimate: 3600 CNY total.";
        }
    }
}
```

注册只需要一行：

```java
RegnexeAgent agent = regnexeAgentBuilder
        .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
        .withPlugin(new WeatherPlugin())
        .withEventListener(new ConsoleEventListener())
        .build();
```

一次 `withPlugin(new WeatherPlugin())`，市场里就出现了 4 个能力，全部挂在同一个 `pluginId`（`weather`）下面。

## 设计上的关键点

`@AgentSubAgent` 内部复用的是和 `@Plugin` 完全一样的扫描机制——`@AgentTool` 方法照样会被扫描出来，只是去向不同：长在 `@Plugin` 类上的变成独立的 MCP_TOOL 能力，谁都能调；长在 `@AgentSubAgent` 类上的变成这个 Sub-Agent 的私有 `ownTools`，外面看不到。同一套扫描逻辑，靠"挂在哪个注解下面"决定能力的可见性，不需要学两套 API——这正是这套 harness 在底层只维护一套扫描/装配机制、上层暴露多种语义的设计思路。

## 两个需要注意的细节

`allowedTools` 要写完整 id：`travel_advisor` 和 `get_weather` 共享同一个 pluginId，工具的真实能力 id 是 `weather.get_weather`，不是裸的 `get_weather`。`@AgentSkill` 类里不要写 `@AgentTool` 方法：Skill 设计上就不持有私有工具，写了也不会被采集。

## 小结

工具不多、不需要打包管理，直接 `withTool`；需要按业务模块打包、加标签、做版本管理，用 `@Plugin` + 嵌套注解，一个类搞定一整套能力。`@AgentSkill`/`@AgentSubAgent` 也可以单独使用，各自注册成独立的单能力插件。

---

这种注解化的能力打包设计，欢迎大家去仓库体验一下，提提意见，对项目改进很有帮助。

📌 上一篇：[03. Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/03-subagent.md) ｜ 下一篇：[05. 插件加载方式：四种姿势全解析](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/oschina/05-plugin-packaging.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
