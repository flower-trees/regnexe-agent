# Skill 与 SubAgent 分层抽象：让企业按复杂度渐进建设 Agent 能力

企业建设 Agent 系统时，最容易走向两个极端。

一种是所有能力都做成简单工具函数。这样早期很快，但一旦任务复杂起来，工具之间的提示词、调用顺序、上下文组织都会散落到主 Agent 里，系统很快变得难以维护。

另一种是所有能力都做成完整子 Agent。这样看起来强大，但每个能力都过重，成本更高，边界也更难治理。

Regnexe 的设计更务实：按复杂度分层。

```text
简单能力 → Tool
领域任务 → Skill
复杂自治任务 → SubAgent
```

这种分层抽象，让企业可以从简单接入开始，随着业务复杂度逐步升级能力形态，而不是一开始就把所有事情做成庞大的 Agent。

## Tool：简单、直接、可复用

Tool 适合单一、明确、可直接调用的能力。

例如：

- 查询天气
- 查询库存
- 获取客户信息
- 调用内部 API
- 执行一个脚本
- 读取某个配置

Tool 的特点是边界清楚，输入输出直接。

```java
Tool weatherTool = Tool.builder()
        .name("get_weather")
        .description("查询指定城市近期天气")
        .params("city: String -- 城市名称")
        .func(city -> "成都：多云转阴，18~25 C，偶有小雨")
        .build();
```

对于企业来说，Tool 是最容易落地的起点。已有 API、已有脚本、已有 Java 方法，都可以先包装成 Tool，快速进入 Agent 能力体系。

## Skill：面向领域任务的能力封装

当一个能力不再只是单次调用，而是需要领域提示词和一组受控工具时，就适合封装成 Skill。

例如合同风险分析：

- 需要法律分析角色设定
- 需要调用条款分析工具
- 需要汇总风险等级
- 需要输出修改建议

这时如果只用 Tool，主 Agent 就必须承担太多领域逻辑。更合适的方式是把它封装成 `contract_analyzer` Skill。

```java
SkillConfig skillConfig = SkillConfig.builder()
        .name("contract_analyzer")
        .description("合同条款风险分析能力")
        .systemPrompt("""
                你是专业合同风险分析助手。
                对用户提供的条款逐条调用 analyze_clause，
                最后汇总风险等级和修改建议。
                """)
        .allowedTools(List.of("analyze_clause"))
        .build();
```

Skill 的商业价值在于：它把领域知识和工具调用边界封装起来，让主 Agent 不需要理解每个领域的细节。

## SubAgent：复杂自治任务的执行单元

当任务需要自己的推理循环、自己的工具集合，甚至自己的模型配置时，就更适合 SubAgent。

例如：

- 商务旅行规划
- 代码工作区分析
- 复杂数据分析
- 多步骤报告生成
- 跨工具的自治流程

SubAgent 可以拥有 `ownTools`，这些工具只在子 Agent 内部可见，不必暴露给上层 Planner。

```java
SubAgentConfig travelPlanner = SubAgentConfig.builder()
        .name("travel_planner")
        .description("商务出差行程规划助手")
        .systemPrompt("根据天气、会议安排和餐厅信息，规划三天商务行程。")
        .ownTools(List.of(getAttractionsTool, getRestaurantsTool))
        .build();
```

这让复杂能力可以独立演进：上层只看到 `travel_planner`，不需要关心它内部调用了多少工具。

## 分层带来的架构价值

Tool、Skill、SubAgent 的分层，让企业可以用统一框架承载不同复杂度的能力。

```text
get_weather         → Tool
contract_analyzer   → Skill
travel_planner      → SubAgent
code_workspace_agent → SubAgent
```

这比“所有东西都是工具”更容易治理，也比“所有东西都是 Agent”更轻量。

企业可以按业务成熟度渐进建设：

1. 先把已有 API 包成 Tool
2. 再把领域流程沉淀成 Skill
3. 最后把复杂自治任务升级成 SubAgent

这种路径符合企业实际建设节奏。

## 商业价值

第一，降低初始接入成本。

简单 API 不需要复杂封装，直接做成 Tool 即可。

第二，提高领域能力复用率。

合同分析、报表分析、客服诊断等领域能力可以沉淀为 Skill，被多个 Agent 复用。

第三，降低复杂任务治理难度。

SubAgent 把复杂流程封装成一个上层能力，内部工具不必全部暴露给主 Agent。

第四，支持能力逐步升级。

一个能力可以从 Tool 起步，业务复杂后升级为 Skill，再进一步演进成 SubAgent。

## 结语

企业 Agent 系统需要的是可演进架构，而不是一次性设计。

Regnexe 通过 Tool、Skill、SubAgent 三层抽象，把不同复杂度的能力放在合适的位置上。

这就是 Regnexe 的第四项核心商业价值：

**简单能力用 Tool，领域任务用 Skill，复杂自治任务用 SubAgent，让企业可以按复杂度渐进建设 Agent 系统。**
