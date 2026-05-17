package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.rag.codegraph.CodeGraphSymbol;
import com.thinkingcoding.rag.codegraph.GitNexusCodeGraphMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图谱嵌入索引编排器。
 * 从 GitNexus 获取符号列表 → 补全图谱关系 → 嵌入 → 存入 pgvector。
 */
public class GraphEmbeddingIndexer {
    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingIndexer.class);
    private static final int BATCH_SIZE = 10;

    private final MCPService mcpService;
    private final String gitNexusServer;
    private final String gitNexusRepo;
    private final GitNexusCodeGraphMapper graphMapper;
    private final EmbeddingService embeddingService;
    private final GraphEmbeddingStore store;
    private final Path workspaceRoot;

    public GraphEmbeddingIndexer(MCPService mcpService, String gitNexusServer,
                                  String gitNexusRepo, EmbeddingService embeddingService,
                                  GraphEmbeddingStore store, Path workspaceRoot) {
        this.mcpService = mcpService;
        this.gitNexusServer = gitNexusServer;
        this.gitNexusRepo = gitNexusRepo;
        this.graphMapper = new GitNexusCodeGraphMapper();
        this.embeddingService = embeddingService;
        this.store = store;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 全量索引：从 GitNexus 获取符号列表并嵌入。
     * @param gitCommitHash 当前 HEAD 的 commit hash，用于过期检测
     */
    public IndexResult indexAll(String gitCommitHash) {
        List<String> targets = discoverSymbols();
        if (targets.isEmpty()) {
            log.warn("No symbols discovered from GitNexus");
            return new IndexResult(0, 0, 0, Collections.emptyList());
        }
        log.info("Discovered {} symbols, starting indexing...", targets.size());
        return indexTargets(targets, gitCommitHash);
    }

    /**
     * 从 GitNexus query 获取仓库中的符号列表。
     * 使用多个宽泛查询词来覆盖仓库中的类。
     */
    private List<String> discoverSymbols() {
        // 使用多个英文通用查询词 + 中文查询词来覆盖仓库
        String[] broadQueries = {"class", "service", "config", "manager", "handler",
                                  "controller", "util", "model", "tool", "driver",
                                  "类", "服务", "配置", "管理", "工具"};
        Map<String, String> discovered = new LinkedHashMap<>();

        for (String q : broadQueries) {
            try {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("query", q);
                if (gitNexusRepo != null && !gitNexusRepo.isBlank()) {
                    args.put("repo", gitNexusRepo);
                }
                args.put("limit", 50);

                Object response = mcpService.callTool(gitNexusServer, "query", args);
                Map<String, Object> payload = coercePayload(response);
                if (payload.isEmpty()) continue;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> definitions =
                        (List<Map<String, Object>>) payload.getOrDefault("definitions", Collections.emptyList());
                for (Map<String, Object> def : definitions) {
                    String name = firstNonBlank(
                            str(def.get("name")),
                            str(def.get("qualifiedName")),
                            extractNameFromUid(str(def.get("id")))
                    );
                    if (name != null && !name.isBlank()) {
                        discovered.putIfAbsent(name, str(def.get("filePath")));
                    }
                    if (discovered.size() >= 2000) break; // 安全上限
                }
            } catch (Exception e) {
                log.debug("Query '{}' failed: {}", q, e.getMessage());
            }
        }
        log.info("Discovered {} unique symbols from GitNexus", discovered.size());
        return new ArrayList<>(discovered.keySet());
    }

    /**
     * 索引指定的符号列表。
     */
    public IndexResult indexTargets(List<String> targets, String gitCommitHash) {
        AtomicInteger indexed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // 分两阶段：阶段1 收集 CodeGraphSymbol，阶段2 批量嵌入
        List<CodeGraphSymbol> symbols = new ArrayList<>();
        for (String target : targets) {
            try {
                if (store.exists(target)) {
                    skipped.incrementAndGet();
                    continue;
                }
                CodeGraphSymbol symbol = resolveSymbol(target);
                if (symbol != null) {
                    symbols.add(symbol);
                }
            } catch (Exception e) {
                failed.incrementAndGet();
                errors.add(target + ": " + e.getMessage());
            }
        }

        // 阶段2：批量嵌入
        List<String> docTexts = new ArrayList<>();
        List<CodeGraphSymbol> batch = new ArrayList<>();
        for (CodeGraphSymbol symbol : symbols) {
            docTexts.add(GraphEnrichedDocument.build(symbol));
            batch.add(symbol);

            if (docTexts.size() >= BATCH_SIZE) {
                embedAndStore(docTexts, batch, gitCommitHash, indexed, failed, errors);
                docTexts.clear();
                batch.clear();
            }
        }
        // 最后一批
        if (!docTexts.isEmpty()) {
            embedAndStore(docTexts, batch, gitCommitHash, indexed, failed, errors);
        }

        return new IndexResult(indexed.get(), skipped.get(), failed.get(), errors);
    }

    private void embedAndStore(List<String> texts, List<CodeGraphSymbol> batch,
                                String gitCommitHash, AtomicInteger indexed,
                                AtomicInteger failed, List<String> errors) {
        try {
            float[][] vectors = embeddingService.embedBatch(texts);
            for (int i = 0; i < batch.size(); i++) {
                CodeGraphSymbol sym = batch.get(i);
                store.upsert(sym.getQualifiedName(), vectors[i],
                        sym.getFilePath().toString(),
                        sym.getKind().name(),
                        gitCommitHash);
                indexed.incrementAndGet();
            }
            log.debug("Indexed batch of {} symbols", batch.size());
        } catch (IOException e) {
            log.error("Batch embedding failed: {}", e.getMessage());
            for (CodeGraphSymbol sym : batch) {
                failed.incrementAndGet();
                errors.add(sym.getQualifiedName() + ": embedding failed");
            }
        }
    }

    /**
     * 调用 GitNexus context 获取符号的图谱数据。
     */
    CodeGraphSymbol resolveSymbol(String target) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("name", target);
            args.put("target", target);
            if (gitNexusRepo != null && !gitNexusRepo.isBlank()) {
                args.put("repo", gitNexusRepo);
            }
            Object response = mcpService.callTool(gitNexusServer, "context", args);
            GitNexusCodeGraphMapper.GitNexusMappingResult mapping =
                    graphMapper.map(workspaceRoot, target, response);
            return mapping.getTarget();
        } catch (Exception e) {
            log.debug("Failed to resolve symbol {}: {}", target, e.getMessage());
            return null;
        }
    }

    /**
     * 检查嵌入是否过期（对比 git commit hash）。
     */
    public boolean isStale(String currentCommitHash) {
        String stored = store.getStoredCommitHash();
        return stored != null && !stored.equals(currentCommitHash);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> coercePayload(Object response) {
        if (response == null) return Collections.emptyMap();
        if (response instanceof Map<?, ?> m) return castMap(m);
        if (response instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> contentMap) {
            Object text = contentMap.get("text");
            if (text instanceof String s && s.trim().startsWith("{")) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
        }
        // unwrap content
        Object content = result.get("content");
        if (content instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> contentMap) {
            Object text = contentMap.get("text");
            if (text instanceof String s && s.trim().startsWith("{")) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private String str(Object val) {
        if (val == null) return null;
        String s = String.valueOf(val).trim();
        return s.isEmpty() ? null : s;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String extractNameFromUid(String uid) {
        if (uid == null) return null;
        int idx = uid.indexOf(':');
        if (idx >= 0 && idx + 1 < uid.length()) return uid.substring(idx + 1).trim();
        return uid.trim();
    }

    public record IndexResult(int total, int indexed, int skipped, int failed,
                               List<String> errors) {
        public IndexResult(int indexed, int skipped, int failed, List<String> errors) {
            this(indexed + skipped + failed, indexed, skipped, failed, errors);
        }
    }
}