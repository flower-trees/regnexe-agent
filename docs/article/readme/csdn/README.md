# Regnexe 实战系列（CSDN 文章，共 10 篇）

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.flower-trees/regnexe-agent"><img src="https://img.shields.io/maven-central/v/io.github.flower-trees/regnexe-agent?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="https://github.com/flower-trees/regnexe-agent/blob/master/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x"/>
</p>

完整文档：[English README](https://github.com/flower-trees/regnexe-agent/blob/master/README.md) ｜ [中文文档](https://github.com/flower-trees/regnexe-agent/blob/master/README_zh.md)

每篇对应仓库 [`src/test/java/.../example/readme/ExampleReadme*Test.java`](https://github.com/flower-trees/regnexe-agent/tree/master/src/test/java/org/salt/regnexe/agent/core/example/readme) 中一个真实可运行的示例，代码全部来自仓库本身，不是 PPT 代码。

| # | 文章 | 对应示例 |
|---|------|----------|
| 00 | [别再给 LLM 写 if-else 了！我用 Java 写了一个能自己规划、执行、反思的 Agent 框架](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/00-intro.md) | 系列开篇 |
| 01 | [Java 接入 LLM Agent 有多快？我数了一下，9 行代码](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/01-multi-tool.md) | [`ExampleReadme01MultiToolTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme01MultiToolTest.java) |
| 02 | [Skill 和 Sub-Agent 到底有什么区别？一个细节看懂](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/02-skill.md) | [`ExampleReadme02SkillTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme02SkillTest.java) |
| 03 | [子任务想换个便宜模型跑？Sub-Agent 这样设计](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/03-subagent.md) | [`ExampleReadme03SubAgentTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme03SubAgentTest.java) |
| 04 | [一个类，一次注册，搞定 2 个工具 + 1 个 Skill + 1 个 Sub-Agent](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/04-plugin-annotation.md) | [`ExampleReadme04PluginAnnotationTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme04PluginAnnotationTest.java) |
| 05 | [插件加载方式选不对，团队迟早要吵起来](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/05-plugin-packaging.md) | [`ExampleReadme05PluginPackagingTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme05PluginPackagingTest.java) |
| 06 | [能力市场换成数据库要改多少代码？答案：一个接口，零侵入](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/06-marketplace.md) | [`ExampleReadme06MarketplaceTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme06MarketplaceTest.java) |
| 07 | [Agent 的"记忆"为什么要拆成三层？混在一起会出大问题](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/07-three-layer-memory.md) | [`ExampleReadme07ThreeLayerMemoryTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme07ThreeLayerMemoryTest.java) |
| 08 | [长任务说停就停？大部分 Agent 框架根本做不到](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/08-pause-resume.md) | [`ExampleReadme08PauseResumeTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme08PauseResumeTest.java) |
| 09 | [Agent 跑出错误结果，你怎么排查？先解决"黑盒"问题](https://github.com/flower-trees/regnexe-agent/blob/master/docs/article/readme/csdn/09-observability.md) | [`ExampleReadme09ObservabilityTest`](https://github.com/flower-trees/regnexe-agent/blob/master/src/test/java/org/salt/regnexe/agent/core/example/readme/ExampleReadme09ObservabilityTest.java) |

项目地址：https://github.com/flower-trees/regnexe-agent
