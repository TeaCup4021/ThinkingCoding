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

## 1.1 深入分析：为什么 `return result.get("content")` 返回的是 ArrayList？

问题根源 #2 是整个故障链条中最隐蔽的一环，值得展开剖析。

### MCP 协议的 tools/call 响应格式

当 `MCPClient.callTool()` 调用 `tools/call` 时，MCP 服务器返回的原始 JSON 结构如下：

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"symbol\":{\"name\":\"ThinkingCodingCLI\",\"kind\":\"class\",...},\"incoming\":{...}}"
      }
    ],
    "isError": false
  }
}
```

关键点：`content` 是一个 **JSON 数组** `[...]`，因为 MCP 协议允许一个工具同时返回多个内容块（如文本 + 图片），所以 `content` 天然是数组结构。

### Jackson 反序列化的类型映射

`MCPResponse` 中 `result` 字段声明为 `Object` 类型（MCPResponse.java:18）：

```java
@JsonProperty("result")
private Object result;   // Object，不是强类型 DTO
```

因为没有指定具体类型，Jackson 按照默认规则反序列化，将整个 JSON 树变成嵌套的 "HashMap 树"：

```
原始 JSON                                Jackson 反序列化后的 Java 类型
─────────────────────────────────────    ─────────────────────────────
{                                        MCPResponse
  "result": {                            ├─ result: LinkedHashMap         ①
    "content": [                         │   ├─ "content": ArrayList      ② ← 你问的关键点
      {                                  │   │   └─ [0]: LinkedHashMap    ③
        "type": "text",                  │   │       ├─ "type" → String "text"
        "text": "{...业务JSON...}"        │   │       └─ "text" → String "{...业务JSON...}"
      }                                  │   └─ "isError" → Boolean false
    ],
    "isError": false
  }
}
```

| Jackson 遇到 | 映射为 |
|---|---|
| JSON `{}` 对象 | `LinkedHashMap` |
| JSON `[]` 数组 | `ArrayList` |
| JSON `"..."` 字符串 | `String` |
| JSON 数字 | `Integer` / `Long` / `Double` |
| JSON `true/false` | `Boolean` |

### MCPClient.callTool() 中的隐式拆包

`MCPClient.callTool()` 的关键代码（MCPClient.java:336-338）：

```java
if (response.getResult() != null) {
    Map<String, Object> result = (Map<String, Object>) response.getResult();  // ① LinkedHashMap
    // ...
    return result.get("content");  // ② 返回的是 ArrayList！
}
```

`result` 是 `LinkedHashMap`，它的 key `"content"` 对应的 value 是 `ArrayList`（因为 JSON 中 `content` 的值是 `[...]`）。所以 `return result.get("content")` 的返回值：

- **类型**：`java.util.ArrayList`
- **内容**：`[{type=text, text={"symbol":{"name":"ThinkingCodingCLI",...},...}}]`

### 完整的类型流转链

```
MCPClient.callTool()                          → 返回 ArrayList
  ↓
mcpService.callTool()                         → 透传 ArrayList
  ↓
CodeGraphTool.callGitNexus()                  → 拿到 ArrayList，存入 Object response
  ↓
graphMapper.map(workspace, target, response)  → 传入 ArrayList
  ↓
GitNexusCodeGraphMapper.coerceMap(response)   → response 实际类型是 ArrayList
```

### 第一次修复为何失败

第一次修复时的 `coerceMap()` 逻辑（伪代码还原）：

```java
// 修复前的逻辑 — 假设 response 一定是 Map
if (response instanceof Map) {          // ← ArrayList instanceof Map → false！
    Map rawMap = (Map) response;
    Object content = rawMap.get("content");
    // ... 解包 text 字段 ...
    return castMap(rawMap);
}
return Collections.emptyMap();           // ← 因为 response 是 ArrayList，走到了这里
```

因为 `response` 实际上是 `ArrayList`（不是 `Map`），`instanceof Map` 判断为 `false`，方法直接返回了 `Collections.emptyMap()`。`map()` 方法收到空 Map，找不到 `symbol`、`definitions` 等业务字段，`targetSymbol` 为 `null`，最终触发报错：

```
失败: Target not found in GitNexus response: ThinkingCodingCLI.
```

**核心教训：AI（以及开发者）在不知晓运行时数据形状的情况下做了类型假设（以为是 Map，实际是 ArrayList），导致补丁打错了层。**

### 修复后的 coerceMap（当前代码）

`GitNexusCodeGraphMapper.java:216-261` 已经前置了 `List` 检查，分两步处理两种可能的数据形状：

```java
private Map<String, Object> coerceMap(Object response) {
    // ✅ 第一步：处理 MCPClient 已剥离 content 的情况 — response 是 ArrayList
    if (response instanceof List<?> contentList && !contentList.isEmpty()) {
        Object first = contentList.get(0);              // 取 [{type=text, text=...}]
        if (first instanceof Map<?, ?> firstMap) {
            Object textObj = firstMap.get("text");      // 提取 "text" 字段
            if (textObj instanceof String text && text.trim().startsWith("{")) {
                return mapper.readValue(text, Map.class); // 反序列化业务 JSON
            }
        }
        return Collections.emptyMap();
    }

    // ✅ 第二步：兼容未被剥离的完整 MCP 响应 — response 是 Map (含 "content" key)
    if (response instanceof Map<?, ?> rawMap) {
        Object contentObj = rawMap.get("content");
        // ... 同样的解包逻辑 ...
    }
    // ...
}
```

### 一图总结

```
MCP Server 原始响应
  ┌─ result
  │   └─ content: [...]          ← JSON 数组（MCP 协议层）
  │       └─ [0]
  │           ├─ type: "text"
  │           └─ text: "{...}"   ← 真正的业务数据藏在这里（还是个 JSON 字符串！）
  └─

MCPClient.callTool()
  return result.get("content");  → 返回 ArrayList（隐式拆掉 result 外层）
                                    ↑ "我帮你拆了一层，但没告诉你"
GitNexusCodeGraphMapper.coerceMap(response)
  收到的是 ArrayList
  → 第一次修复前：instanceof Map? → false → 返回空 Map → 解析失败
  → 第二次修复后：instanceof List? → true → 提取 [0].text → 成功
```

**根本问题的本质**：在协议边界处没有完成解包，把协议层的 `List<Map>` 透传给了业务层。业务层按 `Map` 去强转，直接掉进类型不匹配的陷阱。正确的做法是在 `MCPClient.callTool()` 这一层就完成 "遍历 content、提取 text 类型、检查 isError" 的完整解包，返回干净的字符串给上游。

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
