# GitNexus 知识图谱与 pgvector 嵌入向量增量同步方案

## 问题

当前 RAG 系统中，GitNexus 知识图谱更新（`npx gitnexus analyze`）后，pgvector 中的嵌入向量**不会自动更新**。两条更新路径是独立的：

```
路径 1: GitNexusStalenessChecker → npx gitnexus analyze → 图谱刷新
路径 2: 启动时 graphEmbeddingStore.isEmpty() → GraphEmbeddingIndexer.indexAll()
```

导致 `analyze` 成功后，pgvector 里的嵌入向量仍然是基于旧图谱计算的——符号可能已增删，依赖关系可能已变化。

## 方案概述

在 `GitNexusStalenessChecker.ensureFresh()` 检测到图谱过期并成功执行 `analyze` 后，自动触发增量嵌入更新。使用 GitNexus 的 `detect_changes` MCP 工具获取新旧图谱的符号级差异，只对变更的符号重新嵌入。

```
GitNexusStalenessChecker.ensureFresh()
  │
  ├─ parseStaleness() → stale
  ├─ runCommand("npx gitnexus analyze") → 成功
  │
  └─ onIndexRefreshed 回调               ← 新增
       │
       └─ GraphEmbeddingIndexer.incrementalIndex(oldCommit, newCommit)
            │
            ├─ MCPService.callTool("gitnexus", "detect_changes", {
            │      scope: "compare",
            │      baseRef: oldCommit      // pgvector 中存储的旧 commit
            │  })
            │  → 返回变更符号列表（JSON）
            │
            ├─ 对每个变更符号：
            │    ├─ DELETE FROM pgvector （清除旧嵌入）
            │    ├─ GitNexus context → CodeGraphSymbol
            │    ├─ GraphEnrichedDocument.build()
            │    └─ EmbeddingService.embed() → upsert
            │
            └─ 更新 pgvector 中的 git_commit_hash
```

## 为什么用 `detect_changes`

`detect_changes` 是 GitNexus 提供的 MCP 工具，直接返回新旧图谱之间的符号级差异：

```
detect_changes({scope: "compare", baseRef: "<oldCommit>"})

→ Changed: 7 files, 27 symbols
→ Changed symbols:
    execute → src/main/java/.../CodeGraphTool.java
    AppConfig → src/main/java/.../AppConfig.java
    ThinkingCodingContext → src/main/java/.../ThinkingCodingContext.java
    ...
→ Affected processes: 32
→ Risk: critical
```

相比 `git diff --name-only`（文件级），`detect_changes` 的优势：
- 返回**符号级**粒度，不需要再从文件路径反查符号
- 理解调用图，能检测跨文件影响
- 作为 MCP 工具调用，返回结构化 JSON，无需解析 CLI 文本

## 涉及改动

| 文件 | 改动 |
|------|------|
| `GitNexusStalenessChecker.java` | 新增 `onIndexRefreshed` 回调字段 + setter；`checkStaleness()` 中 analyze 成功后调用回调 |
| `GraphEmbeddingIndexer.java` | 新增 `incrementalIndex(oldCommit, newCommit)` 方法 |
| `GraphEmbeddingStore.java` | 新增 `findByFilePaths()` 查询方法，用于清理旧符号 |
| `ThinkingCodingContext.java` | 设置 `stalenessChecker.onIndexRefreshed` 回调，连接 indexer |

## 数据流

```
配置示例 (config.yaml):

rag:
  gitnexus:
    stalenessCheck:
      enabled: true
      cacheTtlSeconds: 60
      analyzeTimeoutSeconds: 180
    incrementalEmbedding:
      enabled: true         # 是否在 analyze 后自动增量嵌入
      fallbackThreshold: 0.5  # 变更符号占比超过此值时降级为全量重建
```

## 后续改进：内容哈希精确判定

当前方案对 `detect_changes` 返回的所有变更符号都重新嵌入。但有些符号虽然文件变了，其 API 签名和依赖关系却没变——此时 `GraphEnrichedDocument.build()` 生成的嵌入文本与之前完全相同，嵌入向量不变，属于浪费。

后续可在 `graph_embeddings` 表中添加 `content_hash` 列：

```sql
ALTER TABLE graph_embeddings ADD COLUMN content_hash TEXT;
```

`upsert` 时存入 `GraphEnrichedDocument.build()` 文本的 MD5。增量更新时先比较哈希，只对真正变化的符号调嵌入 API：

```
对每个 detect_changes 返回的符号：
  ├─ GitNexus context → GraphEnrichedDocument.build() → MD5
  ├─ 与 pgvector 存储的 content_hash 比较
  ├─ 相同 → 跳过（零嵌入费用）
  └─ 不同 → 嵌入 → UPDATE
```

这对大型仓库（10 万+ 符号）意义更大——一次提交可能涉及 500 个文件的 2000 个符号，但真正 API/关系改变的只有几十个。

## 实现决策（已确认）

| 问题 | 决策 |
|------|------|
| `detect_changes` MCP 返回格式 | 实现前先调 MCP 验证 JSON 结构 |
| 同步/异步 | **同步嵌入**，analyze 已阻塞 30–120s，增量嵌入 5–10s 可接受 |
| 删除符号检测 | 变更文件列表中的符号在 GitNexus context 返回 null → DELETE |
| `content_hash` 列 | **本次不加**，作为后续改进 |
| 旧 commit hash | 从 `GraphEmbeddingStore.getStoredCommitHash()` 获取 |
| pgvector 为空时 | 走全量索引，不触发增量 |
| `refresh` 参数 | 语义不变，analyze 触发的增量嵌入对调用者透明 |
