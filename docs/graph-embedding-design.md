# 图谱增强文本嵌入方案（路径 B）

## 概述

基于 GitNexus 生成的全局知识图谱，对每个符号节点（类/接口/枚举）将其身份信息与周围关系边拼接为结构化文本，调用 text-embedding-3-large 生成向量，存入 PostgreSQL + pgvector，最终通过 `graph_search` 工具供 AI 在对话中检索。

---

## 1. 架构

```
┌─ 索引阶段 ───────────────────────────────────────────────────┐
│                                                               │
│  GitNexus context("Symbol")                                   │
│      │                                                        │
│      ▼                                                        │
│  GitNexusCodeGraphMapper.map()                                │
│      │                                                        │
│      ▼                                                        │
│  CodeGraphSymbol                                              │
│      │                                                        │
│      ▼                                                        │
│  GraphEnrichedDocument.build(symbol)  ──→  结构化文本          │
│      │                                                        │
│      ▼                                                        │
│  EmbeddingService.embed(text)          ──→  float[3072]       │
│      │                                                        │
│      ▼                                                        │
│  GraphEmbeddingStore.insert(id, vector, metadata)              │
│      │                                                        │
│      ▼                                                        │
│  PostgreSQL + pgvector                                        │
│                                                               │
└───────────────────────────────────────────────────────────────┘

┌─ 搜索阶段 ───────────────────────────────────────────────────┐
│                                                               │
│  用户查询: "配置初始化相关的类"                                 │
│      │                                                        │
│      ▼                                                        │
│  EmbeddingService.embed(query)         ──→  query_vector      │
│      │                                                        │
│      ▼                                                        │
│  GraphEmbeddingStore.search(query_vector, topK)               │
│      │                                                        │
│      ▼                                                        │
│  SELECT *, 1-(vector<=>?) AS sim ORDER BY vector<=>? LIMIT ?  │
│      │                                                        │
│      ▼                                                        │
│  返回: [{qualifiedName, filePath, kind, similarity}, ...]     │
│      │                                                        │
│      ▼                                                        │
│  GraphSearchTool 格式化 → AI 阅读                              │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## 2. 嵌入文档构造

### 2.1 嵌入对象

**粒度：每个 CodeGraphSymbol（类/接口/枚举）一条嵌入记录。**

不是每个文件，不是整个图谱。一个大类即使有 50 个依赖，也只生成一条嵌入文本。

### 2.2 构造器

```java
// GraphEnrichedDocument.java
public class GraphEnrichedDocument {

    public static String build(CodeGraphSymbol symbol) {
        StringBuilder sb = new StringBuilder();

        // 符号身份
        sb.append("[TYPE] ").append(symbol.getKind()).append("\n");
        sb.append("[NAME] ").append(symbol.getQualifiedName()).append("\n");
        sb.append("[FILE] ").append(symbol.getFilePath()).append("\n");

        // 图谱边（按关系类型分组）
        Map<String, EnumSet<ReferenceKind>> refs = symbol.getReferenceKinds();
        if (refs != null && !refs.isEmpty()) {
            for (var entry : refs.entrySet()) {
                String target = entry.getKey();
                EnumSet<ReferenceKind> kinds = entry.getValue();
                for (ReferenceKind kind : kinds) {
                    sb.append("[").append(kind.name()).append("] ")
                      .append(target).append("\n");
                }
            }
        }

        // 公共成员（方法名蕴含语义）
        if (symbol.getPublicMembers() != null) {
            for (String member : symbol.getPublicMembers()) {
                sb.append("[MEMBER] ").append(member).append("\n");
            }
        }

        // 声明签名（可选，提供代码级语义线索）
        if (symbol.getDeclaration() != null && !symbol.getDeclaration().isBlank()) {
            sb.append("[SIGNATURE] ").append(symbol.getDeclaration()).append("\n");
        }

        return sb.toString();
    }
}
```

### 2.3 嵌入文本示例

输入 `AgentOrchestrator` 到 GitNexus context，构造出的嵌入文本：

```text
Represent this code symbol and its dependencies for retrieval: 
[TYPE] Class
[NAME] com.thinkingcoding.agentloop.v2.orchestrator.AgentOrchestrator
[FILE] src/main/java/.../orchestrator/AgentOrchestrator.java
[CALLS] com.thinkingcoding.agentloop.v2.plan.LangChainPlanner
[CALLS] com.thinkingcoding.agentloop.v2.orchestrator.ReActDriver
[IMPORTS] com.thinkingcoding.agentloop.v2.model.TurnContext
[IMPORTS] com.thinkingcoding.agentloop.v2.model.PlanResult
[IMPORTS] com.thinkingcoding.agentloop.v2.model.ExecuteReactResult
[IMPORTS] com.thinkingcoding.model.ChatMessage
[MEMBER] onUserInput
[MEMBER] processInput
[MEMBER] snapshotHistory
[MEMBER] loadHistory
[MEMBER] setAutoApprove
[MEMBER] handleSteeringCommand
[MEMBER] getVersion
[SIGNATURE] public class AgentOrchestrator
```

---

## 3. 核心组件

### 3.1 新增文件清单

```
src/main/java/com/thinkingcoding/rag/embedding/
├── EmbeddingService.java          # DashScope Embedding API 调用
├── GraphEmbeddingStore.java       # pgvector 读写操作
├── GraphEnrichedDocument.java     # 嵌入文本构造器
├── GraphEmbeddingIndexer.java     # 批量索引编排
└── GraphSearchTool.java           # BaseTool 子类，供 AI 调用
```

### 3.2 EmbeddingService

```java
public class EmbeddingService {
    private final String baseUrl;     // rag.baseUrl (OpenAI 兼容端点)
    private final String apiKey;      // rag.apiKey
    private final String modelName;   // rag.embeddingModel = text-embedding-3-large
    private final OkHttpClient client;

    /**
     * 嵌入一段文本，返回 float[3072]
     */
    public float[] embed(String text) { ... }
}
```

**API 调用细节：**

```java
// POST {baseUrl}/embeddings  (OpenAI 兼容端点)
// Headers: Authorization: Bearer {apiKey}
// Body:
{
  "model": "text-embedding-3-large",
  "input": "[TYPE] Class\n[NAME] ...\n[CALLS] ...\n[IMPORTS] ...",
  "encoding_format": "float"
}
// Response:
{
  "data": [{
    "embedding": [0.023, -0.451, ...],  // float[3072]
    "index": 0
  }],
  "usage": { "total_tokens": 234 }
}
```

### 3.3 GraphEmbeddingStore

```java
public class GraphEmbeddingStore {
    private final DataSource dataSource;  // PG 连接池（可用 HikariCP 或 DriverManager）

    /**
     * 确保表存在
     */
    public void ensureTable() {
        // CREATE EXTENSION IF NOT EXISTS vector;
        // CREATE TABLE IF NOT EXISTS graph_embeddings (...)
    }

    /**
     * 插入或更新一条嵌入
     */
    public void upsert(String qualifiedName, float[] vector,
                       String filePath, String kind) { ... }

    /**
     * 余弦相似度搜索
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        // SELECT qualified_name, file_path, kind,
        //        1 - (vector <=> ?) AS similarity
        // FROM graph_embeddings
        // ORDER BY vector <=> ?
        // LIMIT ?
    }

    /**
     * 删除指定符号的嵌入（代码删除时用）
     */
    public void delete(String qualifiedName) { ... }

    /**
     * 检查某符号是否已有嵌入
     */
    public boolean exists(String qualifiedName) { ... }
}
```

**pgvector 表结构：**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS graph_embeddings (
    qualified_name  TEXT PRIMARY KEY,
    vector          vector(3072),
    file_path       TEXT,
    kind            TEXT,           -- CLASS / INTERFACE / ENUM
    indexed_at      TIMESTAMP DEFAULT NOW()
);

-- 余弦相似度索引
CREATE INDEX IF NOT EXISTS idx_graph_embeddings_vector
    ON graph_embeddings
    USING ivfflat (vector vector_cosine_ops)
    WITH (lists = 50);
```

### 3.4 GraphSearchTool

```java
public class GraphSearchTool extends BaseTool {

    public GraphSearchTool(AppConfig appConfig, GraphEmbeddingStore store,
                           EmbeddingService embeddingService) {
        super("graph_search",
              "Search the codebase by structural similarity. " +
              "Input: a natural language description of what kind of code you're looking for. " +
              "Returns: structurally similar symbols ranked by relevance. " +
              "Use when: you want to find classes with similar dependencies, " +
              "architecture patterns, or related modules. " +
              "NOT for: exact name lookups (use code_graph), " +
              "text searches (use grep_search), or " +
              "semantic code search (use semantic_search).");
    }

    @Override
    public ToolResult execute(String input) {
        // 1. 解析参数: query, topK
        // 2. embeddingService.embedQuery(query) → float[]
        // 3. store.search(vector, topK)
        // 4. 格式化返回
    }
}
```

**工具输出格式（给 AI 看）：**

```markdown
## 图谱结构搜索: "配置初始化相关的类"

### 匹配结果 (按相似度排列)

1. **DataSourceConfig** (Class) — 相似度 0.87
   `src/main/java/config/DataSourceConfig.java`
   关系: CALLS(HikariConfig, Environment), IMPORTS(Properties)

2. **AppConfigLoader** (Class) — 相似度 0.82
   `src/main/java/config/AppConfigLoader.java`
   关系: CALLS(YamlParser), IMPORTS(AppConfig, ModelConfig)

3. **ConfigManager** (Class) — 相似度 0.79
   `src/main/java/config/ConfigManager.java`
   关系: CALLS(ConfigLoader), IMPORTS(AppConfig, MCPConfig)

> 使用 `code_graph(target="完整类名")` 对任意符号做 360° 依赖分析
```

### 3.5 GraphEmbeddingIndexer

```java
public class GraphEmbeddingIndexer {

    /**
     * 全量索引：遍历仓库中所有符号
     */
    public IndexResult indexAll(GitNexusCodeGraphMapper mapper,
                                 EmbeddingService embedder,
                                 GraphEmbeddingStore store) {
        // 1. 通过 GitNexus query 列出仓库中所有符号（或文件扫描 + JavaParser）
        // 2. 对每个符号调用 context → CodeGraphSymbol
        // 3. GraphEnrichedDocument.build() → 嵌入文本
        // 4. embedder.embed() → 向量
        // 5. store.upsert()
        // 6. 返回 {total, indexed, skipped, failed}
    }

    /**
     * 增量索引：仅索引指定符号
     */
    public void indexSymbol(String target, ...) { ... }

    /**
     * 刷新过期的嵌入（基于 Git commit hash 判断）
     */
    public int refreshStale(...) { ... }
}
```

---

## 4. 配置

### 4.1 config.yaml

```yaml
# 已有的 RAG 配置扩展
rag:
  enabled: true
  autoIndex: true
  workspace: "D:/ThinkingCoding"
  topK: 5

  # Embedding 配置（已有字段，直接使用）
  baseUrl: "https://api.openai.com/v1"     # 或你的 OpenAI 兼容代理
  apiKey: "${OPENAI_API_KEY}"
  embeddingModel: "text-embedding-3-large"  # 3072 维

  gitnexus:
    serverName: "gitnexus"
    repo: "ThinkingCoding"

  # 新增：pgvector 连接配置
  pgvector:
    host: "localhost"
    port: 5432
    database: "thinkingcoding"
    schema: "public"
    user: "postgres"
    password: "${PG_PASSWORD}"
    # 或者直接用连接字符串
    # url: "jdbc:postgresql://localhost:5432/thinkingcoding"
```

### 4.2 AppConfig 改动

`RagConfig` 新增 `pgvector` 子配置：

```java
@JsonProperty("pgvector")
private PgVectorConfig pgvector = new PgVectorConfig();

@Data
public static class PgVectorConfig {
    private String host = "localhost";
    private int port = 5432;
    private String database = "thinkingcoding";
    private String schema = "public";
    private String user = "postgres";
    private String password;
    private String url;  // 优先使用，覆盖 host/port/database
}
```

### 4.3 pom.xml 新增依赖

```xml
<!-- PostgreSQL JDBC Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.1</version>
</dependency>

<!-- pgvector Java 客户端（可选，也可以用裸 JDBC + PGobject） -->
<dependency>
    <groupId>com.pgvector</groupId>
    <artifactId>pgvector</artifactId>
    <version>0.1.6</version>
</dependency>
```

---

## 5. 数据流

### 5.1 查询流程（AI 对话中）

```
用户: "项目里有哪些处理上下文管理的类？"
    │
    ▼
Agent Loop → Planner → AI 决定调用 graph_search
    │
    ▼
GraphSearchTool.execute("{query: '上下文管理'}")
    │
    ├─ EmbeddingService.embedQuery("Find code symbols...上下文管理")
    │     └─ POST DashScope API → query_vector[4096]
    │
    ├─ GraphEmbeddingStore.search(query_vector, topK=5)
    │     └─ SELECT ... FROM graph_embeddings ORDER BY vector <=> ?
    │     └─ → [ContextManager, SessionService, TurnContext, ...]
    │
    └─ 格式化返回 Markdown → 写入对话历史
    │
    ▼
AI: "项目中有以下上下文管理相关类：ContextManager（负责...）、
     SessionService（负责会话持久化...）..."
```

### 5.2 与同类工具的分工

| 工具 | 搜索方式 | 适用场景 |
|------|---------|---------|
| `grep_search` | 关键词精确匹配 | 知道确切的类名/方法名 |
| `code_graph` | 图谱精确查询 | 已知符号，要分析其依赖 |
| `semantic_search` | GitNexus 向量嵌入（代码文本） | 自然语言搜索代码功能 |
| **`graph_search`** (新) | **图谱结构嵌入** | 按依赖模式/架构模式搜索 |

---

## 6. 实现步骤

| 步骤 | 内容 | 新增文件 | 估计行数 |
|------|------|---------|---------|
| 1 | 添加 pom.xml 依赖 (pg JDBC + pgvector) | pom.xml 改动 | ~10 行 |
| 2 | `GraphEnrichedDocument` 嵌入文本构造器 | 1 个类 | ~60 行 |
| 3 | `EmbeddingService` OpenAI 兼容 Embedding API 封装 | 1 个类 | ~100 行 |
| 4 | `AppConfig.PgVectorConfig` 配置类 | AppConfig 改动 | ~30 行 |
| 5 | `GraphEmbeddingStore` pgvector CRUD | 1 个类 | ~150 行 |
| 6 | `GraphSearchTool` 工具实现 | 1 个类 | ~120 行 |
| 7 | `GraphEmbeddingIndexer` 批量索引 | 1 个类 | ~120 行 |
| 8 | 注册到 `ThinkingCodingContext` | 上下文改动 | ~15 行 |
| 9 | 单元测试 | 3-5 个测试类 | ~300 行 |

---

## 7. 待确认的细节问题

### Q1: pgvector 连接管理方式

现有 PG 连接是通过 MCP `@modelcontextprotocol/server-postgres` 走的（供 AI 调用 `sql-query` 工具），但 pgvector 需要应用层直连。两个选择：

- **A) 简单 DriverManager**：每次操作创建连接用完即关。适合 CLI 工具单用户场景，代码最简单
- **B) HikariCP 连接池**：更规范，但如果只做启动时批量索引 + AI 偶尔查询，一条连接就够

建议先用 A，后续按需切 B。

### Q2: 首次全量索引的数据来源

如何获得仓库中所有符号的列表？

- **A) GitNexus `query` 空搜索**：如果 GitNexus 支持列出全部符号，最直接
- **B) 文件扫描 + JavaParser**：项目已有 `javaparser-core` 依赖和 `JavaAstIndexer`，扫 `src/main/java/**/*.java` 提取类名
- **C) 混合**：用 B 获取符号列表，再用 GitNexus `context` 补全图谱关系

B 最快且不依赖 GitNexus 的特殊查询能力。建议 B 作为主路径。

### Q3: 索引触发时机

- **A) 启动时自动全量索引**：方便但启动慢（嵌入 API 调用有延迟 + 费 token）
- **B) 手动命令 `/rag index`**：用户可控，首次需要手动执行
- **C) 懒加载**：首次调用 `graph_search` 时触发，但需要有一个符号列表预取

建议：启动时检测表是否为空 → 空则后台异步索引（不阻塞启动），同时提供 `/rag index` 强制重建命令。

### Q4: 过期检测策略

索引时存储当前 `git rev-parse HEAD` 到表中。下次启动时对比，HEAD 变化则提示用户嵌入可能过期，由用户决定何时重建。

### Q5: 维度选择

text-embedding-3-large 默认 3072 维。不降维。

### Q6: API 调用

text-embedding-3-large 通过 OpenAI 兼容 API 调用（和项目已有的聊天 API 同协议）。支持批量输入（`input` 字段传数组），全量索引时每批发送 10-20 段文本。

