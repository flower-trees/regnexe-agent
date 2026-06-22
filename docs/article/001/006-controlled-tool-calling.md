# 受控工具调用机制：让 Agent 能力可用，也要可控

企业使用 Agent，既希望它能调用工具完成任务，也担心它无边界地调用工具。

如果一个 Agent 可以随意调用所有工具，就会带来很多风险：

- 误调用高风险接口
- 泄露不该访问的数据
- 把内部工具暴露给不相关任务
- 在复杂任务中调用错误能力
- 工具数量太多导致规划混乱

因此，企业 Agent 的关键不是“工具越多越好”，而是“工具边界清楚、调用过程受控”。

Regnexe 通过 `allowedTools` 和 `ownTools` 提供受控工具调用机制。

## Skill 的 allowedTools：只能继承指定工具

Skill 适合领域任务，例如合同分析、制度解读、文档处理。

这类能力应该使用一组明确授权的工具，而不是拥有自己的任意工具集合。

例如合同分析 Skill：

```java
SkillConfig contractAnalyzer = SkillConfig.builder()
        .name("contract_analyzer")
        .description("合同条款风险分析能力")
        .systemPrompt("逐条分析合同条款，并汇总风险等级和修改建议。")
        .allowedTools(List.of("analyze_clause"))
        .build();
```

这里 `contract_analyzer` 只能使用 `analyze_clause`。

这对企业很重要。

合同分析能力不应该随意调用财务接口、客户接口或代码工具。它只应该访问完成领域任务所需的最小工具集合。

## SubAgent 的 ownTools：内部工具只给自己用

SubAgent 更适合复杂自治任务。

它可以拥有自己的 `ownTools`，这些工具只在子 Agent 内部可见，不作为上层能力暴露。

例如代码工作区助手：

```java
SubAgentConfig codeWorkspaceAgent = SubAgentConfig.builder()
        .name("code_workspace_agent")
        .description("代码工作区助手")
        .systemPrompt("可以搜索代码、读取文件、运行白名单验证命令，但不能修改文件。")
        .ownTools(List.of(searchCode, readFile, runCommand))
        .build();
```

上层 Planner 只看到 `code_workspace_agent` 这个能力，不会直接看到 `search_code`、`read_file`、`run_command`。

这样做的好处是：复杂工具被封装在子 Agent 内部，外层任务不会被大量细碎工具干扰。

## allowedTools 与 ownTools 的边界

两者解决的问题不同。

```text
allowedTools → Skill / SubAgent 可以继承哪些外部工具
ownTools     → SubAgent 自己内部可见的专属工具
```

对于 Skill，通常不应该有自己的工具和模型，而是继承主 Agent 的模型，并通过 `allowedTools` 使用授权工具。

对于 SubAgent，可以拥有自己的 `ownTools`，必要时也可以有独立模型配置。

这种边界让企业可以更清楚地设计权限：

- 哪些工具可以被多个能力复用
- 哪些工具只能在某个子 Agent 内部使用
- 哪些能力只是领域封装
- 哪些能力是自治执行单元

## 为什么这对企业安全很重要

企业场景中的工具往往不是无害函数。

它们可能访问：

- 客户数据
- 合同文档
- 财务记录
- 业务指标
- 内部代码
- 运维系统

如果没有工具边界，Agent 很容易在错误任务里调用错误工具。

受控工具调用机制可以减少这种风险。

它让企业按最小权限原则配置能力：

```text
合同分析 Skill      → 只能调用条款分析工具
旅行规划 SubAgent   → 只能调用景点和餐厅工具
代码工作区 SubAgent → 只能调用搜索、读取、验证工具
```

这比把所有工具都扔给一个 Agent 更适合生产环境。

## 可治理的工具调用日志

受控工具调用不仅体现在配置上，也体现在执行日志中。

例如：

```text
[TOOL Call   ] R1 contract_analyzer {"input": "条款 2... 条款 5..."}
[TOOL Call   ] R1 [skill:contract_analyzer] analyze_clause {"clause": "条款 2..."}
[TOOL Result ] R1 [skill:contract_analyzer] analyze_clause -> 风险等级：中
```

日志可以清楚展示：

- 哪个能力被调用
- 该能力内部调用了哪个工具
- 工具返回了什么结果

这为审计和排查提供了基础。

## 商业价值

第一，降低误调用风险。

不同能力只能访问自己需要的工具，减少越权调用。

第二，降低规划复杂度。

上层 Planner 不需要面对所有细碎工具，复杂工具可以封装在 SubAgent 内部。

第三，提高安全治理能力。

工具边界、能力边界和日志记录结合后，更容易接入企业权限和审计体系。

第四，支持复杂能力封装。

SubAgent 可以内部使用多个工具，但对外仍然是一个清晰能力。

## 结语

Agent 要进入企业生产环境，必须解决工具调用边界问题。

Regnexe 通过 `allowedTools` 和 `ownTools`，让能力既能使用工具，又不会无边界扩散。

这就是 Regnexe 的第六项核心商业价值：

**它让 Agent 的工具调用变得可控、可治理、可审计，更适合企业安全场景。**
