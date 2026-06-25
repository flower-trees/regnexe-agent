# 插件加载方式选不好，迟早被同事吐槽——四种姿势全解析

> 「Regnexe 实战系列」第 5 篇（共 10 篇），对应仓库 [`ExampleReadme05PluginPackagingTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme05PluginPackagingTest.java)。上一篇：[04. @Plugin 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/04-plugin-annotation.md)。

## 😤 一个真实的团队矛盾

做平台型项目应该都遇到过这种争论：

> "工具应该写死在代码里，编译期就能查出问题。"
> "不对，运维要能随时加减插件，不能每次都重新编译发版。"

两边都没说错，只是关注点不一样。Regnexe 没有让我们选边站，而是把"插件怎么打包"和"插件怎么加载"拆成了独立的维度，四种方式并存，按场景挑。

## 1️⃣ 代码直打包：PluginDescriptor 才是底层真相

前几篇用的 `withTool`/`withSkill`/`withSubAgent`/`@Plugin`，最终都会落到同一个东西上：一个装着若干 `CapabilityDescriptor` 的 `PluginDescriptor`。想完全手动控制，`PluginDescriptor.builder()` 直接开放了这条路：

```java
PluginDescriptor tripPlugin = PluginDescriptor.builder()
        .pluginId("trip-plugin")
        .version("1.0")
        .name("Trip Plugin")
        .description("Bundles a tool, a skill, and a sub-agent for trip planning")
        .tool(weatherTool)                       // -> trip-plugin.get_weather
        .skillConfig(travelAdvisor)              // -> trip-plugin.travel_advisor
        .subAgentConfig(expenseEstimator)        // -> trip-plugin.expense_estimator
        .build();

regnexeAgentBuilder.withPlugin(tripPlugin) ...
```

一个 `PluginDescriptor`，三种能力类型，id 自动按 `pluginId + "." + name` 拼好。适合能力定义来自数据库、配置中心，需要在运行时动态拼装的场景。

> ⚠️ 踩坑提醒：三种能力共用一个 `pluginId` 时，Skill 的 `allowedTools` 必须写完整能力 id——`"trip-plugin.get_weather"`，不是裸的 `"get_weather"`。

## 2️⃣ 包扫描：让插件"自己长出来"

插件类已经按 `@Plugin`/`@AgentSkill`/`@AgentSubAgent` 写好了，不用一个个 `new` 出来注册，直接扫包：

```java
regnexeAgentBuilder.withScanPackages("com.example.plugins") ...
```

`DefaultPluginManager` 会在指定包下找出所有带这三种注解的类，自动实例化、自动注册。适合插件数量较多、希望"加一个类就自动生效"的场景。

## 3️⃣ 文件系统目录：运维真正想要的那种

回到开头那个争论，运维要的"不重新发版就能加减插件"，靠纯文件目录解决：

```
/opt/regnexe-plugins/
  weather-plugin/
    plugin.yaml              ← 元数据
    tools/
      get_weather.sh         ← 脚本工具
      get_weather.yaml       ← 配套说明
    skills/
      advisor/SKILL.md       ← Skill
    subagents/
      planner/AGENT.md       ← Sub-Agent
```

```java
regnexeAgentBuilder.withDirectory("/opt/regnexe-plugins") ...
```

加一个插件文件夹、删一个插件文件夹，应用都不用重启重新发布。

## 🧪 实测验证：不是只讲道理

仓库里每种方式都有真实测试，比如包扫描：

```java
DefaultPluginManager mgr = new DefaultPluginManager();
mgr.scanPackages("org.salt.regnexe.agent.core.example.testplugins");
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(mgr);

CapabilityDescriptor cap = marketplace.resolveDescriptor("test-weather-plugin.get_weather");
Assert.assertNotNull(cap);   // 扫描成功，能力已注册
```

文件系统目录加载也是同理，临时建一个目录、写好 `plugin.yaml` 和脚本文件，`addDirectory(...)` 之后直接能 `resolveDescriptor` 拿到对应能力。

## 📋 四种方式怎么选

| 方式 | 适合场景 |
|---|---|
| `withTool`/`withSkill`/`withSubAgent` | 临时脚本、PoC、动态生成的简单能力 |
| `@Plugin` + 嵌套注解 | 业务模块固定、需要打包管理和版本控制 |
| `PluginDescriptor.builder()` | 能力定义来自 DB/配置中心，运行时动态拼装 |
| 包扫描 `withScanPackages` | 插件类已写好，希望自动发现注册 |
| 文件系统目录 `withDirectory` | 运维要随时增删插件，不接受重新发版 |

它们不是互斥关系，一个应用里完全可以同时用——核心业务工具用 `@Plugin` 写死，运营临时配置的能力走目录加载。这套 harness 在这件事上的态度很明确：不替你做选择，只把每个选项的成本降到最低。

---

你们团队的插件管理最后是怎么落地的？欢迎评论区吐槽踩过的坑，点个赞再走 👍

📌 上一篇：[04. @Plugin 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/04-plugin-annotation.md) ｜ 下一篇：[06. 能力市场换成数据库](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/juejin/06-marketplace.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
