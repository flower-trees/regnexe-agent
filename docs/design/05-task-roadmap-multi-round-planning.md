# 跨轮次任务分解（Task Roadmap）与多轮规划

- 状态：**现状已核实、方向已讨论定调，具体设计细节还没有像 04 号文档那样逐项敲定**——这份先把问题和方向记下来，后续优化时再展开成具体方案
- 涉及仓库：`regnexe-agent`（`task.state.TaskExecutionState`、`task.state.plan.PlanOutput`、`task.worker.TaskPlanner`、`event.EventType`）、`regnexe-cli`（`CliEventListener`/`CliRenderer` 渲染）
- 关联文档：跟 01-04 号都是平级的独立主题，没有直接依赖关系
- 背景：讨论"Plan 现在是不是只规划一轮 Execute、能不能兼容多轮"时查证的结论——多轮本身系统层面是支持的（Reflector 驱动的外层循环），但"Plan"这一步本身没有跨轮次的持久化任务分解对象，每轮都是从候选能力+历史记录重新推导"这一轮干什么"，没有一个"整个任务分几步、现在到第几步"的结构化数据。对比 Claude Code 的 `TodoWrite`、Codex 的 `update_plan`，这是一个真实缺口。
- 面向读者：下一个要展开这个方向具体设计的人或 AI

---

## 一、现状（已核实）

`PlanOutput` 的 javadoc 原话：**"Planner output for one round"**。字段只有 `narrative`/`selectedCapabilityIds`/`capabilityInputDescriptions`/`resultStrategy`/`finalAnswerRequirements`/`iterationsHint`——没有任何字段承载"整个任务的多步骤分解"。

多轮循环靠的是外层机制，不是 Plan 自己：

```
Search → Plan → Execute → Reflect
                              ↓
                      CONTINUE？绕回 Search，Plan 从头重新推导这一轮该干什么
                      FINISH？结束
```

`TaskPlanner` 每次被调用都是"根据候选能力 + 历史执行记录（`ExecutionRecordFormatter`）+ 上一轮 `Reflector` 给的 `ReflectionHint`，重新决定这一轮的 `narrative`/`selectedCapabilityIds`"——没有一个持久对象记着"这是第几步、还剩几步、哪几步已经做完了"。

## 二、Claude Code / Codex 怎么做（一般认知，非 archived 文档结论）

说明信度：`docs/harness/` 里 archived 的四家分析文章专门讲 Marketplace/Plugin，没有覆盖 Planning/推理循环这条线，下面是对这两个工具实际行为的一般认知，不是查 archived 文档得出的。

两家都是同一个思路：给模型一个 `TodoWrite`（Claude Code）/ `update_plan`（Codex）工具，**模型自己决定要不要用**（不是系统强制的独立阶段），遇到"明显有好几个步骤"的任务时主动创建一个结构化清单（每项：描述 + 状态 pending/in_progress/completed），做完一步就更新一次。这个清单：

- 持久存在、贯穿整个任务，不是每轮重新生成
- 对用户可见（CLI 渲染成勾选列表）
- 底层执行循环还是扁平的 function-calling 循环，"计划"只是循环里模型可选调用的一个工具，不是外部强加的固定阶段

## 三、方向（已讨论定调，细节待展开）

**不推翻 Search→Plan→Execute→Reflect**——这套结构是 01 号文档讨论 DeepSeek "everything is a loop" 时明确决定保留的东西（"没有理由做成可组合的，属于过度设计"），这次要解决的不是"循环结构要不要变"，是"缺一个跨轮次持久化的任务分解对象"，加法不是重构。

初步方向，四点：

1. `TaskExecutionState` 加一个可选的 `taskRoadmap` 字段（跟已有的 `rounds` 列表平级）——步骤列表，每项带描述和状态。`TaskStore` 本来就在存 `TaskExecutionState`，不需要新的存储层
2. `TaskPlanner` 遇到明显多步骤的任务时创建/更新这个 roadmap——先建一次，之后每轮只更新状态，不是每轮重新决定整个任务怎么分解
3. 新增一个 `EventType`（比如 `TASK_ROADMAP_UPDATED`），`CliEventListener` 接上以后能渲染出类似 Claude Code 的勾选清单
4. 不是每个任务都要建 roadmap——参照 Claude Code 自己的指导原则（复杂到一定程度才值得建），简单任务不强加这层结构

## 四、还没定的东西（后续优化时要展开）

- `taskRoadmap` 具体数据结构（字段、状态枚举值）没有细化
- "什么算复杂到需要建 roadmap"的判断标准没定——是 LLM 自己判断，还是给个显式的阈值（比如预估轮数）
- roadmap 更新的触发时机——每轮 Plan 都检查一遍要不要更新，还是只在 Reflect 判断"这一步做完了"时才更新
- 要不要允许 roadmap 被推翻重建（任务执行中发现原计划不对，需要重新分解）
- `iterationsHint`（`PlanOutput` 已有字段）跟新的 roadmap 之间是什么关系，会不会重复
- 跟 04 号文档"压缩策略"的交互——roadmap 本身要不要参与 Session 记忆的压缩，还是完全独立于三层记忆之外

## 五、验证方式（初步设想，未细化）

用一个真实的多步骤任务（比如"重构 5 个文件"这种明确有可枚举步骤的任务）跑一遍，对比有 roadmap 和没有 roadmap 两种情况下：总 LLM 调用次数、每轮 Plan 的输入 token 数、任务完成的稳定性（会不会漏做某一步）。
