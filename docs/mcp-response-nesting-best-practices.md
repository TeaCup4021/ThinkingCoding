# MCP 回包外层结构嵌套问题：原理、排查与开发避坑指南

> 整理自一次关于 MCP 集成调试的对话，涵盖问题本质、实际故障复盘，以及如何向 AI 描述类似任务以提升编码准确率。

---

## 1. 问题本质

MCP 协议遵循 JSON-RPC 2.0 规范，`tools/call` 的响应结构为：

```json
{
  "jsonrpc": "2.0",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "这里才是真正的工具返回内容"
      }
    ],
    "isError": false
  }
}
```

问题发生在 `MCPClient.callTool()` 中：`result.get("content")` 返回的是 `List<Map>`（即 content 数组），但下游代码（`MCPService`、`MCPToolAdapter`）直接对其调用了 `.toString()`，导致 LLM 最终收到的内容是：

```
[{type=text, text=真正的内容}]
```

而不是干净的：

```
真正的内容
```

### 完整数据链路

```
MCPClient.callTool() → result.get("content") → 返回 List<Map>
  → MCPService / MCPToolAdapter.execute() → .toString() → "[{type=text, text=...}]"
    → ToolResult.success(stringified)
      → ToolResultFormatter → 写入 LLM 上下文（observation）
```

### 两个常被忽略的细节

1. **`content` 是数组，可能有多项** — 除 `text` 外还可包含 `image`、`resource` 类型。只取 `content[0].text` 会丢数据。
2. **`isError` 字段未被检查** — 当 `isError: true` 时，当前代码仍返回 `ToolResult.success()`，LLM 无法区分成功与失败。

### 不同 MCP 方法的响应结构对比

| 方法 | 结构 |
|------|------|
| `tools/list` | `result.tools[]` |
| `tools/call` | `result.content[]` + `result.isError` |
| `resources/read` | `result.contents[]`（注意是 `contents` 不是 `content`） |
| `resources/list` | `result.resources[]` |
| `prompts/get` | `result.messages[]` |

每类方法的外层包装不同，不能假设都是 `result.content[]`。

---

## 2. 实际故障复盘：code_graph 工具查询失效

来源文档：[`docs/code_graph_fix_summary.md`](code_graph_fix_summary.md)

### 故障现象

使用 `code_graph`（GitNexus）工具分析 `ThinkingCodingCLI` 等源码符号时持续报错：

```
失败: Target not found in GitNexus response: ThinkingCodingCLI.
Try a fully-qualified symbol name or a known class name
```

### 三个根本原因

1. **MCP 回包外层结构嵌套** — GitNexus 返回的业务 JSON 被包裹在 MCP 标准的 `[{"type": "text", "text": "{...真实数据...}"}]` 结构中，业务层直接用 `ObjectMapper` 解析外层结构后读取不到 `symbol`、`definitions` 等业务字段。

2. **MCPClient 底层返回类型误判** — `MCPClient.callTool()` 中执行了 `return result.get("content")`，导致上游收到的实际类型是 `java.util.ArrayList` 而不是包含 `"content"` key 的 `Map`。第一次修复时假设是 Map，强转失败后返回空结果。

3. **GitNexus API 字段变更** — 新版 API 中定义节点主键从 `uid` 变为 `id`，旧代码只读取 `def.get("uid")` 导致取不出标识符。

### 两次修复过程

| 轮次 | 思路 | 问题 |
|------|------|------|
| 第一次 | 增加 `response instanceof Map` + 包含 `"content"` 键的处理逻辑；向后兼容 `id` 字段 | 未察觉底层 MCPClient 已剥离 `content` 返回了 `List`，仍然报错 |
| 第二次 | 增加 `response instanceof List` 检查，取 `list[0]` 并提取 `text` 内的 JSON 字符串 | 确认底层实际返回 ArrayList，修复生效 |

核心教训：**AI 在不知晓运行时数据形状的情况下做了类型假设（以为是 Map，实际是 ArrayList），导致补丁打错了层。**

---

## 3. 开发时如何避免此类问题

### 原则一：在协议边界处完成解包，不要透传原始结构

```java
// 不要直接返回 result.get("content") 然后交给下游 toString()
// 而是提取出实际的文本内容:
Object content = result.get("content");
if (content instanceof List) {
    List<?> items = (List<?>) content;
    for (Object item : items) {
        if (item instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) item;
            String type = (String) map.get("type");
            if ("text".equals(type)) {
                return map.get("text").toString();
            }
            // 处理 image、resource 等其他类型
        }
    }
}
```

### 原则二：协议 DTO 与业务类型严格分离

不要直接把 JSON 反序列化得到的 `Map<String, Object>` 透传到业务层。在协议边界处用强类型 DTO 收拢：

```java
// MCPResponse 里 result 应使用精确类型:
@JsonProperty("result")
private ToolsCallResult result;  // 而非 Object
```

### 原则三：禁止 `.toString()` 作为数据转换手段

除非你明确知道那是最终展示格式。正确的做法是显式构造业务对象，在设值之前就完成内容提取。

### 原则四：用集成测试锚定回包结构

为 MCP 工具调用链路写测试，mock MCP server 返回标准 JSON-RPC 响应，断言最终 `ToolResult.getOutput()` 是干净的文本字符串而非 `[{...}]` 格式。

### 原则五：按方法类型分派解析逻辑

不要用一套通用代码处理所有 MCP 方法响应。`tools/call`、`resources/read`、`prompts/get` 各有不同结构，应分别处理。

---

## 4. 向 AI 描述类似任务的最佳实践

针对**跨层调试类任务**（数据流经多层，运行时类型可能偏离预期），以下五个策略可显著提升 AI 编码准确率：

### 策略一：要求 AI 先"探路"再动刀

不要直接让 AI 修复问题，先要求它写数据形状验证代码：

> "先在 `MCPClient.callTool()` 返回之前加一行 log，打印 `result.get("content")` 的实际类型（`getClass().getName()`）和 JSON 序列化后的值。运行一次，把日志给我看，然后我们再决定怎么修。"

**效果**：强制 AI 无法跳过"确认输入到底是什么"这一步。本次故障中 AI 假设数据类型是 Map（实际是 ArrayList），如果能先打印日志就不会错。

### 策略二：要求全链路类型追踪

> "从 MCP 响应解析开始，到最终被 GitNexusCodeGraphMapper 消费为止，帮我梳理完整的类型流转链。每一步都要标注：变量名、实际类型、示例值。然后基于这个链条，告诉我应该在**哪一层**做解包最合适。"

**效果**：避免到处打补丁。本次故障的补丁分布在 `MCPClient` → `CodeGraphTool` → `GitNexusCodeGraphMapper` 三层，如果先画出完整链路，就会倾向于在正确的边界层一次性解决问题。

### 策略三：禁止 `instanceof` 级联 + `.toString()` 兜底

这是 AI 最爱写但最容易出事的模式：

```java
if (response instanceof Map) {
    // ...
} else if (response instanceof List) {
    // ...
} else {
    return response.toString(); // 兜底黑洞
}
```

可以这样约束：

> "解包逻辑必须用强类型 DTO 而非 instanceof 级联。如果现有类型不匹配，先定义正确的 DTO 类，再用 ObjectMapper 做 convertValue，不要手动逐层拆 Map/List。"

### 策略四：把协议规范作为硬约束

> "MCP `tools/call` 的响应格式遵循 JSON-RPC 2.0，`result.content[]` 是一个数组，每个元素可能是 `{type: text, text: ...}` 或 `{type: image, ...}`。请基于这个规范设计解包逻辑，不要假设 content 数组只有一项，也不要假设只有 text 类型。"

**效果**：不给协议细节的话，AI 会按"常见 JSON 返回"的惯性去猜，而不是按规范实现。

### 策略五：要求先写失败的测试再修代码

> "在动手改代码之前，先写一个单元测试：mock MCPClient 返回一个标准的 `tools/call` 响应（包含 `result.content[0].text` 嵌套结构），断言最终 `ToolResult.getOutput()` 是干净的文本。测试先跑红，然后再修代码。"

**效果**：强制 AI 在动手之前就理解"正确的输出长什么样"。如果 AI 连测试都写不对，说明它还没理解数据结构——此时不会产生改错代码的代价。

### 推荐 Prompt 模板

综合以上策略，下次类似任务可直接使用：

> ```
> 这是一个 MCP 集成调试任务。在做任何修改之前，请先完成三件事：
> 1. 在数据入口处加日志，打印原始响应的类型和 JSON，确认运行时数据形状
> 2. 绘制从入口到最终消费端的完整类型流转图
> 3. 写一个单元测试，用标准 MCP 响应 mock 输入，断言最终输出格式
> 
> 确认以上三点都正确后，再提出修改方案。解包时使用强类型 DTO 而非 instanceof 级联，
> 协议规范见 MCP spec 中 tools/call 的返回格式。
> ```

---

## 5. 总结

| 要点 | 说明 |
|------|------|
| **根本原因** | 协议结构在边界处未被解包，原始 `List<Map>` 透传至业务层后被 `.toString()` 破坏 |
| **核心教训** | 在协议边界完成解包，使用强类型 DTO，禁止 `.toString()` 作为转换手段 |
| **AI 协作关键** | 让 AI 先证明它理解了运行时数据形状，再允许它改代码 |
| **防护措施** | 集成测试锚定回包结构，按方法类型分派解析逻辑 |
