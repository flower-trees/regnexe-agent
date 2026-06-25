# 代码工作区能力可组件化：为企业研发自动化打基础

研发场景是企业 Agent 的重要方向。

开发者希望 Agent 不只是解释代码，还能真正进入工作区：

- 搜索代码
- 读取文件
- 定位实现
- 运行测试
- 总结风险
- 辅助生成修改方案

但代码工作区能力必须受控。不能让 Agent 随意执行命令，也不能让它任意读写文件。

Regnexe 可以把代码工作区能力包装成受控 SubAgent，为企业内部研发自动化打基础。

## 为什么要组件化

如果把 `search_code`、`read_file`、`run_command` 直接暴露给主 Agent，会有两个问题。

第一，工具太细碎，会干扰 Planner。

第二，安全边界不清晰，容易误调用。

更好的方式是把它们封装到一个 `code_workspace_agent` 里。

上层只看到一个能力：

```text
code_workspace_agent
```

内部工具由 SubAgent 自己使用：

```text
search_code
read_file
run_command
```

这就是代码工作区组件化。

## 受控工具设计

代码工作区工具不应该是无限制 shell。

更合理的是白名单工具：

- `search_code`：搜索项目代码
- `read_file`：读取 workspace 内文件
- `run_command`：只允许指定命令 key

例如：

```java
SubAgentConfig codeWorkspaceAgent = SubAgentConfig.builder()
        .name("code_workspace_agent")
        .description("代码工作区助手")
        .systemPrompt("可以搜索代码、读取文件、运行白名单命令；不能修改文件。")
        .ownTools(List.of(searchCode, readFile, runCommand))
        .build();
```

这样，Agent 可以使用研发工具，但不能越过安全边界。

## 适合哪些研发任务

代码工作区 Agent 可以用于：

- 查找某个类或方法在哪里实现
- 阅读指定文件并总结逻辑
- 搜索配置项和调用链
- 运行指定测试
- 汇总失败日志
- 生成变更影响分析

例如用户可以说：

```text
读取 CapabilityDescriptor.java，找出填充 name 和 description 默认值的方法，并运行 pwd。
```

执行过程可能是：

```text
[TOOL Call ] [subagent:code_workspace_agent] read_file {"path": "src/main/java/.../CapabilityDescriptor.java"}
[TOOL Call ] [subagent:code_workspace_agent] run_command {"commandKey": "pwd"}
```

这说明代码工具是受控执行的，而不是任意命令执行。

## 未来可以扩展到自动修复

第一阶段可以只读和验证。

后续可以逐步扩展：

- 受控 patch
- 只允许修改特定目录
- 修改后自动运行测试
- 输出 git diff 摘要
- 提交前等待人工确认

这条路径更适合企业。

先让 Agent 会看、会查、会验证，再逐步让它参与修改。

## 商业价值

第一，提升研发效率。

Agent 可以帮助开发者快速定位代码和验证结果。

第二，降低安全风险。

通过路径限制和命令白名单，避免任意命令和越权文件访问。

第三，适合内部平台化。

代码工作区能力可以作为企业研发助手的基础组件。

第四，便于逐步演进。

从只读分析到受控修改，再到自动验证，可以分阶段建设。

## 结语

研发自动化不是让 Agent 随意操作代码，而是把代码工作区能力变成受控组件。

Regnexe 可以通过 SubAgent 和 ownTools，把搜索、读取、验证等能力封装成 `code_workspace_agent`。

这就是 Regnexe 的第十四项核心商业价值：

**它可以把代码工作区能力组件化，为企业内部研发自动化打下安全可控的基础。**
