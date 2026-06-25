# 支持企业私有能力接入：让已有系统、内部 API 和脚本资产进入 Agent

企业建设 Agent，最大的资产往往不是模型，而是已经存在多年的内部系统。

这些系统里沉淀了大量业务能力：

- CRM 客户查询
- ERP 订单和库存
- OA 审批流程
- 财务报销规则
- 法务合同模板
- 运维脚本
- 数据分析任务
- 内部知识库

如果 Agent 无法接入这些私有能力，它就只能做通用问答，无法真正进入业务流程。

Regnexe 的价值之一，是支持多种企业私有能力接入方式，让已有系统可以逐步变成 Agent 能力。

## 多种能力来源统一进入 Marketplace

Regnexe 不要求企业把能力改造成单一形态。

能力可以来自：

- Java Bean
- 包扫描
- 文件系统目录
- 数据库
- 远程配置
- 脚本文件
- 自定义 PluginManager

进入系统后，它们都会被统一封装成 `CapabilityDescriptor`，再进入 Marketplace。

```text
企业已有系统 / 脚本 / 配置
        ↓
PluginManager
        ↓
CapabilityDescriptor
        ↓
Marketplace
        ↓
Search → Plan → Execute
```

这意味着企业可以保留原有资产，不需要为了 Agent 重写全部系统。

## Java Bean：最快接入业务服务

对于已经在 Spring Boot 系统里的业务服务，最简单的方式是直接注册 Bean。

```java
@Plugin(id = "crm", name = "CRM 插件", description = "客户查询能力")
public class CrmPlugin {

    @AgentTool("根据客户名称查询客户资料")
    public String getCustomerProfile(String customerName) {
        return crmService.queryProfile(customerName);
    }
}
```

业务系统原本就有服务方法，只需要加上插件和工具注解，就可以进入 Agent 能力市场。

这种方式低成本、低侵入，非常适合企业早期试点。

## 包扫描：适合企业插件库

当企业内部有多个插件类时，可以通过包扫描集中加载。

```java
RegnexeAgent agent = regnexeAgentBuilder
        .withScanPackages("com.company.agent.plugins")
        .build();
```

平台团队可以维护一个标准插件包，业务团队只需要按照规范新增插件类，Agent 启动时自动发现。

这适合从项目试点走向平台化管理。

## 文件目录：适合运维和脚本资产

很多企业已有大量脚本资产，例如：

- 数据清洗脚本
- 报表生成脚本
- 运维诊断脚本
- 日志分析脚本
- 内部检查脚本

Regnexe 支持通过文件系统目录加载脚本型工具。

```text
/opt/regnexe-plugins/
  ops-plugin/
    plugin.yaml
    tools/
      check_service.sh
      check_service.yaml
      analyze_log.py
      analyze_log.yaml
```

这样，已有脚本不需要完全重写，就可以被包装成 Agent 可调用能力。

## 数据库和远程配置：适合动态能力平台

当企业发展到平台阶段，能力可能来自数据库或远程配置中心。

例如能力定义存在配置表里：

- capabilityId
- pluginId
- type
- name
- description
- tags
- endpoint
- params

平台可以运行时读取这些配置，构造 `CapabilityDescriptor`：

```java
CapabilityDescriptor cap = CapabilityDescriptor.builder()
        .capabilityId("finance.query_budget")
        .pluginId("finance")
        .type(CapabilityType.MCP_TOOL)
        .tool(queryBudgetTool)
        .build();

marketplace.install(PluginDescriptor.builder()
        .pluginId("finance")
        .name("财务能力插件")
        .capabilities(List.of(cap))
        .build());
```

这让 Agent 能力管理可以和企业已有权限系统、配置中心、审批流程结合。

## 私有能力接入的商业价值

第一，保护既有 IT 投资。

企业不需要推翻原有系统，而是把已有 API、服务和脚本逐步接入 Agent。

第二，缩短落地周期。

已有业务服务可以快速包装成工具，马上进入 Agent 试点。

第三，支持分阶段建设。

早期用 Bean 接入，后期用目录、数据库、远程配置管理能力，路径清晰。

第四，降低迁移风险。

业务系统保持原样，Agent 只是新增一个智能编排入口。

第五，适合企业私有化。

能力可以完全来自企业内部系统，不依赖公开工具市场。

## 结语

企业 Agent 要产生业务价值，必须连接企业自己的能力资产。

Regnexe 通过多种接入方式，让 Java Bean、包扫描、文件目录、数据库和远程配置都能成为 Agent 能力来源。

这就是 Regnexe 的第五项核心商业价值：

**它让企业已有系统、内部 API 和脚本资产可以低成本接入 Agent，转化为可搜索、可规划、可执行的私有能力。**
