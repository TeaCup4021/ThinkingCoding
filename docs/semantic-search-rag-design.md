# SemanticSearch RAG 设计方案（基于 GitNexus 嵌入模型）

## 1. 关键发现

GitNexus 自带嵌入模型，其 MCP `query` 工具已经实现了语义搜索能力：

| GitNexus MCP 工具 | 功能 | 现有包装 |
|-------------------|------|---------|
| `context` | 精确符号查询（360°视图：调用者、被调用者、执行流程） | `CodeGraphTool` |
| **`query`** | **概念级语义搜索（输入自然语言 → 返回相关执行流程和符号，按相关性排序）** | **无（本次实现）** |
| `impact` | 影响分析/爆炸半径 | 无 |
| `detect_changes` | Git diff 影响分析 | 无 |

从 GitNexus 指南：
> `gitnexus_query({query: "payment processing"})` → Processes: CheckoutFlow, RefundFlow, WebhookHandler. Symbols grouped by flow with file locations. **Returns process-grouped results ranked by relevance.**

这意味着：**不需要自己建 Embedding 模型、向量数据库、代码分块器**。只需包装 GitNexus `query` 工具即可。

## 2. 与自建 RAG 的对比

| 维度 | 自建 RAG 方案 | 基于 GitNexus query |
|------|-------------|-------------------|
| 嵌入模型 | 需要配置 DeepSeek/OpenAI Embedding API | GitNexus 内置 |
| 向量存储 | InMemoryEmbeddingStore + JSON 持久化 | GitNexus LadybugDB |
| 代码分块 | AST-aware CodeChunker（需实现） | GitNexus Tree-sitter 解析 |
| 索引维护 | 启动时全量/增量索引，需处理过期 | `npx gitnexus analyze` 刷新 |
| 新增依赖 | 可能需 `langchain4j-embeddings` 模块 | 零（复用现有 MCPService） |
| 新增代码量 | ~8 个类，~1200 行 | **1 个类，~200 行** |
| 语义理解深度 | 片段级相似度匹配 | 执行流程级语义理解（更结构化） |

**结论：直接用 GitNexus `query`，不做自建。**

## 3. 架构

```
┌──────────────────────────────────────────────────┐
│                 Agent Loop (V2)                   │
│                       │                           │
│                       ▼                           │
│              ToolExecutionEngine                  │
│                  │          │                     │
│                  ▼          ▼                     │
│  SemanticSearchTool    CodeGraphTool    (并列)   │
│       │                    │                      │
│       ▼                    ▼                      │
│  ┌──────────────────────────────────────┐        │
│  │           MCPService                  │        │
│  │  mcpService.callTool(server, tool, args)       │
│  │       │                    │          │        │
│  │       ▼                    ▼          │        │
│  │  "query" tool         "context" tool  │        │
│  │  (语义搜索)           (符号查询)      │        │
│  └──────────────────────────────────────┘        │
│                       │                           │
│                       ▼                           │
│  ┌──────────────────────────────────────┐        │
│  │       GitNexus MCP Server             │        │
│  │  npx gitnexus mcp                     │        │
│  │                                       │        │
│  │  ┌──────────┐  ┌──────────────┐      │        │
│  │  │ Embedded │  │ Knowledge    │      │        │
│  │  │ Model    │  │ Graph (LDB)  │      │        │
│  │  └──────────┘  └──────────────┘      │        │
│  └──────────────────────────────────────┘        │
└──────────────────────────────────────────────────┘
```

## 4. 核心组件

### 4.1 新增文件

只需要 **1 个新文件**：

```
src/main/java/com/thinkingcoding/tools/rag/
├── CodeGraphTool.java          # 已有，包装 gitnexus "context"
└── SemanticSearchTool.java     # 新增，包装 gitnexus "query"
```

### 4.2 SemanticSearchTool

```java
package com.thinkingcoding.tools.rag;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.BaseTool;

public class SemanticSearchTool extends BaseTool {

    private final AppConfig appConfig;
    private final MCPService mcpService;

    public SemanticSearchTool(AppConfig appConfig, MCPService mcpService) {
        super("semantic_search",
              "Search the codebase using natural language. " +
              "Use this to find code related to a concept, e.g. 'authentication logic' or 'database connection pool'. " +
              "Returns execution flows and symbols ranked by relevance.");
        this.appConfig = appConfig;
        this.mcpService = mcpService;
    }

    @Override
    public ToolResult execute(String input) {
        // 1. 解析参数
        // 2. 调用 mcpService.callTool("gitnexus", "query", args)
        // 3. 格式化响应为 AI 可读的文本
    }

    @Override
    public String getCategory() { return "rag"; }

    @Override
    public Object getInputSchema() {
        // query (必填): 自然语言查询
        // topK (可选): 返回结果数
    }
}
```

### 4.3 输入/输出设计

**工具签名**（AI 可发现的 ToolSpec）：

```json
{
  "name": "semantic_search",
  "description": "Search the codebase with natural language. Use for concept-level queries like 'where is authentication handled' or 'find database connection logic'. Returns execution flows and symbols ranked by relevance.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "Natural language description of what to find"
      },
      "topK": {
        "type": "integer",
        "description": "Maximum number of results to return (default: 5)"
      }
    },
    "required": ["query"]
  }
}
```

**输出格式**（给 AI 看的文本）：

```
## Semantic Search Results for: "数据库连接池配置"

### Execution Flows
1. **DatabaseInitFlow** (relevance: high)
   - DataSourceConfig.setupPool() → HikariConfig.loadProperties() → DataSource.getConnection()
   - Files: config/DataSourceConfig.java, config/HikariConfig.java

2. **ConnectionLifecycle** (relevance: medium)
   - PoolManager.acquire() → ConnectionProxy.validate() → PoolManager.release()
   - Files: pool/PoolManager.java, pool/ConnectionProxy.java

### Related Symbols
- **DataSourceConfig** (class) — config/DataSourceConfig.java
- **HikariProperties** (record) — config/HikariProperties.java
- **PoolManager** (class) — pool/PoolManager.java
```

## 5. 数据流

### 5.1 搜索流程

```
用户: "认证逻辑在哪实现的？"
  │
  ▼
Agent Loop → Planner 判断需要搜索代码库
  │
  ▼
AI 发起 tool call:
  semantic_search(query="认证逻辑 authentication", topK=5)
  │
  ▼
ToolExecutionEngine → SemanticSearchTool.execute()
  │
  ├─ 1. 解析参数: {query: "认证逻辑 authentication", topK: 5}
  ├─ 2. mcpService.callTool("gitnexus", "query", {
  │      query: "认证逻辑 authentication",
  │      repo: "ThinkingCoding",
  │      limit: 5
  │    })
  ├─ 3. GitNexus 嵌入模型将 query 向量化
  ├─ 4. LadybugDB 知识图谱检索 → 返回相关执行流程 + 符号
  ├─ 5. SemanticSearchTool 格式化结果
  └─ 6. 返回 ToolResult → 写回 Agent 对话历史
  │
  ▼
AI 基于搜索结果回答用户
```

### 5.2 索引维护

GitNexus 负责索引维护，ThinkingCoding 不需操心：

```bash
# 初始建索引（一次性）
npx gitnexus analyze

# 索引过期时刷新
npx gitnexus analyze
```

AGENTS.md 已有提示：
> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## 6. 配置

### 6.1 config.yaml

```yaml
rag:
  enabled: true
  gitnexus:
    serverName: "gitnexus"
    repo: "ThinkingCoding"      # GitNexus 中的 repo 名称

tools:
  semanticSearch:
    enabled: true
    timeoutSeconds: 30          # query 可能比 context 慢一些
```

配置项 `RagConfig` 中 `topK`、`minScore` 等参数保留，用作默认值传给 GitNexus `query`。

### 6.2 AppConfig 改动

无需新增字段，现有 `RagConfig` 已有 `topK`，`GitNexusConfig` 已有 `serverName` 和 `repo`。

## 7. 实现步骤

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 创建 `SemanticSearchTool`（继承 BaseTool，包装 `mcpService.callTool("gitnexus", "query", ...)`） | 小 |
| 2 | 实现 GitNexus `query` 响应格式化（解析返回的 processes/symbols，渲染为 AI 可读文本） | 中 |
| 3 | 在 `ToolRegistry` / `ThinkingCodingContext` 中注册 `SemanticSearchTool` | 小 |
| 4 | 编写单元测试（Mock MCPService） | 小 |

总计：**1 个新类，~200 行代码**，工作量远小于自建 RAG。

## 8. 与 CodeGraphTool 的分工

两个工具互补，AI 可以组合使用：

| | SemanticSearchTool | CodeGraphTool |
|---|---|---|
| 后端 | GitNexus `query` | GitNexus `context` |
| 输入 | 自然语言概念 | 精确类名/方法名 |
| 输出 | 执行流程 + 相关符号 | 符号 360°视图 + 依赖图 |
| 场景 | "认证功能在哪" | "AuthService 依赖哪些类" |
| 检索方式 | 嵌入向量语义匹配 | 知识图谱遍历 |

**AI 协作模式**：
1. 用户问模糊问题 → AI 调用 `semantic_search` 定位候选
2. 锁定候选符号 → AI 调用 `code_graph` 深入分析依赖

## 9. 关键设计决策

### 9.1 为什么用 GitNexus query 而不是自建 RAG？

- GitNexus 已完成代码解析（Tree-sitter）、嵌入（内置模型）、存储（LadybugDB）
- 自建 RAG 是**重复造轮子**，且质量不如 GitNexus（它理解执行流程，不只是代码片段相似度）
- 维护成本：GitNexus 负责索引更新，自建需要自己管过期检测、增量更新

### 9.2 什么时候考虑自建 RAG？

只有在以下场景才需要自建：
- 需要对**非代码文档**（README、设计文档、会议纪要）做语义搜索
- 需要跨项目/跨仓库的统一语义搜索
- GitNexus 不可用的环境（如离线或非 JS 环境）

这些场景可以作为后续增强，当前用 GitNexus 是最高性价比的选择。

## 10. 风险和缓解

| 风险 | 缓解 |
|------|------|
| GitNexus MCP 未启动 | `SemanticSearchTool.isEnabled()` 检查 `mcpService` 可用性，不可用时工具自动隐藏 |
| GitNexus 索引过期 | `query` 返回结果中通常包含 staleness 标记，工具检测到后提示用户运行 `npx gitnexus analyze` |
| `query` 返回空结果 | 工具返回明确提示 "No results found for 'xxx'. Try different keywords or check if the index is up to date." |
