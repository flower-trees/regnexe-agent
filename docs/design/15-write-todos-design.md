# write_todos：执行器自维护的任务清单（可选，不是强制规划阶段）

- 状态：**已实现**
- 涉及仓库：`regnexe-agent`（`TaskExecutionState`/`TodoItem`/`TodoStatus`/
  `CapabilityExecutor`/`EventType`/`AbstractEventListener`）、`regnexe-cli`
  （`CliRenderer`/`TerminalCliRenderer`/`CliEventListener`）

## 一、背景

对比同项目 Python 版（`regnexe-agentscope`，基于 deepagents）发现：deepagents 的默认
agent loop 根本没有"先规划再执行"这个独立阶段——跟 Claude Code 自己的 agent loop 一
样，是一个直来直去的单循环，TodoWrite 只是一个普通工具，模型自己判断简单任务要不要
调。查了 `regnexe-agentscope` 的 `SkillSpec.allowed_tools` 文档注释后确认，Python 版
对 SKILL 的隔离模型本来就跟 Java 版不一样（skill 永远共享 agent 全部工具，`allowed_tools`
只是展示用的提示，不是真正的工具收窄），所以 Java 版 `TaskPlanner` 的"CAPABILITY
DEPENDENCIES"规则（展开 skill 的 `allowedTools` 进 `selectedCapabilityIds`）要解决的
工具收窄问题，在 Python 的新架构里根本不存在——不是迁移漏做，是问题本身消失了。

但这不代表 Java 版的 round 级 Planner 就完全够用：一个 round 内部，Execute 阶段可能
要跑几十次工具调用（真实见过 robot-article-writing 一个任务 93 次工具调用、跑 8 个多
小时），round 级的 narrative 只写"做什么、大致顺序"，不会细到每一步——这部分内部步骤
目前完全没有可见的自我追踪机制。参考 Claude Code 自己的做法（TodoWrite 是普通工具，
模型自己决定要不要用），给 Execute 阶段的内层循环加一个同等地位的 `write_todos` 工具，
而不是在 Plan 之前重新插入一个强制规划阶段。

## 二、设计

### 1. 状态存在 `TaskExecutionState`，不是临时内存

`TodoStatus`（`pending`/`in_progress`/`completed`）+ `TodoItem`（`content` +
`status`），作为 `TaskExecutionState.todos` 字段，跟 `rounds`/`priorSteps` 一样随每轮
`taskStore.save(state)` 持久化——这样 `/resume` 恢复一个未完成任务时能看到当时还剩哪
些待办，而不是每次新开一轮就清空。

### 2. 工具建在 `CapabilityExecutor` 里，不是 regnexe-cli 的 `tools/`

`Tool.func` 的签名是 `Function<Object, Object>`，只能拿到模型传的原始参数，没有任何
办法访问当前的 `TaskExecutionState`（`BashTool`/`FileTools` 不需要这个，它们是纯函数，
闭包里只装了 workspace/renderer）。`write_todos` 天生有状态——得知道"这是哪个任务的
todo list"——这个状态只存在于 `regnexe-agent` 域里，regnexe-cli 看不到。所以工具是在
`CapabilityExecutor.process()` 里**每轮现建一个**，跟 `mcpTools`/`subAgents` 一样，闭
包直接拿这一轮的 `state`：

```java
mcpTools.add(buildWriteTodosTool(state, taskStore, listener, taskId, round));
```

无条件加入这一轮的工具列表（不挂在任何 skill 的 `allowedTools` 下，所有轮次都能
调）——跟 Claude Code 的 TodoWrite 一样，是不是调由模型自己判断，不是强制阶段。

### 3. 整表替换语义

每次调用替换整个 todo list（不是增量 patch），跟 TodoWrite 的语义一致。改完立刻
`taskStore.save(state)`，并发一个新事件 `EventType.TODOS_UPDATED`（text 是 todo list
的 JSON 数组），供上层监听展示。

### 4. regnexe-cli 侧只做渲染，不碰状态

`CliEventListener` 接住 `TODOS_UPDATED`，转给 `CliRenderer.todosUpdated(json)`；
`TerminalCliRenderer` 渲染成清单（`☑` 绿 / `◐` 黄 / `☐` 默认色），具体渐染规则见
`regnexe-cli/docs/design/cli-ui-theme.md` 第 4.4 节。

## 三、不做的事

- 不强制每轮都调用——没有验证/校验机制要求模型必须维护 todo list。
- 不做跨任务共享的 todo list——每个 `TaskExecutionState` 自己的 `todos` 字段，任务结
  束就结束。
- 不在 Plan 之前重新插入一个独立的规划 LLM 调用（Java 原来 Planner/Reflector 分离架
  构本身保留不变,这个工具是 Execute 阶段内部的补充，不是替代）。
