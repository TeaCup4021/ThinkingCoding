# Code Graph (GitNexus) 工具查询失效修复总结

在近期使用 `code_graph` 工具分析 `ThinkingCodingCLI` 等源码符号时，连续出现了无法找到目标的报错：
`失败: Target not found in GitNexus response: ThinkingCodingCLI. Try a fully-qualified symbol name or a known class name`

针对这一问题，实施了两次针对性的排查和修复。以下是问题根源及修复过程的详细总结。

## 1. 问题根源 (Root Cause)

导致 `code_graph` 分析失效的核心原因有三个：

1. **MCP 回包外层结构嵌套问题**  
   工具层原本期望直接接收到业务 JSON 或包含业务属性的 Map，但通过 MCP 协议调用 GitNexus 工具时，其实际反馈的 JSON 数据被包裹在 MCP 标准的文本反馈结构中，格式类似于：
   `[{"type": "text", "text": "{\"status\":\"found\", ... 真实业务数据 ...}"}]` 
   或者带有外层结构：
   `{"content": [{"type": "text", "text": "..."}]}`。
   这导致原来的 `ObjectMapper` 直接将外层结构转为 Map 后，读取不到 `symbol` 和 `definitions` 等关键业务节点。

2. **MCPClient 底层返回类型的误判 (ArrayList vs Map)**  
   在底层的 `MCPClient.callTool()` 实现中，当拿到了 MCP 完整的 response 后，它实际上做了 `return result.get("content");` 的操作。这意味着上游 `CodeGraphTool` 和 `GitNexusCodeGraphMapper` 接收到的对象实际上是 `java.util.ArrayList`，而不是一个包含 `"content"` key 的 `Map` 或原始的 JSON 字符串。
   这种隐式拆包导致第一次尝试解包时陷入了类型不匹配的陷阱（强转/判断为 Map 失败），最终返回了空的解析结果。

3. **GitNexus API 字段变更**  
   在较新版本的 `gitnexus query` 响应中，定义数据节点的主键从旧版本的 `uid` 变更（或兼容）为了 `id`。而原有代码仅在提取查询结果（如 `resolveCandidateFromQuery` 方法）时尝试读取 `def.get("uid")`，这就导致了就算 JSON 解包成功，也会因为取不出唯一标识而无法在回退映射策略里匹配到目标类 `ThinkingCodingCLI`。

---

## 2. 修复过程分解

### 第一次修改：尝试解开 content 包装并兼容新主键
- **修改位置**: `GitNexusCodeGraphMapper.java` 和 `CodeGraphTool.java`
- **改动内容**:
  1. 在 `coerceMap()` 等处理响应结果的方法中，增加对 `response instanceof Map<?, ?>` 且包含 `"content"` 键的处理逻辑，尝试提取出嵌套在 `content[0].text` 的 JSON 字符串并进行重解析。
  2. 在 `resolveCandidateFromQuery()` 以及依赖映射的 `parseDependency`、`buildSymbol` 方法中，增加向后兼容逻辑。原来如果是 `extractNameFromUid(asString(map.get("uid")))` 失败，现增加 fallback 到 `map.get("id")` 字段读取以兼容新版知识图谱结构。

*(注：第一次修改后，因未察觉到底层 `MCPClient` 是直接剥离了 `content` 并返回 `List`，因此测试仍然抛出了找不到类的错误。)*

### 第二次修改：兼容 ArrayList 直接返回场景
- **修改位置**: `GitNexusCodeGraphMapper.java` 和 `CodeGraphTool.java`
- **改动内容**: 
  1. 增加了本地模拟和终端编译 `TestToolClient` 进行真实方法调用级别的 debug。确认接收到的类型为 `class java.util.ArrayList`。
  2. 针对性地在 `coerceMap()` (Mapper) 和 `resolveCandidateFromQuery()` (Tool) 中增加了 `response instanceof List<?>` 的检查逻辑。
  3. 当识别为列表并且不为空时，直接取出第 0 个元素，判断其是否是 Map 并且含有 `"text"` 键，随后将其中的 JSON 字符串反序列化并取出真实的 `status=found` 业务结构体。

## 3. 最终结果
修改完成后，底层的 GitNexus 数据请求无论在外层是被封装成了含有 `content` 的 `Map`，还是直接被底层 MCPClient 剥离成了 `ArrayList`，上层的 RAG 相关处理组件均能够安全、顺利地向下解包提取嵌套的纯文本业务 JSON。

编译 `ThinkingCodingCLI` 并跳过测试打包通过（`mvn clean package -DskipTests`）后，`code_graph` 功能验证恢复正常，成功对工程实现了精确的上下游依赖图谱解析。
