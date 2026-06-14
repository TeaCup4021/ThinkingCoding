# Vibe Coding 问题复盘：RAG 自动检索注入功能的实现过程

> 本文记录了在 ThinkingCoding 项目中实现"用户提问时自动通过向量搜索检索相关代码并注入 LLM 上下文"这一功能的过程中，via vibe coding 暴露出的问题、根因分析及预防建议。

---

## 一、功能目标

用户提问代码问题 → 向量化问题 → pgvector 搜索 → 获取源码 → 注入 LLM prompt。

---

## 二、遇到的问题及根因分析

### 问题 1：过度设计的三层降级 —— 两层从不可用

**表现**：设计了三层源码获取策略（MCP context by name → MCP context by file → Filesystem），上线测试后发现前两层全部静默失败。

**根因**：
- **第一层（MCP context by name）**：pgvector 中存储的 `qualified_name` 是从 GitNexus `query` 结果中获取的，其值是 GitNexus 的 **UID** 格式（如 `Method:path:name#n`）。UID 本身是合法的 GitNexus 标识符（通过 `gitnexus context --uid "Method:..."` 可以正确查找），但代码中错误地将其传给了 **`name` 参数**——`name` 参数期望的是符号简称（如 `"setTools"`），收到 UID 格式字符串时 GitNexus 尝试按名称匹配，结果找不到。**数据格式正确，参数名用错了。**
- **第二层（MCP context by file）**：`File:` UID 类型（如 `File:src/.../FileManagerTool.java`）在 MCP `context` 响应中不返回 `startLine`/`endLine`，无法做精准切片。同时，`file` 参数本身没有明确定义行为。

**补充：Method UID 与 File UID 的响应差异**

GitNexus 知识图谱中有不同粒度的节点，它们对 `startLine`/`endLine` 的支持不一致：

| 节点类型 | 代表什么 | 有 startLine/endLine？ | 原因 |
|---------|---------|----------------------|------|
| `Method:` | 一个方法 | ✅ 有 | 方法是有明确边界的代码块，AST 可精确定位 |
| `Class:` | 一个类 | ✅ 有 | 类有声明行到结束大括号的明确范围 |
| `File:` | 整个文件 | ❌ 没有 | File 节点在 GitNexus 中是**关系节点**，用于追踪文件间的 import 依赖，不表示代码块 |

Method UID 的 MCP 响应示例：

```json
{
  "symbol": {
    "uid": "Method:src/.../AppConfig.java:AppConfig.setTools#1",
    "name": "setTools",
    "kind": "Method",
    "filePath": "src/main/java/.../AppConfig.java",
    "startLine": 80,       ← AST 解析出来的精准起止行
    "endLine": 82
  }
}
```

File UID 的 MCP 响应示例：

```json
{
  "symbol": {
    "uid": "File:src/.../FileManagerTool.java",
    "name": "FileManagerTool.java",
    "filePath": "src/main/java/.../FileManagerTool.java"
    // 注意：没有 startLine、没有 endLine、没有 kind
  },
  "incoming": { "imports": [...] }
}
```

项目中的处理逻辑（`RagContextEnricher.extractPosition()`）：

```java
int startLine = intField(symbol, "startLine");   // File UID → 返回 0
int endLine = intField(symbol, "endLine");       // File UID → 返回 0

if (startLine <= 0 || endLine <= 0) {
    // 打印: symbol missing position info, keys=[uid, name, filePath]
    return null;  // 触发 fetchCode() 中的降级逻辑
}
```

降级到 `readFile()` 直接 `Files.readString()` 读全文件。对于 File 节点，**降级到读全文件就是正确的处理方式**——File 节点本来代表的就是整个文件，不需要行号切分。

日志体现：

```
RAG: symbol missing position info, keys=[uid, name, filePath]
RAG: MCP returned null position for uid File:.../FileManagerTool.java
RAG: [File:.../FileManagerTool.java] via filesystem fallback
```

**根本原因**：在设计降级链路之前，没有先单独验证每一层是否可用。三层中两层的假设（"MCP context 接受 name 参数查找符号"、"所有 UID 类型都会返回行号"）都没有经过前置验证。

**预防措施**：
- 设计多层降级之前，先**逐个验证每一层的可工作性**。写一个简单的 main 方法或脚本，分别测试每一层路径
- 对于外部服务（MCP），**先通过 CLI 确认参数格式**，再映射到代码中的参数名和参数值
- 对存储数据的格式有疑问时，**先查数据库看实际存的什么**，而不是根据代码逻辑推导

---

### 问题 2：假设 MCP `context` 工具支持 `content` 参数

**表现**：代码中传入 `args.put("content", true)` 期望获得源码内容，但 MCP `context` 的 schema 并不包含 `content` 参数。虽然 CLI 提供了 `--content` 选项，但 MCP 协议的参数 schema 是独立定义的，两者不一定一致。

**定位过程耗时**：从"为什么返回的内容为空" → 加 debug 日志 → 发现响应中有 `symbol` 对象但缺少 `content` 字段 → 打印 `symbol.keySet()` → 发现只有 `[uid, name, kind, filePath, startLine, endLine]`。前后经过 3 轮测试才定位。

**根因**：**没有确认 MCP 工具的完整参数 schema**。CLI 支持的功能不等于 MCP 接口也暴露了相同的参数。MCP 服务器可以独立定义每个工具的参数白名单，不在白名单中的参数会被静默忽略。

**预防措施**：
- 对于要通过 MCP 调用的工具，**先了解其完整的 inputSchema**。可以通过 MCP 协议列出工具列表，查看每个工具的 `inputSchema.properties`
- **永远不要假设 CLI flag 和 MCP 参数是一一对应的**。CLI 可能通过环境变量、配置文件、或隐式上下文提供额外信息
- 调用外部工具的新参数时，先在代码中加**临时日志打印完整响应**，确认生效后再去掉临时日志

---

### 问题 3：并行调用 MCP 导致 stdio 连接争抢

**表现**：使用 `CompletableFuture` + `ExecutorService`（线程池大小 5）并行调用 MCP `context`，导致 `java.io.IOException: 读取响应被中断`。线程 A 发出的请求的响应被线程 B 读走了，或者 stdio 流被并发访问破坏。

**根因**：**MCP 底层使用 stdio（标准输入/输出）通信**，本质上是一个单线程的管道。多个线程同时从同一个 `InputStream` 读取，会导致数据错乱和连接中断。`MCPClient` 不是线程安全的。

**预防措施**：
- 使用任何基于 stdio/socket 长连接的客户端时，**默认假设它不是线程安全的**，除非明确标注了线程安全
- 需要并发调用时，使用 **串行化（for 循环依次调用）或消息队列（单消费者线程）**
- 如果确实需要并发，应确认客户端是否支持多路复用（如 HTTP/2 或独立的连接池）

---

### 问题 4：关键错误日志使用 DEBUG 级别

**表现**：RAG 管线全部静默失败（全部走 filesystem fallback），但终端看不到任何错误信息。因为 MCP 调用失败、内容提取失败等关键错误都用 `log.debug()` 输出。

**定位过程耗时**：用户提问 → 观察 LLM 仍然自己读全文件 → 怀疑 RAG 没生效 → 改日志级别 → 重新编译 → 重新测试 → 才能看到错误。反复了至少 2 轮。

**根因**：**"静默降级"设计过度**。静默降级是好的用户体验（用户不应因 RAG 挂了而打断对话），但开发调试阶段需要有可见的错误信息。代码中所有降级路径的异常和失败原因都用 `debug` 级别，导致运行时完全不可见。

**预防措施**：
- 降级逻辑中的**关键决策点**应该使用 `info` 级别："为什么降级了？被降级的环节具体是什么错误？"
- 可以将**日志级别分层**：开始/结束用 `info`，降级用 `info`（带原因），内部细节用 `debug`
- 开发验证阶段，临时把所有降级日志提到 `info`，确认正常后再降低
- SLF4J 支持运行时切换日志级别（通过配置文件），不需要重新编译

---

### 问题 5：通过完整的端到端测试来发现问题

**表现**：每次发现问题都是"启动完整项目 → 输入提问 → 观察日志 → 发现不对劲 → 分析 → 改代码 → 重新启动 → 再测试"。每一轮耗时 2-5 分钟。

**根因**：**没有对 RAG 管线的独立组件做单元级验证**。`fetchViaMcpUid()`、`extractContent()`、`readFileLines()` 这些方法都可以在 1 秒内独立测试，但每次都是通过完整的 `mvn exec:java` 启动 → 对话 → 观察。

**预防措施**：
- 关键管线组件应该加一个 **`main` 方法或独立测试用例**，允许脱离完整系统快速验证
- 外部依赖（MCP、数据库、API）的集成点，先写**最小可运行脚本**验证连通性和数据格式
- 开发顺序应该是：**验证外部契约 → 实现组件 → 集成 → 端到端**，而不是：实现 → 集成 → 端到端 → 发现契约不对

---

### 问题 6：数据模型假设与实际情况不符

**表现**：代码中假设 pgvector 存储的是 Java 全限定类名（如 `com.thinkingcoding.config.AppConfig`），但实际存储的是 GitNexus UID 格式（`Method:path:name#n`、`File:path`、`Class:path:name`）。UI 格式本身是合法的 GitNexus 标识符，但只能通过 `uid` 参数查找。代码最初使用 `name` 参数去查 UID 格式的字符串，导致查找失败。

同时，`kind` 字段恒为 `CLASS`，即使 UID 明确标注了 `Method:` 或 `File:`。说明索引阶段的 `CodeGraphSymbol.getKind()` 对非类节点没有正确设置类型。

**根因**：**没有在开始写 RAG 代码之前，先检查 pgvector 中存储的数据格式**。基于对索引代码的逻辑推导得出了错误的格式结论。

**预防措施**：
- 在消费任何存储数据之前，**先去数据库/存储中查看实际存储的内容**。一条 `SELECT * FROM graph_embeddings LIMIT 3` 就能避免这个问题
- 代码中对数据的假设应写成**断言或校验**，一旦假设不成立立刻暴露

---

## 三、Vibe Coding 高效实践建议

基于本次开发过程中暴露的问题，总结以下实践建议：

### 3.1 外部依赖先验证

| 操作 | 耗时 | 时机 |
|------|------|------|
| `gitnexus context --content -u "UID"` 验证 CLI | 10 秒 | 写代码前 |
| 查 pgvector 数据格式 `SELECT * LIMIT 3` | 10 秒 | 写代码前 |
| 确认 MCP 工具 inputSchema | 1 分钟 | 写代码前 |

这三步做完了再写代码，可以避免 50% 以上的返工。

### 3.2 关键管线加独立测试入口

```java
// 不启动完整项目，只测试 RAG 管线
public static void main(String[] args) {
    var enricher = new RagContextEnricher(embedding, store, mcp, server, root, config);
    String result = enricher.enrich("AppConfig.setTools 方法做了什么？");
    System.out.println(result);
}
```

### 3.3 "失败可见"优先于"静默降级"

先确保一切失败都有日志，再考虑降级的用户体验。开发阶段的代码不应该是"静默"的。

### 3.4 多版本逐步验证优于一次完整设计

本次实际开发过程：

```
版本 1：三层降级（全错）       → 10 秒/轮 × 1 轮
版本 2：全文件读取（对的但粗糙） → 10 秒/轮 × 1 轮
版本 3：MCP uid + 行切片（正确） → 10 秒/轮 × 3 轮（调试两个参数问题）
```

如果一开始就先验证 MCP 参数、查 pgvector 格式、写单组件测试，版本 1 和部分版本 3 的调试轮次可以避免。

### 3.5 开发过程中的关键卡点

下图是本次开发遇到的实际卡点流程：

```
设计三层降级
  → 实现 → 编译通过 → 启动测试
  → ❌ 全部走 filesystem fallback（无可见错误）
  → 加 info 日志 → 重启
  → ❌ MCP响应有 symbol 但无 content 字段
  → 改参数 content: true → ❌ 仍然无 content
  → 打印 symbol.keySet() → [uid, name, kind, filePath, startLine, endLine]
  → 发现 MCP context 不支持 content 参数
  → 改为用 startLine/endLine + 文件切片
  → → ❌ 第一层失败（name 不是 UID）
  → 发现 pgvector 存的是 UID 格式
  → 改用 uid 参数 → ✅
  → ❌ 并行 MCP 导致 stdio 争抢
  → 改为串行调用 → ✅
```

每一个箭头都是一次"重启-提问-观察"循环。如果有前置验证，可以砍掉一半箭头。

---

## 四、总结

Vibe coding 的核心风险在于：**在假设基础上快速搭建，但假设本身就是最脆弱的环节**。

本次实现中：
- 对 MCP 工具参数 schema 的假设不对 → 2 轮调试
- 对 pgvector 数据格式的假设不对 → 1 轮调试
- 对 MCP 客户端线程安全的假设不对 → 1 轮调试
- 对日志级别的选择过于保守 → 多轮调试的加速因素

最有效的预防措施是老生常谈的三步：**先查数据、先验契约、先写最小测试**。在 vibe coding 的快节奏中，这三步常被跳过，但它们的缺失恰恰是后续返工的根源。
