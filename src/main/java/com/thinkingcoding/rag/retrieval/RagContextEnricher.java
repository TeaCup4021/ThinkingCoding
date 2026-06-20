package com.thinkingcoding.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.rag.embedding.EmbeddingService;
import com.thinkingcoding.rag.embedding.GraphEmbeddingStore;
import com.thinkingcoding.rag.embedding.GraphEmbeddingStore.SearchResult;
import com.thinkingcoding.tools.rag.HybridLocatorTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 上下文增强器。
 * 用户问题向量化 → pgvector 搜索 → GitNexus MCP context --uid 获取 startLine/endLine
 * → 从本地文件按行切片 → 获得语言无关的精准代码块 → 注入 LLM 上下文。
 */
public class RagContextEnricher {
    private static final Logger log = LoggerFactory.getLogger(RagContextEnricher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final EmbeddingService embeddingService;
    private final GraphEmbeddingStore store;
    private final MCPService mcpService;
    private final String gitNexusServer;
    private final Path workspaceRoot;
    private final AppConfig.RagConfig config;
    private final HybridLocatorTool hybridLocatorTool;

    public RagContextEnricher(EmbeddingService embeddingService,
                              GraphEmbeddingStore store,
                              MCPService mcpService,
                              String gitNexusServer,
                              Path workspaceRoot,
                              AppConfig.RagConfig config) {
        this(embeddingService, store, mcpService, gitNexusServer, workspaceRoot, config, null);
    }

    public RagContextEnricher(EmbeddingService embeddingService,
                              GraphEmbeddingStore store,
                              MCPService mcpService,
                              String gitNexusServer,
                              Path workspaceRoot,
                              AppConfig.RagConfig config,
                              HybridLocatorTool hybridLocatorTool) {
        this.embeddingService = embeddingService;
        this.store = store;
        this.mcpService = mcpService;
        this.gitNexusServer = gitNexusServer;
        this.workspaceRoot = workspaceRoot;
        this.config = config;
        this.hybridLocatorTool = hybridLocatorTool;
    }

    public boolean isEnabled() {
        return config != null && config.isRagContextEnabled();
    }

    public String enrich(String userInput) {
        if (userInput == null || userInput.isBlank()) return "";

        try {
            long t0 = System.currentTimeMillis();
            String hybridContext = enrichWithHybridLocator(userInput, t0);
            if (!hybridContext.isBlank()) {
                return hybridContext;
            }
            float[] queryVector = embeddingService.embed(userInput);
            log.info("RAG: embedded question → {}d vector ({}ms)",
                    queryVector.length, System.currentTimeMillis() - t0);

            List<SearchResult> results = store.searchDefault(queryVector, config.getRagTopK());
            if (results.isEmpty()) {
                log.info("RAG: pgvector search returned 0 results");
                return "";
            }
            for (SearchResult r : results) {
                log.info("RAG:   {} ({} | {})",
                        r.qualifiedName(), r.kind(), String.format("%.3f", r.similarity()));
            }

            results = results.stream()
                    .filter(r -> r.similarity() >= config.getRagMinSimilarity())
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                log.info("RAG: all results below similarity threshold {}",
                        config.getRagMinSimilarity());
                return "";
            }

            List<CodeSnippet> snippets = fetchCodeSnippets(results);
            String ctx = formatContext(snippets);
            log.info("RAG: injecting {} chars of code context ({} symbols, {}ms total)",
                    ctx.length(), snippets.size(), System.currentTimeMillis() - t0);
            return ctx;
        } catch (Exception e) {
            log.warn("RAG enrichment failed silently: {}", e.getMessage());
            return "";
        }
    }

    private List<CodeSnippet> fetchCodeSnippets(List<SearchResult> results) {
        List<CodeSnippet> snippets = new ArrayList<>();
        for (SearchResult result : results) {
            CodeSnippet snippet = fetchCode(result);
            if (snippet != null) snippets.add(snippet);
        }
        return snippets;
    }

    private String enrichWithHybridLocator(String userInput, long startedAt) {
        if (hybridLocatorTool == null) {
            return "";
        }

        try {
            int topK = Math.max(1, config.getRagTopK());
            List<HybridLocatorTool.LocatorResult> results = hybridLocatorTool.locateV2(
                    userInput,
                    topK,
                    Math.max(topK * 4, topK),
                    Math.max(topK, 5),
                    Math.max(topK * 2, topK)
            );
            if (results.isEmpty()) {
                log.info("RAG: hybrid locator v2 returned 0 results");
                return "";
            }

            for (HybridLocatorTool.LocatorResult result : results) {
                log.info("RAG: hybrid v2 candidate {} ({} | {} | {})",
                        firstNonBlank(result.qualifiedName(), result.symbol(), result.file()),
                        result.kind(),
                        result.source(),
                        result.score() == null ? "n/a" : String.format("%.3f", result.score()));
            }

            List<CodeSnippet> snippets = fetchCodeSnippetsFromLocator(results);
            String ctx = formatContext(snippets);
            log.info("RAG: hybrid v2 injecting {} chars of code context ({} candidates, {}ms total)",
                    ctx.length(), snippets.size(), System.currentTimeMillis() - startedAt);
            return ctx;
        } catch (Exception e) {
            log.info("RAG: hybrid locator v2 failed, falling back to vector search: {}", e.getMessage());
            return "";
        }
    }

    private List<CodeSnippet> fetchCodeSnippetsFromLocator(List<HybridLocatorTool.LocatorResult> results) {
        List<CodeSnippet> snippets = new ArrayList<>();
        for (HybridLocatorTool.LocatorResult result : results) {
            CodeSnippet snippet = fetchCode(result);
            if (snippet != null) snippets.add(snippet);
        }
        return snippets;
    }

    private CodeSnippet fetchCode(SearchResult result) {
        long t = System.currentTimeMillis();
        String code;

        // Layer 1: MCP context --uid → 获取 startLine/endLine → 按行切片
        code = fetchViaMcpSliced(result.qualifiedName());
        if (code != null) {
            log.info("RAG: [{}] via MCP uid + line slicing ({}ms, similarity {})",
                    result.qualifiedName(), System.currentTimeMillis() - t,
                    String.format("%.2f", result.similarity()));
            return new CodeSnippet(result.qualifiedName(), result.filePath(),
                    result.kind(), result.similarity(), code);
        }

        // Layer 2: 降级到本地文件系统全文件读取
        code = readFile(result.filePath());
        if (code != null) {
            log.info("RAG: [{}] via filesystem fallback (similarity {})",
                    result.qualifiedName(), String.format("%.2f", result.similarity()));
            return new CodeSnippet(result.qualifiedName(), result.filePath(),
                    result.kind(), result.similarity(), code);
        }

        log.info("RAG: [{}] all layers failed — skipped", result.qualifiedName());
        return null;
    }

    /**
     * 通过 GitNexus MCP context --uid 获取符号的 startLine/endLine，
     * 然后从本地文件按行切片返回精准代码块。语言无关。
     */
    private CodeSnippet fetchCode(HybridLocatorTool.LocatorResult result) {
        long t = System.currentTimeMillis();
        String identity = firstNonBlank(result.qualifiedName(), result.symbol(), result.file());
        String code = fetchViaMcpSliced(identity);
        if (code == null && result.symbol() != null && !result.symbol().equals(identity)) {
            code = fetchViaMcpSliced(result.symbol());
        }
        if (code != null) {
            log.info("RAG: [{}] via hybrid locator + MCP line slicing ({}ms, source {})",
                    identity, System.currentTimeMillis() - t, result.source());
            return new CodeSnippet(identity, result.file(), result.kind(), safeScore(result.score()), code);
        }

        code = readFile(result.file());
        if (code != null) {
            log.info("RAG: [{}] via hybrid locator filesystem fallback (source {})",
                    identity, result.source());
            return new CodeSnippet(identity, result.file(), result.kind(), safeScore(result.score()), code);
        }

        log.info("RAG: [{}] hybrid locator candidate had no readable code", identity);
        return null;
    }

    private String fetchViaMcpSliced(String uid) {
        if (uid == null || uid.isBlank() || mcpService == null || gitNexusServer == null || gitNexusServer.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("uid", uid);

            Object response = mcpService.callTool(gitNexusServer, "context", args);
            SymbolPosition pos = extractPosition(response);
            if (pos == null) {
                log.info("RAG: MCP returned null position for uid {}", uid);
                return null;
            }

            // GitNexus startLine/endLine 是基于 1 的
            return readFileLines(pos.filePath, pos.startLine, pos.endLine);
        } catch (Exception e) {
            log.info("RAG: MCP context failed for uid {}: {}", uid, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 MCP 响应，提取 symbol 的 {filePath, startLine, endLine}。
     */
    @SuppressWarnings("unchecked")
    private SymbolPosition extractPosition(Object response) {
        if (response == null) return null;

        Map<String, Object> payload = null;
        String text = null;

        if (response instanceof Map<?, ?> m) {
            Object contentObj = m.get("content");
            if (contentObj instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> contentMap) {
                text = stringValue(contentMap.get("text"));
            }
            if (text == null) payload = castMap(m);
        } else if (response instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> contentMap) {
            text = stringValue(contentMap.get("text"));
        } else if (response instanceof String s && s.trim().startsWith("{")) {
            text = s;
        }

        if (text != null && payload == null) {
            try {
                payload = mapper.readValue(text, Map.class);
            } catch (Exception e) {
                log.info("RAG: JSON parse failed: {}", e.getMessage());
                return null;
            }
        }

        if (payload == null) return null;

        Object symbolObj = payload.get("symbol");
        if (symbolObj == null) return null;
        Map<String, Object> symbol = asMap(symbolObj);
        if (symbol.isEmpty()) return null;

        String filePath = stringField(symbol, "filePath");
        int startLine = intField(symbol, "startLine");
        int endLine = intField(symbol, "endLine");

        if (filePath == null || startLine <= 0 || endLine <= 0) {
            log.info("RAG: symbol missing position info, keys={}", symbol.keySet());
            return null;
        }

        return new SymbolPosition(filePath, startLine, endLine);
    }

    /**
     * 按行号从文件中切片提取代码。
     */
    private String readFileLines(String filePath, int startLine, int endLine) {
        try {
            Path resolved = workspaceRoot.resolve(filePath).toAbsolutePath().normalize();
            if (!Files.exists(resolved)) return null;
            List<String> lines = Files.readAllLines(resolved);
            int from = Math.max(0, startLine - 1);                 // 转为 0-based
            int to = Math.min(endLine, lines.size());              // 不超出文件行数
            if (from >= to) return null;
            return String.join("\n", lines.subList(from, to));
        } catch (IOException e) {
            log.debug("RAG: line slice failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String readFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path resolved = workspaceRoot.resolve(filePath).toAbsolutePath().normalize();
            if (Files.exists(resolved)) return Files.readString(resolved);
        } catch (IOException e) {
            log.debug("RAG: read failed for {}: {}", filePath, e.getMessage());
        }
        return null;
    }

    // ---- JSON helpers ----

    private String stringValue(Object obj) {
        if (obj instanceof String s && !s.isBlank() && s.trim().startsWith("{")) return s;
        return null;
    }

    private String stringField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private int intField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return castMap(map);
        return Collections.emptyMap();
    }

    private double safeScore(Double score) {
        return score == null ? 0.0 : score;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // ---- formatting ----

    private String formatContext(List<CodeSnippet> snippets) {
        if (snippets.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Relevant Code from Codebase\n\n");
        sb.append("The following code was retrieved from the project based on the user's question. ");
        sb.append("Use it to provide an accurate, code-aware answer.\n\n");

        int totalChars = 0;
        int maxChars = config.getRagMaxCodeChars();

        for (CodeSnippet snippet : snippets) {
            sb.append("### `").append(snippet.qualifiedName()).append("` (")
                    .append(snippet.kind()).append(") — similarity ")
                    .append(String.format("%.2f", snippet.similarity())).append("\n");
            sb.append("`").append(snippet.filePath()).append("`\n\n");

            String code = snippet.code();
            String lang = detectLanguage(snippet.filePath());

            if (totalChars + code.length() > maxChars) {
                int remaining = maxChars - totalChars;
                if (remaining > 200) {
                    String trimmed = code.substring(0, Math.min(remaining, code.length()));
                    sb.append("```").append(lang).append("\n");
                    sb.append(trimmed);
                    sb.append("\n// ... (truncated)\n");
                    sb.append("```\n\n");
                }
                break;
            }

            sb.append("```").append(lang).append("\n");
            sb.append(code);
            sb.append("\n```\n\n");
            totalChars += code.length();
        }

        return sb.toString();
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) return "";
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".cs")) return "csharp";
        return "";
    }

    // ---- inner types ----

    private record CodeSnippet(String qualifiedName, String filePath,
                               String kind, double similarity, String code) {}

    private record SymbolPosition(String filePath, int startLine, int endLine) {}
}
