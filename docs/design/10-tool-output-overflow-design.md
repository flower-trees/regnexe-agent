# 工具结果溢出设计：预览 + tmp 文件指针，不再丢数据

- 状态：**已实现**
- 涉及仓库：`regnexe-cli`（`tools/BashTool.java`、`tools/McpTools.java`、`tools/WorkspaceContext.java`，新增 `tools/ToolOutputOverflow.java`）
- 关联文档：09 号文档（`docs/design/09-context-memory-compaction-design.md`）① 部分点名的"头部硬截断丢数据"问题，这次是同一类问题在**单次工具调用结果**这个维度上的解法；也依赖 09 号会话里做的"Reflector/TaskPlanner 不再回读跨轮原始记录"这个前置改动（见下文"为什么现在能安全清理"）
- 背景：09 号文档梳理清截断问题后，发现还有一处更根本的：不管 Reflector/TaskPlanner 怎么读历史，**单次工具调用本身的结果一旦很大，就已经在源头被截断丢失了**——bash 10,000 字符、MCP 工具完全没有上限。截了就是丢了，模型永远看不到被砍掉的那部分，不管后面账本设计得多好。

---

## 一、核心思路

不再"截断丢弃"，改成"**预览 + 指针**"：
- 结果在阈值（`ToolOutputOverflow.MAX_INLINE_CHARS`，2000 字符）以内：原样返回，不做任何处理。
- 超过阈值：只把前 2000 字符放进模型看到的结果里，**完整内容写进系统 tmp 文件**，附上一句明确的指针提示（"完整内容在 XXX，用 read_file 或 bash cat/grep 自己去看"）。
- 模型如果真的需要更多信息（比如一段报错 traceback 的关键行在结尾），自己决定要不要去读那个文件，读多少——不是我们代码这边猜"该保留头还是保留尾"。

这不是新发明的思路，是 Claude Code 等主流 agentic 工具已经在用的模式（长 bash 输出截断但给出"完整内容在哪"的指针；大文件读取用 offset/limit 分页，而不是一次性塞进去）——只是这次会话里第一次意识到这个项目完全没有对应机制，一直是纯截断。

---

## 二、几个关键的设计决策，及为什么这么定

### 1. 为什么不能放在 `CapabilityExecutor.recordToolExecution()` 里做（一开始想错的地方）

最初讨论时倾向于在 `CapabilityExecutor` 里统一处理，因为它是所有工具类型（bash/file/MCP）汇总的唯一记录点。但实际去查代码，发现这个想法有个硬伤：

```java
// McpAgentExecutor.Builder
private Consumer<String> observationConsumer;   // onObservation 的类型
```

`onObservation` 是 `Consumer<String>`——**纯被动回调，只能"看"，不能"改"**。`CapabilityExecutor.recordToolExecution()` 就是挂在这个回调上的，它能记录、能落库，但**改不了模型在当轮循环里实际看到的内容**——模型看到的，是工具自己 `.func()` 返回的原始字符串，这个回调只是在旁边围观、记一笔账。

所以真正能控制"模型看到什么"的地方，只能是工具自己的 `.func()` 内部——也就是 `BashTool`/`McpTools` 各自的实现里，而不是下游某个统一的记录点。这也是为什么 `BashTool`/`FileTools` 一直以来都是各自维护自己的截断上限（10,000/8,000），不是历史遗留的偷懒，是这个约束逼出来的。

### 2. tmp 文件放系统 tmp，不放工作区内部

放系统 tmp（`/tmp/`、macOS 上 `/var/folders/...`）而不是 `<workspace>/.rex/tmp/` 这种项目内部目录，好处是操作系统自己会周期性回收（macOS 的 `/private/tmp/` 重启清空，`$TMPDIR` 几天没访问会被系统回收）——不用我们自己在 CLI 启动时扫一遍清理残留。

### 3. 清理策略：`File.deleteOnExit()` + 系统兜底，没有做按轮/按任务的主动清理

`Files.createTempFile(...)` 之后立刻 `tmp.toFile().deleteOnExit()`——JVM 正常退出时自动删除，零额外状态跟踪。没有在 `CapabilityExecutor` 那层做"这一轮结束就删掉本轮产生的文件"这种更精细的清理，原因很直接：**这个功能现在是在单个工具的 `.func()` 内部实现的，天然拿不到"当前是第几轮"这个上下文**，硬要传进去会打乱工具构造的现有结构，收益不成比例。

`deleteOnExit()` 覆盖正常退出；`kill -9` 这种硬杀掉进程的情况，`deleteOnExit()` 不会触发，但系统自己的 tmp 回收机制兜底，可以接受。

### 4. 为什么现在能安全这么清理——依赖同一次会话里的另一个改动

这个设计成立有个前提：**没有任何东西会在很久以后回头去读这些 tmp 文件**。这在今天成立，是因为同一次会话里刚做了一件事——把 `resume`/`--force-resume`/`--continue` 整条链路，以及 `TaskPlanner`/`CapabilityExecutor` 里"跨轮读取原始 `tool_executions`"的 `resumeMode` 分支，全部删掉了（见相关改动，`ExecutionRecordFormatter` 类整个删除）。

如果那条 resume 链路还在，问题就大了：进程被打断、重启后 `--resume`，Planner 想回读老轮次的原始工具调用记录，此时 tmp 文件早被 `deleteOnExit()` 清掉了，指针指向一个不存在的文件——这是真实会发生的 bug，不是假设。现在没有 resume 了，不存在"跨进程回头看"这个场景，按进程生命周期清理是安全的。

### 5. 统一 `read_file` 和 `bash` 的沙盒规则

`WorkspaceContext.resolve()`（`read_file`/`write_file` 用）以前只认工作区根目录白名单，不像 `BashTool` 自己有个 `SAFE_ABSOLUTE_PREFIXES` 允许 `/tmp/` 之类的路径。这次把这份列表挪到 `WorkspaceContext.SAFE_READ_PREFIXES`（公开常量），`BashTool` 的越界检测和 `WorkspaceContext.resolveForRead()`（`read_file` 用）共用同一份，不再各维护一份容易长歪的拷贝——`write_file`/`edit_file` 走的 `resolve()` 不受影响，仍然严格限制在工作区内，没理由让模型往系统 tmp 里写东西，只开放"读"。

---

## 三、具体实现

**新增 `ToolOutputOverflow.java`**（`regnexe-cli/tools/`）：
```java
public static final int MAX_INLINE_CHARS = 2000;

public static String capOrOffload(String text, String label) {
    if (text == null || text.length() <= MAX_INLINE_CHARS) return text;
    Path tmp = Files.createTempFile("rex-" + label + "-", ".txt");
    tmp.toFile().deleteOnExit();
    Files.writeString(tmp, text, UTF_8);
    return text.substring(0, MAX_INLINE_CHARS)
        + "\n\n[... N more chars omitted (total). Full output saved to " + tmp
        + " — read_file (paginated) or bash cat/grep it if you need more.]";
    // IOException（磁盘满/权限问题）时退化成纯截断，不让整个工具调用失败
}
```

**`BashTool.java`**：
- 原来的 `MAX_OUTPUT_CHARS`（10,000，边读边截断丢弃）拆成两层：
  - `MAX_CAPTURE_CHARS`（200,000）：真正的硬上限，防止极端失控输出把内存/磁盘打爆，跟以前一样"边读边截、但持续把流排空避免进程阻塞"。
  - 捕获完成后，若结果超过 `ToolOutputOverflow.MAX_INLINE_CHARS`（2,000），调用 `capOrOffload` 存文件、给指针，不再是直接砍掉后半段。
- `SAFE_ABSOLUTE_PREFIXES` 挪到 `WorkspaceContext.SAFE_READ_PREFIXES`，两处共用。

**`McpTools.java`**：
- MCP 工具结果原来完全没有上限（`tavily_search` 这类调用可能返回任意大的文本），现在同样过一遍 `capOrOffload`。
- 加了 `sanitizeLabel()`：MCP 工具名是服务端定义的，不保证是合法的临时文件名前缀，做一次 `[^a-zA-Z0-9_-]` 清洗。

**`WorkspaceContext.java`**：
- 新增公开常量 `SAFE_READ_PREFIXES`（内容跟 `BashTool` 原来私有的那份一致）。
- `resolveForRead()` 对绝对路径新增一次前置检查：命中安全前缀直接放行，不再要求必须在工作区根目录内——只影响读，`resolve()`（写路径）不变。

---

## 四、验证方式

编译通过（`regnexe-cli` `mvn compile`），实际问题需要真实触发一次超过 2000 字符的 bash/MCP 结果来验证预览+指针+可读回三个环节都对：
1. 跑一个会产生大量输出的 bash 命令，确认返回文本里出现预览 + tmp 文件路径提示。
2. 用 `read_file` 指向那个 tmp 文件路径，确认能读到（验证 `WorkspaceContext.SAFE_READ_PREFIXES` 生效，不被沙盒拦下）。
3. 确认正常退出后该文件被清理（`deleteOnExit`）。

## 五、还没做/暂不处理的

- `FileTools.readFile()` 本身不需要这套机制——它已经是 offset/limit 分页读取，不会一次性把整个文件塞进上下文，不存在"结果太大"这个问题。
- `CapabilityExecutor` 记录进 `ToolExecutionRecord`（进而落库到 `TaskExecutionState`）的，是工具 `.func()` 返回的字符串——也就是说，**溢出后落库的也是"预览+指针"这个短版本，不是完整原文**。tmp 文件被清理之后，这条记录里能看到的信息就永久只有预览部分了（09 号文档已经说明过这是"任务活着的时候零丢失，清理之后退化成截断"的权衡，不是本文档新引入的）。
- 09 号文档里讨论的"轮次太多"（Plan B，轮次级周期性压缩）仍然是独立、尚未实现的问题，这次改动解决的是"单次结果太大"，两个维度互补，不是替代关系。
