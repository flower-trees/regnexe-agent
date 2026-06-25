# 支持脚本型插件：让企业已有脚本快速转成 Agent 能力

企业内部常常有大量脚本资产。

这些脚本可能来自运维、数据、测试、财务、运营等团队：

- `.sh` 运维检查脚本
- `.py` 数据处理脚本
- `.groovy` 内部自动化脚本
- 日志分析脚本
- 报表生成脚本
- 环境诊断脚本

过去，这些脚本通常只能由人手动执行，或者嵌入某个固定流水线。

Regnexe 支持脚本型插件，让这些资产可以快速转成 Agent 可调用能力。

## 脚本目录即插件

目录插件可以这样组织：

```text
ops-plugin/
  plugin.yaml
  tools/
    check_service.sh
    check_service.yaml
    analyze_log.py
    analyze_log.yaml
```

`plugin.yaml` 描述插件元数据，工具同名 YAML 描述脚本能力。

例如：

```yaml
description: 检查指定服务状态
params: "serviceName: String -- 服务名称"
tags: [ops, health-check]
```

Regnexe 加载目录后，会把脚本包装成 Tool，再进入 Marketplace。

## 适合哪些企业资产

脚本型插件尤其适合：

- 运维诊断
- 日志分析
- 数据抽取
- 报表生成
- 质量检查
- 临时业务自动化

这些脚本往往已经被企业验证过，有稳定逻辑和输出格式。

把它们接入 Agent，可以让业务人员通过自然语言触发这些能力。

## 从人工执行到 Agent 编排

过去用户可能需要这样做：

```text
登录机器 → 找脚本 → 输入参数 → 看输出 → 再手动整理结论
```

接入 Agent 后，可以变成：

```text
请检查订单服务状态，并分析最近 100 行错误日志。
```

Agent 可以选择对应脚本工具，执行后汇总结果。

这提升了脚本资产的可用性。

## 与能力市场结合

脚本型能力进入 Marketplace 后，就和 Java Tool、Skill、SubAgent 一样被统一管理。

```text
[Search Result] Found: check_service, analyze_log
[Plan Result] Selected: [check_service, analyze_log]
```

这意味着脚本不再是孤立文件，而是企业 Agent 能力市场的一部分。

## 商业价值

第一，复用已有脚本资产。

企业不需要重写所有自动化能力。

第二，降低接入门槛。

非 Java 团队也可以通过脚本贡献 Agent 能力。

第三，加速场景落地。

已有脚本可以快速包装成工具，进入 Agent 编排流程。

第四，适合运维和数据场景。

这些团队通常已有大量脚本积累，转化价值高。

第五，支持渐进治理。

脚本可以先快速接入，再逐步补充描述、参数、标签和权限控制。

## 结语

企业自动化资产不只存在于 Java 服务里，也大量存在于脚本中。

Regnexe 支持 `.sh`、`.py`、`.groovy` 等脚本作为工具加载，让这些资产进入 Agent 能力市场。

这就是 Regnexe 的第十六项核心商业价值：

**它能把企业已有运维脚本、数据脚本和自动化脚本快速转成 Agent 能力。**
