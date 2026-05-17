# RAG 自动检索注入方案

## Context

当前 ThinkingCoding 已有完整的嵌入管道（GitNexus 图节点 → text-embedding-v4 → pgvector），但仅用于 AI 通过 `graph_search` 工具被动查询。用户提问代码问题时，LLM 不自动获得相关代码上下文。需要在 `LangChainService.prepareMessages()` 处主动将用户问题转为向量 → 搜索 pgvector → 获取源码 → 注入 prompt。

## 工具链（全部复用，0 个新工具）

| 步骤 | 已有组件 | 文件 |
|------|---------|------|
| 向量化问题 | `EmbeddingService.embed()` | `rag/embedding/EmbeddingService.java:46` |
| 搜索 pgvector | `GraphEmbeddingStore.search()` | `rag/embedding/GraphEmbeddingStore.java:106` |
| 获取源码（主） | `MCPService.callTool("gitnexus", "context", {content: true, ...})` | MCP |
| 获取源码（降级） | `Files.readString(Path.of(filePath))` | JDK |

## 为什么用 GitNexus MCP `context --content` 而不是 `Files.readString()`

| 方案 | 多语言 | 精准度 | 延迟 |
|------|--------|--------|------|
| `Files.readString(filePath)` 全文 | 全部语言，但无差别返回全文 | 低（整个文件，含无关代码） | 低（本地 I/O） |
| GitNexus MCP `context --content` | GitNexus 支持的全部语言（Java/Kotlin/Scala/Python/JS/TS/Go/Rust/C#...） | 高（AST 级符号边界，返回精确代码块） | 中（MCP IPC 往返） |
| 正则+大括号自切 | 仅 C 家族 | 中 | 低 |

**选择 GitNexus MCP 为主路径，`Files.readString()` 为降级路径**。GitNexus 已经在 AST 层面解析了所有语言的代码，知道每个符号的精确 `startLine`/`endLine`——这是任何本地正则方案都无法覆盖的多语言保障。

验证结果：`gitnexus context --content -u "Class:src/.../AppConfig.java:AppConfig"` 返回了 `AppConfig` 完整的 638 行源码，带 `startLine: 15` 和 `endLine: 638`。

## 新增文件（共 1 个）

### `RagContextEnricher` — 编排器

**路径**: `src/main/java/com/thinkingcoding/rag/retrieval/RagContextEnricher.java`

**依赖注入**:

```java
public RagContextEnricher(
    EmbeddingService embeddingService,   // 向量化用户问题
    GraphEmbeddingStore store,           // pgvector 搜索
    MCPService mcpService,               // GitNexus MCP 获取源码
    String gitNexusServer,               // MCP 服务器名 ("gitnexus")
    Path workspaceRoot,                  // 降级读文件时的项目根目录
    RagConfig config                     // topK, maxCodeChars, minSimilarity, enabled
)
```

**核心方法**:

```java
public String enrich(String userInput) {
    // 1. 向量化用户问题
    float[] queryVector = embeddingService.embed(userInput);
    
    // 2. 搜索 pgvector
    List<SearchResult> results = store.search(queryVector, config.getTopK());
    
    // 3. 按相似度阈值过滤
    results = results.stream()
        .filter(r -> r.similarity() >= config.getMinSimilarity())
        .toList();
    
    // 4. 并行获取每个符号的源码（MCP context --content）
    List<CodeSnippet> snippets = fetchCodeSnippets(results);
    
    // 5. 格式化为 Markdown，截断到 maxCodeChars
    return formatContext(snippets);
}
```

**获取源码的策略（三层降级）**:

```
对每个 SearchResult:
  │
  ├─ 第1层: MCP context --content {name: qualifiedName, content: true}
  │   │  成功 → 提取 response.symbol.content
  │   └─ 失败（符号未找到/网络超时）
  │      │
  │      ├─ 第2层: MCP context --content --file {filePath}
  │      │   │  成功 → 提取 response.symbol.content
  │      │   └─ 失败
  │      │      │
  │      │      └─ 第3层: Files.readString(filePath)
  │      │          │  成功 → 全文（截断到单文件上限）
  │      │          └─ 失败 → 跳过此符号
```

**并行 MCP 调用**：对 topK 个结果同时发起 MCP 调用，用 `CompletableFuture` 等待全部完成或超时（5 秒），避免串行等待。

**格式化输出示例**:

````markdown
## Relevant Code from Codebase

### `com.thinkingcoding.agentloop.v2.orchestrator.AgentOrchestrator` (CLASS) — similarity 0.87
`src/main/java/.../AgentOrchestrator.java`

```java
public class AgentOrchestrator {
    private final LangChainPlanner planner;
    private final ReActDriver reactDriver;
    
    public void onUserInput(String input) {
        TurnContext ctx = new TurnContext(sessionId, modelName, history);
        history.add(new ChatMessage("user", input));
        // ...
    }
}
```

### `com.thinkingcoding.agentloop.v2.plan.LangChainPlanner` (CLASS) — similarity 0.82
...
````

**关键设计决策**:

- 所有异常静默降级，返回空字符串 `""`——RAG 是增强，不是依赖
- 并行 MCP 调用 + 总超时 5 秒，避免拖慢对话
- MCP 调用带 `content: true` 参数，GitNexus 用已有 AST 数据直接返回，无需重新解析

## 修改文件（共 5 个）

### 1. `AppConfig.RagConfig` — 新增配置字段

**路径**: `src/main/java/com/thinkingcoding/config/AppConfig.java`

在已有的 `RagConfig` 内部类中添加：

```java
@JsonProperty("ragContextEnabled")
private boolean ragContextEnabled = true;

@JsonProperty("ragTopK")
private int ragTopK = 5;

@JsonProperty("ragMaxCodeChars")
private int ragMaxCodeChars = 8000;

@JsonProperty("ragMinSimilarity")
private double ragMinSimilarity = 0.5;

// + getters/setters
```

### 2. `LangChainService` — 构造函数 + `prepareMessages()` 注入

**路径**: `src/main/java/com/thinkingcoding/service/LangChainService.java`

**构造函数变更**: 新增 `RagContextEnricher ragEnricher` 参数（可为 null）：

```java
public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry,
                        ContextManager contextManager, RagContextEnricher ragEnricher)
```

**`prepareMessages()` 变更** (第 537 行): 在 Part 1 (System Message) 和 Part 2 (History) 之间插入 Part 1.5：

```java
// Part 1.5: RAG Code Context
if (ragEnricher != null && ragEnricher.isEnabled()) {
    try {
        String ragContext = ragEnricher.enrich(input);
        if (ragContext != null && !ragContext.isBlank()) {
            messages.add(SystemMessage.from(ragContext));
        }
    } catch (Exception e) {
        log.warn("RAG enrichment failed silently: {}", e.getMessage());
    }
}
```

选择此位置的理由：
- `SystemMessage` 角色让 LLM 将其视为权威参考信息
- 在 History 之前，不会被长对话冲淡
- 不在 `ContextManager` 中做——RAG 是消息装配层逻辑，与对话管理无关

### 3. `ThinkingCodingContext` — 组装 & 注入

**路径**: `src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java`

在创建 `EmbeddingService` + `GraphEmbeddingStore` 的代码块之后（约第 163 行），新增：

```java
RagContextEnricher ragEnricher = null;
if (embeddingService != null && graphEmbeddingStore != null && mcpService != null) {
    ragEnricher = new RagContextEnricher(
        embeddingService,
        graphEmbeddingStore,
        mcpService,
        appConfig.getRag().getGitnexus().getServerName(),
        Path.of(workspacePath),
        appConfig.getRag()
    );
}
```

然后在创建 `AIService`（第 199 行）时传入：

```java
AIService aiService = new LangChainService(appConfig, toolRegistry, contextManager, ragEnricher);
```

### 4. `config.yaml` — 新增配置项

**路径**: `src/main/resources/config.yaml`

```yaml
rag:
  baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  apiKey: "sk-xxx"
  embeddingModel: "text-embedding-v4"
  ragContextEnabled: true       # 新增
  ragTopK: 5                    # 新增
  ragMaxCodeChars: 8000         # 新增
  ragMinSimilarity: 0.5         # 新增
  pgvector:
    host: "localhost"
    port: 5432
    database: "thinkingcoding"
    user: "postgres"
    password: "xxx"
  gitnexus:
    serverName: "gitnexus"
    contextTool: "context"
    repo: ""
    autoConnect: true
```

## 完整数据流

```
用户输入: "AgentOrchestrator 是怎么处理用户输入的？"
  │
  ▼
LangChainService.prepareMessages(input, history)
  │
  ├─ Part 1: System Message（角色定义、Skills）
  │
  ├─ Part 1.5: 【NEW】RagContextEnricher.enrich(input)
  │     │
  │     ├─ EmbeddingService.embed(question)
  │     │   → float[1024] queryVector
  │     │
  │     ├─ GraphEmbeddingStore.search(queryVector, topK=5)
  │     │   → [SearchResult("com...AgentOrchestrator", ".../AgentOrchestrator.java", CLASS, 0.87),
  │     │      SearchResult("com...LangChainPlanner", ".../LangChainPlanner.java", CLASS, 0.82),
  │     │      SearchResult("com...ReActDriver", ".../ReActDriver.java", CLASS, 0.76),
  │     │      ...]
  │     │
  │     ├─ 并行调用 GitNexus MCP context --content × N
  │     │   │
  │     │   ├─ MCP call: context {name: "com...AgentOrchestrator", content: true}
  │     │   │   → { symbol: { content: "public class AgentOrchestrator { ... }", startLine: 30, endLine: 320 } }
  │     │   │
  │     │   ├─ MCP call: context {name: "com...LangChainPlanner", content: true}
  │     │   │   → { symbol: { content: "public class LangChainPlanner { ... }" } }
  │     │   │
  │     │   └─ MCP call: context {name: "com...ReActDriver", content: true}
  │     │       → 失败（符号未找到）
  │     │       → 降级: context --file ".../ReActDriver.java"
  │     │       → 成功
  │     │
  │     └─ 格式化输出:
  │         """
  │         ## Relevant Code from Codebase
  │         
  │         ### `com...AgentOrchestrator` (CLASS) — similarity 0.87
  │         ```java
  │         public class AgentOrchestrator {
  │             public void onUserInput(String input) { ... }
  │             ...
  │         }
  │         ```
  │         
  │         ### `com...LangChainPlanner` (CLASS) — similarity 0.82
  │         ...
  │         """
  │
  ├─ Part 2: History（对话历史）
  │
  └─ Part 3: CurrentContext + UserMessage
```

## 验证方案

1. **启动前检查**: `gitnexus status` 确认索引非过期，`psql` 确认 pgvector 有数据
2. **启动项目**: `mvn exec:java -Dexec.mainClass="com.thinkingcoding.ThinkingCodingCLI"`
3. **提问测试**: "AgentOrchestrator 的 onUserInput 方法做了什么？"
4. **日志验证**: 检查日志确认 `RagContextEnricher` 检索到了 AgentOrchestrator.java 等文件，并成功从 GitNexus MCP 获取源码
5. **LLM 回答验证**: 回答应包含实际代码细节（方法签名、参数类型、调用关系），而非仅凭训练数据猜测
6. **降级测试 A**: 关掉 GitNexus MCP → 应降级到 `Files.readString()`，对话不受影响
7. **降级测试 B**: `ragContextEnabled: false` → 不注入 RAG 上下文，对话正常
8. **配置切换**: `ragTopK: 3` → 确认只检索 3 个符号；`ragMinSimilarity: 0.8` → 确认低相似度被过滤
