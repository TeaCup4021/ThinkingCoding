# MCP 工具参数如何传递给 AI 模型

## 概述

AI 模型通过 MCP 协议中的 `inputSchema`（JSON Schema 格式）获知每个 MCP 工具的参数定义。整个流程分为 3 个阶段，从 MCP 服务器一直传递到 AI 模型的 ChatRequest 中。

## 完整数据流

```
MCP Server (JSON-RPC)
  │ tools/list 响应中携带 inputSchema (JSON Schema)
  ▼
MCPClient.listTools()
  │ 反序列化为 MCPTool 对象，存入 availableTools
  ▼
MCPToolAdapter.getInputSchema()
  │ 透传 mcpTool.getInputSchema()
  ▼
LangChainService.resolveToolSchema()
  │ toJsonObjectSchema() 将 Map 转为 LangChain4j 的 JsonObjectSchema
  ▼
ChatRequest.toolSpecifications
  │ 随每次请求发给 AI 模型
  ▼
AI 模型
  └─ 根据 schema 生成 ToolExecutionRequest
```

## 阶段 1：MCP 服务器返回工具的 inputSchema

`MCPClient.connect()` 完成协议握手后，调用 `listTools()` 向 MCP 服务器发送 `tools/list` 请求。

MCP 服务器返回的 JSON 示例（以虚构的 `get_weather` 工具为例）：

```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "查询指定城市的天气",
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": { "type": "string", "description": "城市名称" },
          "unit": { "type": "string", "enum": ["celsius", "fahrenheit"] }
        },
        "required": ["city"]
      }
    }
  ]
}
```

`MCPClient` 使用 Jackson 反序列化为 `MCPTool` 对象（`MCPClient.java:236`）：

```java
MCPTool tool = objectMapper.convertValue(toolData, MCPTool.class);
availableTools.put(tool.getName(), tool);
```

`MCPTool`（`mcp/model/MCPTool.java`）有三个核心字段：

| 字段 | 说明 |
|------|------|
| `name` | 工具名称 |
| `description` | 工具功能描述 |
| `inputSchema` | JSON Schema 格式的参数定义（原始 Object） |

## 阶段 2：MCPToolAdapter 桥接到 BaseTool

`MCPToolAdapter` 继承 `BaseTool`，重写 `getInputSchema()` 透传 MCP 服务器的原始 schema（`mcp/MCPToolAdapter.java:95-97`）：

```java
@Override
public Object getInputSchema() {
    return mcpTool.getInputSchema();  // 直接透传 MCP 服务器给的 schema
}
```

这样 MCP 工具的 schema 就进入了 `ToolRegistry` 统一管理的工具池，与内置工具、Skill 工具平级。

## 阶段 3：LangChainService 转换为 ToolSpecification 发给 AI

每次调用 AI 模型前，`LangChainService.buildToolSpecifications()`（`service/LangChainService.java:368-381`）遍历所有工具，为每个工具构建 `ToolSpecification`：

```java
for (BaseTool tool : toolRegistry.getAllTools()) {
    ToolSpecification specification = ToolSpecification.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .parameters(resolveToolSchema(tool))  // 关键：解析 schema
            .build();
    specifications.add(specification);
}
```

`resolveToolSchema()`（`LangChainService.java:388-400`）调用 `tool.getInputSchema()` 拿到原始 JSON Schema，再通过 `toJsonObjectSchema()`（`LangChainService.java:434-464`）递归转换为 LangChain4j 的 `JsonObjectSchema`：

- `properties` 中的每个字段根据 `type` 转为 `JsonStringSchema` / `JsonEnumSchema` / `JsonBooleanSchema` / `JsonIntegerSchema` / `JsonNumberSchema` / `JsonArraySchema`
- 嵌套 `object` 递归调用 `toJsonObjectSchema()`
- `required` 数组直接透传

最终 `ToolSpecification` 被放入 `ChatRequest`（`LangChainService.java:155-158`）：

```java
ChatRequest request = ChatRequest.builder()
        .messages(messages)
        .toolSpecifications(toolSpecifications)  // AI 模型在这里看到工具定义
        .build();
```

## 关键类关系

| 类 | 文件 | 职责 |
|----|------|------|
| `MCPClient` | `mcp/MCPClient.java` | 通过 JSON-RPC 与 MCP 服务器通信，解析 `tools/list` 响应 |
| `MCPTool` | `mcp/model/MCPTool.java` | 数据模型，持有 name / description / inputSchema |
| `MCPToolAdapter` | `mcp/MCPToolAdapter.java` | 将 `MCPTool` 适配为 `BaseTool`，透传 `getInputSchema()` |
| `BaseTool` | `tools/BaseTool.java` | 工具抽象基类，定义 `getInputSchema()` 接口 |
| `ToolRegistry` | `tools/ToolRegistry.java` | 统一管理所有工具（内置 + MCP + Skill） |
| `LangChainService` | `service/LangChainService.java` | 将工具的 schema 转为 LangChain4j `ToolSpecification`，注入 `ChatRequest` |

## 内置工具的降级方案

对于没有 `inputSchema` 的工具（内置工具），`BaseTool.getInputSchema()` 默认返回 `null`，此时 `resolveToolSchema()` 走 `defaultToolSchema()` 方法（`LangChainService.java:402-432`），根据工具名匹配硬编码的 schema 定义（如 `file_manager`、`command_executor`、`grep_search`），匹配不到则返回一个宽松的 `additionalProperties(true)` 的空 schema。