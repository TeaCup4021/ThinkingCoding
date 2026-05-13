package com.thinkingcoding.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.rag.embedding.EmbeddingService;
import com.thinkingcoding.rag.embedding.GraphEmbeddingStore;
import com.thinkingcoding.rag.embedding.GraphEmbeddingStore.SearchResult;
import com.thinkingcoding.tools.BaseTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱结构搜索工具。
 * 将用户查询转为嵌入向量，在 pgvector 中搜索结构相似的代码符号。
 */
public class GraphSearchTool extends BaseTool {

    private static final int DEFAULT_TOP_K = 5;

    private final AppConfig appConfig;
    private final GraphEmbeddingStore store;
    private final EmbeddingService embeddingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphSearchTool(AppConfig appConfig, GraphEmbeddingStore store,
                           EmbeddingService embeddingService) {
        super("graph_search",
              "Search the codebase by structural similarity. " +
              "Input: a natural language description of what kind of code or module you're looking for. " +
              "Returns: structurally similar symbols ranked by relevance, with their dependency summaries. " +
              "Use when: you want to find classes with similar dependency patterns, " +
              "related architecture modules, or code with specific structural characteristics. " +
              "NOT for: exact name lookups (use code_graph), " +
              "literal text searches (use grep_search), or " +
              "general code semantic search (use semantic_search).");
        this.appConfig = appConfig;
        this.store = store;
        this.embeddingService = embeddingService;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = parseParams(input);
            String query = stringParam(params, "query", input);
            int topK = intParam(params, "topK", DEFAULT_TOP_K);

            float[] queryVector = embeddingService.embed(query);
            List<SearchResult> results = store.search(queryVector, topK);

            String output = formatResults(query, results);
            return success(output, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("Graph search failed: " + e.getMessage(),
                         System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "rag";
    }

    @Override
    public boolean isEnabled() {
        return appConfig == null || appConfig.getRag() == null || appConfig.getRag().isEnabled();
    }

    @Override
    public Object getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Search the codebase by structural/dependency similarity using graph embeddings");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", field("string",
                "Natural language description of what kind of code you want to find, " +
                "e.g. 'classes that manage configuration and initialization'"));
        properties.put("topK", field("integer", "Maximum number of results (default: " + DEFAULT_TOP_K + ")"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> field(String type, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("description", description);
        return f;
    }

    private Map<String, Object> parseParams(String input) throws Exception {
        if (input != null && input.trim().startsWith("{")) {
            return mapper.readValue(input, Map.class);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("query", input);
        return fallback;
    }

    private String stringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number n) return Math.max(1, n.intValue());
        if (value != null) {
            try { return Math.max(1, Integer.parseInt(String.valueOf(value))); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private String formatResults(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 图谱结构搜索: \"").append(query).append("\"\n\n");

        if (results.isEmpty()) {
            sb.append("未找到匹配的符号。尝试不同的关键词，或运行 `/rag index` 建立索引。\n");
            return sb.toString();
        }

        sb.append("### 匹配结果 (按相似度排列)\n\n");
        int index = 1;
        for (SearchResult r : results) {
            sb.append(index++).append(". **").append(r.qualifiedName()).append("**");
            sb.append(" (").append(r.kind()).append(")");
            sb.append(" — 相似度 ").append(String.format("%.2f", r.similarity())).append("\n");
            sb.append("   `").append(r.filePath()).append("`\n");
            sb.append("\n");
        }

        sb.append("> 使用 `code_graph(target=\"完整类名\")` 对任意符号做 360° 依赖分析\n");
        return sb.toString();
    }
}