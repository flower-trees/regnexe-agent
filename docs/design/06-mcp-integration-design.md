# MCP（Model Context Protocol）接入设计

- 状态：**直接配置的 MCP server（第一阶段）和 Plugin 携带 MCP（第二阶段）均已实现并真实验证通过**——用户已明确决定跳过"项目级信任边界"这道前置门槛，先接再说（该风险已记录在 `docs/todo/security-todo.md`）。
- 涉及仓库：`j-langchain`（已有真实 MCP 客户端，未改动）、`regnexe-cli`（`CliMain.java` 新增 MCP 配置读取/连接/`/mcp` 命令、新增 `McpTools.java`、`CliRenderer`/`TerminalCliRenderer` 新增 `mcpToolPreview`/`ConfirmChoice.ALWAYS`）。`regnexe-agent` 最终**零改动**——接入完全通过 `withTool()` 完成。
- 关联文档：`04-session-memory-and-skill-context-design.md`（Skill 共享上下文改造用的是同一套 `CliRenderer` 确认机制，MCP 工具确认复用同一套）、`docs/todo/security-todo.md`（"项目级信任"这个前置依赖记在那份清单里，第 1/2 条）
- 面向读者：下一个要继续推进这份设计、或者要实现它的人或 AI

---

## 一、现状：真实 MCP 客户端已经存在，但完全没接入 regnexe

`j-langchain` 的 `org.salt.jlangchain.rag.tools.mcp` 包有一套相对完整的真实 MCP 协议客户端：

- `McpClient` 读取 `mcpServers` 配置（跟 Claude Code/Claude Desktop 的 `.mcp.json` 格式对齐），通过 `McpConnectionFactory` 建连接：`command` 字段存在 → `McpServerConnection`（stdio，起子进程）；否则按 `type: sse/http` 走 `McpSseConnection`/`McpHttpConnection`。三种标准 transport 都有真实实现。
- `listAllTools()`（对应协议的 `tools/list`）、`callTool(server, tool, args)`（对应 `tools/call`）、`getServerStatuses()`（连接状态）都已经是可以直接调用的公开方法。
- `McpAgentExecutor.Builder` 有 `.tools(McpClient, serverName)` 重载，能把发现到的远程工具包成 `Tool` 对象混进工具列表——但这个桥接**没有任何确认逻辑**，`func` 直接 `mcpClient.callTool(...)`，零 `pauseAction`。

`regnexe-agent`/`regnexe-cli` 源码里搜不到任何 `McpClient`/`McpManager`/`rag.tools.mcp` 引用——`CapabilityType.MCP_TOOL` 这个名字纯粹是"能被模型调用的工具"这个类型的标签，跟真实 MCP 协议毫无关系。今天没有任何路径能在 regnexe 里配一个真实 MCP server 然后被发现使用。

---

## 二、接入原则：MCP 工具当普通 Tool 注册，不新建能力类型

关键事实：`DefaultPluginManager.registerToolCapability(tool, marketplace)` 里，`capabilityId = pluginId = tool.getName()`，直接从 `Tool` 对象取，没有额外的"来源"字段。这意味着——**接入 MCP 不需要改 `CapabilityType`/`CapabilityExecutor`/Search/Plan 任何一行**，只要把每个 MCP 工具包装成一个普通 `Tool` 对象（跟 `FileTools.readFile()`、`ScriptTool.from()` 做的事一样），扔进现有的 `withTool()` 就行。"这是 MCP 来的"这件事完全封装在 Tool 的构造过程里，marketplace 那层不需要知道。

`j-langchain` 那边现成的 `McpClient`（连接管理、`listAllTools()`、`callTool()`）直接复用，不重写协议层；但**不用**它自带的 `.tools(McpClient, group)` 桥接方法（那个是给 `McpAgentExecutor` 直接用、绕过 marketplace 的，而且没有确认逻辑）——regnexe 这边自己写一层适配（下面第五节），复用 `McpClient` 的连接/发现/调用能力，但确认逻辑走 `CliRenderer`。

---

## 三、配置：跟现有 `.rex/` 惯例对齐

`~/.rex/mcp.json`（用户级）+ `<project>/.rex/mcp.json`（项目级），JSON 结构直接用 `McpConfig`/`ServerConfig` 已经认识的形状：

```json
{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_TOKEN": "${GITHUB_TOKEN}" }
    },
    "internal-db": {
      "type": "http",
      "url": "https://internal.example.com/mcp"
    }
  }
}
```

不新发明格式、不新写解析代码——`McpConfig`/`ServerConfig` 已经能直接反序列化这个形状（连环境变量替换 `${VAR}` 都已经有，`McpClient.processEnvironmentVariables()`）。

**Scope 合并**：按 server name 撞名，项目覆盖用户——跟 `enabled.yml` 现在的合并顺序（`Map<Scope,Path>` + `priorityOrder`）完全一样的写法，直接复用同一套模式。

---

## 四、命名空间：`<server>_<tool>`，下划线分隔（点号在真实调用中被证明不可用）

**这一节改过两次，两次都是真实验证之后发现原方案有问题才改的，如实记录过程：**

第一版定的是 `mcp:<server>:<tool>`（冒号分隔、按"类型"加前缀）。核对 `ManifestPluginLoader.java` 后发现现有规则从来不是这样——磁盘 `plugin.yaml` 加载的 Plugin，三种能力类型统一是 `<pluginId>.<名字>`（点号分隔），所以改成了跟它统一的点号版本：`<server>.<tool>`（直接配置）/ `<pluginId>.<server>.<tool>`（Plugin 携带）。

**第二版（点号版）代码写完、接上真实 MCP server（`@modelcontextprotocol/server-filesystem`）真实调用后，deepseek 直接 400**：

```
Invalid 'tools[0].function.name': string does not match pattern.
Expected a string that matches the pattern '^[a-zA-Z0-9_-]+$'.
```

**根因**：点号根本不是合法的 function-calling 工具名字符——这是 OpenAI 自己的 function calling API 文档写明的约束（`^[a-zA-Z0-9_-]{1,64}$`，2023 年功能上线以来没变过，是协议本身的一部分），deepseek 的报错 JSON 结构跟 OpenAI 官方格式一字不差，说明是直接照搬了 OpenAI 的校验逻辑——"OpenAI 兼容"这个定位本身就意味着连这层校验都会照抄。试了 zhipu/moonshot/aliyun 想拿更多厂商的真实数据点，三个 key 都过期/无效，没能验证到这一步；但 j-langchain 里所有厂商适配走的都是同一套 `AiChatInput.Tool` 请求结构、没有任何厂商特有的工具名改写逻辑，加上 OpenAI 协议本身的约束，可以确信这不是 deepseek 一家的问题。

再回去看 `ManifestPluginLoader` 为什么点号能用：它的 `pluginId + "." + name` **只用在 `CapabilityDescriptor.capabilityId` 上**（regnexe 内部给 Search/Plan 用的标识符），真正构造出来给模型的 `Tool` 对象用的是裸名字 `.name(toolName)`，点号从来没被发给过 LLM——capabilityId 和 `Tool.name` 是两个独立维护的字符串。这次的 MCP 适配走的是 `withTool()`（`DefaultPluginManager.registerToolCapability()` 把 `capabilityId = pluginId = tool.getName()` 三者绑死成同一个字符串），没有这种分离的余地，点号会直接进到发给模型的 JSON 里。

**第三版（当前版本）**：分隔符统一改成下划线 `_`（合法字符），capabilityId 和 `Tool.name` 保持同一个字符串不变，继续走 `withTool()`：

- **直接配置的 MCP server**（`.rex/mcp.json`）：`<server>_<tool>`。
- **Plugin 携带的 MCP server**（`<plugin-dir>/mcp.json`）：`<pluginId>_<server>_<tool>`。

代价：跟 `ManifestPluginLoader` 的点号约定不完全一致了——但"统一"的价值是"一眼看出来源"，下划线一样做得到（`fs-test_read_file` 照样看得出来自 `fs-test`），比起"点号统一但真实调用直接 400"，能用是硬约束，风格统一是软目标，两者冲突时选前者。

Plugin 侧的声明方式：`<plugin-dir>/mcp.json`（跟 Claude Code 的 Plugin 目录结构一致——`.mcp.json` 是 Plugin 根目录下的一个独立文件，不是塞进 `plugin.json` 里的字段；regnexe 这边命名成 `mcp.json`，不带前导点）。这个文件**不经过** `PluginManifest`/`ManifestPluginLoader` 的正常加载流程（那条路径要求插件至少有一个 tools/skills/subagents 才会被视为"可加载"，一个只带 `mcp.json` 的 Plugin 会被跳过）——`CliMain` 直接在 `listAllInstalledEntriesInScanOrder()` 已经扫出来的已安装插件目录列表上，各自检查根目录是否有 `mcp.json`，独立于 Skill/Tool/SubAgent 的加载判定，`regnexe-agent`/`ManifestPluginLoader` 完全没有改动。

**均已验证真实可行**（`harness-testbed`，真实 `@modelcontextprotocol/server-filesystem`，真实 deepseek 调用）：直接配置的 `fs-test_read_text_file`、Plugin 携带的 `mcp-fs-plugin_fs-test2_list_allowed_directories` 都被模型正确识别、调用、执行，全程无 400。

---

## 五、确认门槛：默认全部确认，复用刚统一完的 `CliRenderer`

MCP 工具对 regnexe 来说是黑盒（`ToolDesc` 现在没解析 `annotations.readOnlyHint`，见 `docs/todo/security-todo.md` 第 6 条）——**默认所有 MCP 工具调用一律确认**，不做 `BashTool` 那种只读前缀白名单自动跳过。

具体接入方式：

```java
String capabilityId = serverName + "_" + toolDesc.getName();  // Plugin 携带的话前面再加 pluginId + "_"
Tool.builder()
    .name(capabilityId)
    .description(toolDesc.getDescription())
    .params(schemaToParams(toolDesc.getInputSchema()))
    .func(args -> {
        renderer.mcpToolPreview(serverName, toolDesc.getName(), args);
        ConfirmChoice choice = renderer.confirm("call", capabilityId);
        if (choice == ConfirmChoice.PAUSE) { pauseAction.run(); return "Task paused by user."; }
        if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALWAYS) return "Call cancelled by user.";
        var result = mcpClient.callTool(serverName, toolDesc.getName(), argsMap);
        ...
    })
    .build();
```

这就是实际实现（`McpTools.forServer`，`regnexe-cli`）。`renderer.confirm(verb, rememberKey)` 和 `ConfirmChoice.ALWAYS`、`CliRenderer.mcpToolPreview(...)` **都已实现并真实验证**——`rememberKey` 用完整 capabilityId（`<server>_<tool>`），按单个 MCP 工具粒度选择"以后不用再问这个了"，不是整个 server 或整个 MCP 类型一刀切。这是纯内存态、进程退出即失效的会话级记忆，不是持久化规则，细节和已知局限记在 `docs/todo/security-todo.md` 第 5 条。

**`schemaToParams`（JSON Schema → `Tool.params()` 字符串）——照抄了 `j-langchain` 里 `McpAgentExecutor.Builder` 那份私有实现的逻辑**（`Tool.getParams()`不public，规避碰 j-langchain 内部实现，理由跟 `CapabilityExecutor.buildSkillSystemPrompt()` 不碰 `Skill.java` 一样）：取 `inputSchema.properties`，每个属性按 JSON Schema 的 `type` 映射（`integer`→`int`，`number`→`double`，`boolean`→`boolean`，其余含 `string`/`array`/`object`→`String`），拼成 `"name: Type, name2: Type2"` 扁平字符串。

**真实验证发现的已知局限，后来定位到了更准确的根因（已修复）**：接了真实的 `@modelcontextprotocol/server-filesystem` 之后，`read_text_file` 工具有 `head`/`tail` 两个可选 `number` 参数——最初以为问题只是"不区分必填/可选"，模型因此先尝试显式传 `null` 被拒绝，多试了两轮才蒙对（只传 `tail`，不传 `head`）。真正把根因坐实是后来接真实 Playwright MCP server（`browser_find`/`browser_snapshot`）做端到端资讯写作测试时——这几个工具参数类型都对，模型却围着 `target`/`regex` 参数瞎猜了小十轮（`target` 猜过 `"page"`/`"main"`/`"browser"`/`"url"`，`regex` 一直当布尔值传）。去查 `@playwright/mcp` 实际装的包（`playwright-core/lib/coreBundle.js`）里的真实 Zod schema 才发现：`regex` 类型本来就是 `String`（我们的 type 映射没错），`target` 的真实说明是"page snapshot 里某个元素的精确 ref，或者一个唯一选择器，不传就是整页"——**这些约束全部写在 JSON Schema 每个属性自己的 `description`（Zod 的 `.describe()`）里，而 `schemaToParams` 一直在丢弃这部分，只留 `name: Type`**。不区分必填/可选是真问题，但影响更大的是这条：参数类型本身对了也没用，模型没有任何字面依据知道"这个字符串该填 ref 还是填关键词"。

**已修复**（`McpTools.schemaToParams`）：现在同时读 `inputSchema.required` 数组（非必填的类型后面加 `(optional)`）和每个属性的 `description`（截断到 220 字符后原样拼进去）。用上面 `browser_find`/`browser_snapshot` 的真实 schema 做了一次不经过真实 LLM 调用的单元级验证（反射直调 `schemaToParams`，构造跟 `coreBundle.js` 里完全一致的 Map），确认输出变成 `regex: String (optional) — Regular expression to search for in the page snapshot. ... Provide either text or regex, not both.`、`target: String (optional) — Exact target element reference from the page snapshot, or a unique element selector` 这种带约束说明的形式——`target` 那一句原本要花掉模型近十轮试错才能蒙对的信息，现在第一次调用就摆在它面前。

**这条修复本身还不够——真正决定模型看到什么 schema 的是另一层，这次一并挖出来并修了（已实现并真实验证）**：`schemaToParams` 产出的字符串只进了 `Tool.params`，而 `Tool.params` 真正被谁读、怎么用，之前没有顺着代码走到底。追下去发现：`McpAgentExecutor.Builder.toAiTool(Tool tool)`（j-langchain）才是构造真正发给 LLM 的原生 function-calling schema（`llm.setTools(aiTools)`，这条路径真实生效，不是备用/文本 ReAct 那条）的地方，它靠一个私有的 `buildSchema(String params)` 把 `Tool.params` 这段扁平字符串**再解析一遍**——`split(",")` 再 `split(":", 2)`——而且**不管三七二十一把每一个解析出来的属性名都塞进 `required` 数组**，完全不看源 schema 自己的 `required` 声明。这不是这次改的新 bug，是一直存在的旧 bug；只是我这次往 `params` 字符串里塞进长描述文字之后，`buildSchema` 对 `type` 的解析也被连带破坏了（把 `"String (optional) — Regular expression to search..."` 整段当成 type 关键字去匹配，匹配不上就退化成 `"string"`——类型这次歪打正着还对，但纯属侥幸）。

拿真实的 `browser_find` params 字符串手动模拟了一遍 `buildSchema` 的解析逻辑，实测输出：`properties: {'text': {'type': 'string'}, 'regex': {'type': 'string'}}`、`required: ['text', 'regex']`——**两个互斥参数全部被标成必填**。这直接解释了整场测试里反复出现的 "Provide only one of text or regex, not both" 死循环：不是模型瞎猜，是它拿到的 schema 本来就要求它两个都填。也很可能是"模型习惯给所有可选参数塞空字符串"这个之前以为是模型毛病的现象的真正根因——schema 说这些字段必填，模型没有真实值就只能填空字符串占位。

**修法**：给 `j-langchain` 的 `rag.tools.Tool` 加了一个新字段 `parametersSchema`（`Map<String,Object>`，原始 JSON Schema），`McpAgentExecutor.toAiTool()` 现在优先用它，只有它是 `null` 时才回退到 `buildSchema(params)` 的旧逻辑；`McpTools.forServer()`（regnexe-cli）在构造每个 `Tool` 时，除了照常设置 `params`（给可能存在的文本渲染路径用），额外把 `desc.getInputSchema()`——MCP server 返回的原始 schema，一个字节都不改——通过 `.parametersSchema(...)` 直接传下去。这是 j-langchain 里已经有的正确先例（`McpManager.manifestForInput()` 对它自己的 `AiChatInput.Tool` 列表就是这么干的，只是那条路径专供 `McpAgentExecutor.Builder.tools(McpManager, group)` 这个"直连、无确认"的旧入口用，不是我们这条经过 marketplace/确认框的路径）——这次只是把同一个正确模式补到了我们这条路径上。

**真实验证**：`harness-testbed` 上用同样"打开必应搜索 + `browser_find` + `browser_snapshot`"的最小场景复测（同一个 deepseek 模型，跟只做字符串修复那版做严格对照）——`browser_find` 第一次调用就是 `{"text":"result"}`，`regex` 完全没出现（不是空字符串、不是 `false`，是压根没这个 key）；`browser_snapshot` 第一次调用是 `{}`，`target` 同样完全省略。全程 3 次工具调用、0 次报错、0 次瞎猜，跟同一场景第一版修复后"猜 2 轮"、原始未修复"猜 5-10 轮"相比，是质的差别，不是量的差别。

---

## 六、生命周期：直接配置的 server 独立开关，Plugin 携带的跟着 Plugin 走

两种来源的 MCP server，生命周期规则不一样，参照 Claude Code 的分法：

**直接配置的 server**（`.rex/mcp.json`）：独立的 enable/disable，走**新增的 `/mcp enable|disable` 命令**（不是 `/plugin` 的别名，是单独一个命令族——底层照样复用 `enabled.yml` 的读写机制：`EnabledStateLoader`/`EnabledStateWriter` 本来就是通用的 `globalId -> Boolean` 存取，"这是不是 Plugin"这类语义完全在 CLI 层的 key 命名约定里，不在这两个类本身，所以复用零障碍）。Key 格式定为 `<server-name>@mcp`（跟 `<plugin-id>@<marketplace-name>` 是同一种"用 `@` 分隔来源"的写法，`mcp` 在这里相当于一个固定的伪 marketplace 名）。

之所以不直接复用 `/plugin enable|disable`：虽然底层状态机一样，但"MCP server"和"Plugin"对用户来说是不同的心智模型（一个是"外部工具来源"，一个是"能力包"），合在一个命令下用户容易搞混到底是在开关什么；单独一个 `/mcp` 命令族更符合直觉，也跟 Claude Code 自己用 `/mcp` 单独管理是一致的。

**Plugin 携带的 server**（`<plugin-dir>/mcp.json`）：**没有独立开关**，连接状态 100% 跟随所属 Plugin 的 enable/disable，走 `/plugin enable|disable`，不会在 `enabled.yml` 里单独写一条 `<server-name>@mcp` 的 key。这么定是为了避免"Plugin 启用了，但它带的 MCP server 被单独关掉"这种分裂状态——跟 Claude Code 原文描述的"Plugin MCP 在 Session 启动/Plugin 启用/`/reload-plugins`/Plugin 禁用/卸载这几个阶段自动连接和断开"完全对应。

---

## 七、列表/管理命令：`/mcp`

`McpClient` 已经有 `getServerStatuses()`（连接状态）和 `listAllTools()`（每个 server 暴露的工具）——新增 `/mcp` REPL 命令族直接调这两个方法渲染，`j-langchain` 侧零新增代码，纯 CLI 层的事：

```
/mcp list                  列出所有已配置的 server（含 Plugin 携带的）+ 连接状态 + 每个 server 暴露的工具
/mcp enable <server>        启用一个直接配置的 server（Plugin 携带的不接受这个命令，提示去 /plugin enable）
/mcp disable <server>       禁用一个直接配置的 server
```

跟 `/plugin` 是平级的独立命令族，对标 Claude Code 自己的 `/mcp`。

---

## 八、项目级信任边界：用户已明确决定跳过，风险照实记录

"项目级信任边界"是 regnexe 现在完全没有的东西（项目级 `.rex/mcp.json` 一读到就会真的 spawn 子进程/发起网络连接，比任何工具调用确认都早，参见 `docs/todo/security-todo.md` 第 1/2 条）。这本来是接入 MCP 真实连接的前置依赖，但用户已经明确决定"先不管信任边界，直接接"——所以直接配置和 Plugin 携带这两部分**都已经真实接入并验证通过**，这个风险敞口是已知、已接受、已记录的，不是遗漏。Plugin 携带 MCP 反而让这条风险更宽——一个安装的 Plugin 只要带了根目录 `mcp.json`，同样在 `buildAgent()` 阶段就会被连接，不需要用户自己在 `.rex/mcp.json` 里手写配置。

---

## 九、已确认的决定（这轮讨论定下来的，均已实现并验证）

- **`/mcp enable|disable` 是独立命令，不复用 `/plugin`**——底层 `enabled.yml` 读写机制复用，命令入口独立，见第六、七节。**已实现并验证**：`/mcp list` 真实跑通，显示连接状态和工具列表；`/mcp enable|disable` 走独立命令族。
- **Plugin 携带 MCP 要支持**——命名跟 `ManifestPluginLoader` 分组前缀这条规则保持一致，但分隔符改成下划线（见第四节的教训）：直接配置的是 `<server>_<tool>`，Plugin 携带的是 `<pluginId>_<server>_<tool>`。生命周期跟随 Plugin 自己的 enable/disable，不单独开关。**已实现并真实验证**：装了一个自带 `mcp.json` 的测试 Plugin，`/mcp list` 正确显示 `mcp-fs-plugin_fs-test2 (via plugin mcp-fs-plugin@default)`、命名空间三段式 `mcp-fs-plugin_fs-test2_<tool>` 全部正确；真实工具调用（`list_allowed_directories`）被模型正确识别调用，三段式下划线名一样能过 LLM API 校验；`/mcp disable mcp-fs-plugin_fs-test2` 正确拒绝并提示改用 `/plugin disable`；`/plugin disable mcp-fs-plugin@default` 之后 `/mcp list` 正确显示该 server 变成 `[disabled]`。
- **`schemaToParams` 复用 `j-langchain` 已有的实现**（照抄逻辑，不改可见性）。**已实现并真实验证，且已按真实发现的根因修复过两轮**：第一轮补上 `required`/`description`（字符串层面）；第二轮顺着 `Tool.params` 的真正消费者往下挖，发现 j-langchain 的 `McpAgentExecutor.buildSchema()` 本来就无条件把所有参数标成必填，这才是"provide only one of text or regex"死循环和"可选参数塞空字符串"现象的真正主因——修法是给 `Tool` 加 `parametersSchema` 字段，把原始 JSON Schema 直接传给 `toAiTool()`，绕开 `buildSchema()` 这层有损转换。两轮都做了真实 LLM 端到端复测：第一轮 `target` 从猜 5-6 轮降到 2 轮，第二轮（原始 schema 直传）`browser_find`/`browser_snapshot` 第一次调用就完全正确、`regex`/`target` 该省略的地方压根没出现在参数里，3 次工具调用 0 报错跑完全程。详见第五节。
- **多个 MCP server 同时连接失败，只 log，不做特殊处理**——保留 `McpClient.initializeFromConfig()` 现在的行为，**已实现**（`connectMcpServers()` 对每个 server 名字都调用 `McpTools.forServer()`，连接失败的 server 因为不在 `McpClient` 内部的已连接 `servers` map 里，自然贡献零工具，不需要额外的 try/catch）。
- **第三轮（原始 schema 直传后暴露的新根因）：全局 `Long→String` JSON 序列化约定污染了发往 LLM 供应商的请求体**——第二轮修复（`parametersSchema` 直传原始 JSON Schema）上线后，在真实 skill 全流程复测中用 deepseek 实测暴露：`pw_browser_network_request` 工具每次调用都被 deepseek 400 拒绝且**死循环**（工具列表随每次请求一起发送，坏字段不会自愈）：`"Invalid schema for function 'pw_browser_network_request': \"9007199254740991\" is not of type \"number\""`。根因定位：Playwright MCP 该工具的 `index` 参数是 `z.number().int().min(1)`，`zod-to-json-schema` 会自动补 `maximum: Number.MAX_SAFE_INTEGER`（即 `9007199254740991`）；这个值超出 Java `Integer` 范围，Jackson 把它解析成 `Long`；而 j-langchain 的 `JsonUtil.objectMapper` 全局注册了 `Long.class → ToStringSerializer`（本意是保护大号雪花 ID 如文章 ID 传给前端 JS 时不因 `Number` 精度上限丢位），这条规则被无差别复用到了发往 LLM 供应商的请求体序列化上（`BaseAiChatActuator`/`SseBaseAiChatActuator` 里 4 处 `JsonUtil.toJson(request)` 调用点），把这个 schema 数值字段错误地转成了字符串，触发供应商端严格 JSON Schema 校验拒绝整个请求。修复：新增不做 `Long→String` 转换的 `JsonUtil.strictObjectMapper`/`toJsonStrict()`，专供发往第三方供应商的请求体使用（4 处调用点 + `HttpStreamClient`/`HttpSseClient` 的兜底分支全部切换），内部业务响应仍走原 `objectMapper` 不受影响。真实复测：见第十一节。

## 十、还没定下来、需要继续讨论的点

目前没有遗留的设计层面开放问题。剩下的都是"实现了但没来得及真实复测的细节"，记在第十一节里，不是没想清楚。

---

## 十一、验证方式与实际验证结果

跟前面几份设计文档一样的原则——不满足于编译通过，每一条都要在 `harness-testbed` 上真实跑通。测试用真实的 MCP server，不 mock：官方 `@modelcontextprotocol/server-filesystem`（`npx -y @modelcontextprotocol/server-filesystem <dir>`，stdio）。第一阶段（直接配置）测试用 `harness-testbed/.rex/mcp.json` 配了一个叫 `fs-test` 的 server；第二阶段（Plugin 携带）另外装了一个真实 Plugin（`mcp-fs-plugin`，根目录带 `mcp.json`，声明了一个叫 `fs-test2` 的 server），两次测试都指向隔离出来的小测试目录，不是真项目文件，测完都清理干净了。

已验证（✅）/ 未验证：

- ✅ **直接配置的 server 能被发现、连接、列出**：`/mcp list` 真实跑通，`fs-test` 显示 `[connected]`，`listAllTools()` 正确列出 filesystem server 真实暴露的全部 14 个工具，命名空间是 `<server>_<tool>`。
- ✅ **确认门槛默认全部询问**：真实触发 `fs-test_read_text_file` 调用，正确弹出 `call?` 确认框（no/yes/always/pause 四选一），不是自动跳过。
- ✅ **`ALWAYS` 记忆按工具粒度生效、跨多次调用持续**：第一次选 always 后，模型因为真实 MCP server 的参数校验报错重试了 3 次（同一个工具 `fs-test_read_text_file`），全部 3 次都正确跳过了确认框，最终第 4 次调用成功拿到文件内容。
- ✅ **真实工具调用能跑通整条链路**：从 Planner 选中、`McpTools.forServer` 构造的 `Tool` 被模型正确识别调用、到 `McpClient.callTool()` 真实执行、结果正确回传给模型合成最终答案，全程无误。
- ✅ **命名分隔符必须是合法 function-calling 字符**（意外收获，不是原计划要测的，但测出了真问题）：点号版本被 deepseek 400 拒绝，下划线版本真实调用通过。
- ✅ **`schemaToParams`/原生 function-calling schema 丢失参数约束信息，两轮定位根因并修复，均已真实 LLM 端到端复测**：
  - 第一轮：filesystem server 测试先发现"不区分必填/可选"；后续真实 Playwright MCP 端到端测试中，模型围着 `browser_snapshot` 的 `target` 瞎猜近十轮（"page"/"main"/"browser"/"url"），查 `@playwright/mcp` 实际安装包里的真实 Zod schema 确认根因是**每个属性的 `description` 被 `schemaToParams` 整个丢弃**。修复：`schemaToParams` 补上 `required`/`description`。端到端复测：`target` 猜测轮次从 5-6 轮降到 2 轮。
  - 第二轮（更深的根因）：顺着 `Tool.params` 字符串往下追到它真正的消费者——j-langchain `McpAgentExecutor.buildSchema()`，发现它**无条件把每个解析出的参数名标成必填**，完全不看源 schema 的 `required` 声明，这是比"丢描述"更根本的问题，直接解释了"provide only one of text or regex, not both"死循环。修复：给 `rag.tools.Tool` 加 `parametersSchema`（原始 JSON Schema）字段，`toAiTool()` 优先用它、绕开 `buildSchema()` 的有损转换；`McpTools.forServer()` 把 MCP server 返回的 `inputSchema` 原样传下去。端到端复测（同一 deepseek 模型，跟第一轮修复严格对照）：`browser_find`/`browser_snapshot` 第一次调用即完全正确（`{"text":"result"}`、`{}`），`regex`/`target` 该省略时完全不出现在参数里（不是空字符串），3 次工具调用、0 报错跑完全程——质变，不是量变。
- ✅ **Plugin 携带 MCP 全流程**：`/plugin install` 装了一个根目录带 `mcp.json` 的测试 Plugin，`/mcp list` 正确显示 `mcp-fs-plugin_fs-test2 (via plugin mcp-fs-plugin@default)`，三段式命名空间 `mcp-fs-plugin_fs-test2_<tool>` 全部正确（含真实工具调用 `list_allowed_directories`，模型正确识别调用、确认框正常、执行成功——三段式下划线名一样通过 LLM API 校验）。
- ✅ **`/mcp disable` 正确拒绝 Plugin 携带的 server**：`/mcp disable mcp-fs-plugin_fs-test2` 返回 "it has no independent switch, use: /plugin enable|disable mcp-fs-plugin@default"，没有静默写一个不会被读取的 `<server>@mcp` key。
- ✅ **Plugin 携带的 server 生命周期真的跟随 Plugin**：`/plugin disable mcp-fs-plugin@default` 之后 `/mcp list` 正确显示该 server 变成 `[disabled]`（连接被断开）；`/plugin uninstall` 之后整个 Plugin 目录和 `enabled.yml` 里的记录都被清理干净。
- 未做：**配置读取与 Scope 合并**（`~/.rex/mcp.json` + `<project>/.rex/mcp.json` 都配同名 server，确认项目覆盖用户）——两次测试都只配了项目级/单一来源，没有测两层合并。
- 未做：**命名空间不撞车**（两个 server 都有一个叫 `read_file` 的工具，确认 `fs-a_read_file`/`fs-b_read_file` 不冲突；或者一个 Plugin 携带的 server 名字跟一个直接配置的 server 撞名）——这次每次只接了一个 server 来源。
- 未做：**直接配置的 `/mcp enable|disable` 真实切换生效**——Plugin 携带这条路径的"disable 生效"验证过了（走 `/plugin disable`），但直接配置这条路径自己的 `/mcp disable <server-name>` 没有单独复测"disable 之后 rebuild、确认工具从候选消失"。
- 未做：**多 server 同时失败，一个正常一个连不上**——两次测试都没有故意配一个错的 server 一起测。
