# 一个类，一次注册，搞定 2 个工具 + 1 个 Skill + 1 个 Sub-Agent

> 「Regnexe 实战系列」第 4 篇（共 10 篇），对应仓库 [`ExampleReadme04PluginAnnotationTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java)。上一篇：[03. Sub-Agent：自己的模型，自己的工具](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/03-subagent.md)。

## 痛点：能力一多，注册代码比业务代码还长

前三篇我们分别用 `withTool`、`withSkill`、`withSubAgent` 注册了工具、Skill、Sub-Agent。如果一个业务模块同时需要这三种能力，按现在的写法得分别 `new` 三个对象，再分三次 `with*` 调用——能力一多，这部分"装配代码"很容易比业务逻辑本身还长。

Regnexe 解决这个问题的思路很简单：**像 `@Plugin` 注解一类工具方法一样，把 Skill 和 Sub-Agent 也变成注解，而且可以嵌套进同一个类，共享同一个 `pluginId`**。

## 先复习一下 @Plugin

Java 老用户应该很熟悉这个套路——类上加 `@Plugin`，方法上加 `@AgentTool`：

```java
@Plugin(id = "weather", name = "Weather Plugin", description = "天气查询")
public class WeatherPlugin {

    @AgentTool("Get today's weather for a city.")
    public String getWeather(String city) {
        return "Beijing: sunny, 22 C.";
    }
}
```

这个用法没变。变化在于：现在 `@AgentSkill` 和 `@AgentSubAgent` 可以作为**这个类的内部静态类**，跟 `@AgentTool` 方法长在一起。

## 实战代码：四种能力，一个类，一次注册

仓库 [`ExampleReadme04PluginAnnotationTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java) 里的 `WeatherPlugin`：

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

一次 `withPlugin(new WeatherPlugin())`，市场里就出现了 4 个能力：`weather.get_weather`、`weather.get_air_quality`、`weather.travel_advisor`、`weather.expense_estimator`，全部挂在同一个 `pluginId`（`weather`）下面。

## 两个容易踩的坑

**坑 1：`allowedTools` 要写完整 id。** 因为 `travel_advisor` 和 `get_weather` 现在共享同一个 pluginId，工具的真实能力 id 是 `weather.get_weather`，不是裸的 `get_weather`。这里写错，Skill 内部就找不到这个工具，表现上会是"Skill 选中了，但回答里没有真实数据"。

**坑 2：`@AgentSkill` 类里千万别写 `@AgentTool` 方法。** Skill 设计上就不持有私有工具，写了也不会被采集——记住"Skill 只能借"这条铁律（上一篇讲的）。

## 这个设计巧妙在哪

`@AgentSubAgent` 内部复用的是和 `@Plugin` 完全一样的扫描机制——`@AgentTool` 方法照样会被扫描出来，只是扫描结果的去向不同：

- 长在 `@Plugin` 类上的 `@AgentTool` → 变成独立的 MCP_TOOL 能力，谁都能调
- 长在 `@AgentSubAgent` 类上的 `@AgentTool` → 变成这个 Sub-Agent 的私有 `ownTools`，外面看不到

同一套扫描逻辑，靠"挂在哪个注解下面"决定能力的可见性。不需要学两套 API——这正是 harness 设计上的一个好处：底层只维护一套扫描/装配机制，上层暴露多种语义。

## 小结

- 工具不多、不需要打包管理 → 直接 `withTool`（第 1 篇）
- 需要按业务模块打包、加标签、做版本管理 → `@Plugin` + 嵌套 `@AgentSkill`/`@AgentSubAgent`，一个类搞定一整套能力
- `@AgentSkill`/`@AgentSubAgent` 也可以单独用（不嵌套），各自注册成独立的单能力插件

下一篇会把视角拉高一层：除了写代码注册，插件还能怎么"打包"——纯代码构造、包扫描、文件系统目录三种方式一次讲完。

---

📌 上一篇：[03. Sub-Agent：自己的模型，自己的工具](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/03-subagent.md) ｜ 下一篇：[05. 插件打包的三种姿势](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/05-plugin-packaging.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
