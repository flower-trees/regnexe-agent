# 插件加载方式选不对，团队迟早要吵起来：代码 / 注解 / 包扫描 / 目录怎么选

> 「Regnexe 实战系列」第 5 篇（共 10 篇），对应仓库 [`ExampleReadme05PluginPackagingTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme05PluginPackagingTest.java)。上一篇：[04. @Plugin 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/04-plugin-annotation.md)。

## 一个真实的团队矛盾

做平台型项目的同学应该都遇到过这种争论：

> "工具应该写死在代码里，编译期就能查出问题。"
> "不对，运维要能随时加减插件，不能每次都重新编译发版。"

这两种诉求都对，只是适用场景不一样。Regnexe 这套 harness 没有强行二选一，而是把"插件怎么打包"和"插件怎么加载"拆成了独立的维度，四种方式并存，按场景挑。

## 方式一：代码直打包——`PluginDescriptor` 才是底层真相

前几篇用的 `withTool`/`withSkill`/`withSubAgent`/`@Plugin`，最终都会落到同一个东西上：一个装着若干 `CapabilityDescriptor` 的 `PluginDescriptor`。如果你想完全手动控制，`PluginDescriptor.builder()` 直接开放了这条路：

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

一个 `PluginDescriptor`，三种能力类型，id 自动按 `pluginId + "." + name` 拼好，不用再一个个手写 `CapabilityDescriptor`。这是最"硬核"的方式——适合能力定义来自数据库、配置中心，需要在运行时动态拼装的场景。

> ⚠️ 踩坑提醒：三种能力共用一个 `pluginId` 时，Skill 的 `allowedTools` 必须写**完整能力 id**——这里是 `"trip-plugin.get_weather"`，不是裸的 `"get_weather"`。这个坑在上一篇也提过，因为太容易踩，这里再敲一次警钟。

## 方式二：包扫描——让插件"自己长出来"

如果插件类已经按 `@Plugin`/`@AgentSkill`/`@AgentSubAgent` 写好了，根本不需要一个个 `new` 出来注册，直接扫包：

```java
regnexeAgentBuilder.withScanPackages("com.example.plugins") ...
```

`DefaultPluginManager` 会在指定包下找出所有带这三种注解的类，自动实例化、自动注册。适合插件数量较多、希望"加一个类就自动生效"的场景——前提是类得有 public 无参构造器。

## 方式三：文件系统目录——运维真正想要的那种

回到开头那个争论，运维要的"不重新发版就能加减插件"，Regnexe 用纯文件目录解决：

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

加一个插件文件夹、删一个插件文件夹，应用都不用重启重新发布——这是纯代码方案永远做不到的灵活度。

## 实测验证：三种方式都能跑通

仓库里的测试不是只讲道理，每种方式都有真实验证。比如包扫描：

```java
DefaultPluginManager mgr = new DefaultPluginManager();
mgr.scanPackages("org.salt.regnexe.agent.core.example.testplugins");
SimpleMarketplace marketplace = new SimpleMarketplace();
marketplace.load(mgr);

CapabilityDescriptor cap = marketplace.resolveDescriptor("test-weather-plugin.get_weather");
Assert.assertNotNull(cap);   // 扫描成功，能力已注册
```

文件系统目录加载也是同理——临时建一个目录、写好 `plugin.yaml` 和脚本文件，`addDirectory(...)` 之后直接能 `resolveDescriptor` 拿到对应能力，整个流程全自动。

## 四种方式怎么选

| 方式 | 适合场景 |
|---|---|
| `withTool`/`withSkill`/`withSubAgent` | 临时脚本、PoC、动态生成的简单能力 |
| `@Plugin` + 嵌套注解 | 业务模块固定、需要打包管理和版本控制 |
| `PluginDescriptor.builder()` | 能力定义来自 DB/配置中心，运行时动态拼装 |
| 包扫描 `withScanPackages` | 插件类已写好，希望自动发现注册 |
| 文件系统目录 `withDirectory` | 运维要随时增删插件，不接受重新发版 |

它们不是互斥关系，一个应用里完全可以同时用——核心业务工具用 `@Plugin` 写死，运营临时配置的能力走目录加载。

---

📌 上一篇：[04. @Plugin 注解打包一切](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/04-plugin-annotation.md) ｜ 下一篇：[06. Marketplace 换成数据库，只需要实现一个接口](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/06-marketplace.md)
📌 项目地址：https://github.com/flower-trees/regnexe-agent
