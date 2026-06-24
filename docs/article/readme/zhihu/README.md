# Regnexe 实战系列（知乎文章，共 10 篇）

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="https://github.com/flower-trees/regnexe-agent/blob/master/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x"/>
</p>

完整文档：[English README](https://github.com/flower-trees/regnexe-agent/blob/master/README.md) ｜ [中文文档](https://github.com/flower-trees/regnexe-agent/blob/master/README_zh.md)

内容和 [CSDN](https://github.com/flower-trees/regnexe-agent/tree/master/docs/article/readme/csdn)、[掘金](https://github.com/flower-trees/regnexe-agent/tree/master/docs/article/readme/juejin)、[开源中国](https://github.com/flower-trees/regnexe-agent/tree/master/docs/article/readme/oschina) 系列同一套技术主题、同一批真实可运行示例，但写法更贴近知乎"先说结论 + 观点讨论"的风格。每篇对应仓库 [`src/test/java/.../example/readme/ExampleReadme*Test.java`](https://github.com/flower-trees/regnexe-agent/tree/master/src/test/java/org/salt/regnexe/agent/core/example/readme) 中一个真实可运行的示例。

| # | 文章 | 对应示例 |
|---|------|----------|
| 00 | [为什么大多数所谓的「AI Agent」，本质上只是一次工具调用？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/00-intro.md) | 系列开篇 |
| 01 | [Java 调用大模型工具，真的需要为每个工具建一个类吗？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/01-multi-tool.md) | [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) |
| 02 | [Agent 框架里的 Skill 和 Sub-Agent，到底有什么本质区别？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/02-skill.md) | [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java) |
| 03 | [多 Agent 系统里，子任务该不该用更便宜的模型？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/03-subagent.md) | [`ExampleReadme03SubAgentTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java) |
| 04 | [一个注解，能不能同时声明 Tool、Skill、Sub-Agent？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/04-plugin-annotation.md) | [`ExampleReadme04PluginAnnotationTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java) |
| 05 | [插件该怎么加载，才能让开发和运维都不吵架？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/05-plugin-packaging.md) | [`ExampleReadme05PluginPackagingTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme05PluginPackagingTest.java) |
| 06 | [能力市场换成数据库，对架构设计意味着什么？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/06-marketplace.md) | [`ExampleReadme06MarketplaceTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java) |
| 07 | [Agent 的「记忆」，为什么不能只用一个 Map 存？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/07-three-layer-memory.md) | [`ExampleReadme07ThreeLayerMemoryTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme07ThreeLayerMemoryTest.java) |
| 08 | [长任务的暂停-恢复，工程上到底难在哪？](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/08-pause-resume.md) | [`ExampleReadme08PauseResumeTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme08PauseResumeTest.java) |
| 09 | [Agent 系统出问题怎么排查？聊聊可观测性设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/zhihu/09-observability.md) | [`ExampleReadme09ObservabilityTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme09ObservabilityTest.java) |

项目地址：https://github.com/flower-trees/regnexe-agent
