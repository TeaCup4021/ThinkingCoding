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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图谱嵌入索引编排器。
 * 从 GitNexus 获取符号列表 → 补全图谱关系 → 嵌入 → 存入 pgvector。
 */
public class GraphEmbeddingIndexer {
    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingIndexer.class);
    private static final int BATCH_SIZE = 10;
    private static final int CYPHER_PAGE_SIZE = 500;
    private static final int MAX_FULL_GRAPH_NODES = 20_000;

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
        List<DiscoveredNode> nodes = discoverGraphNodes();
        if (nodes.isEmpty()) {
            log.warn("No GitNexus graph nodes discovered");
            return new IndexResult(0, 0, 0, Collections.emptyList());
        }
        log.info("Discovered {} GitNexus graph nodes, starting indexing...", nodes.size());
        return indexNodes(nodes, gitCommitHash);
    }

    /**
     * 增量索引：对比新旧 commit，只重嵌入变更的符号。
     */
    public IndexResult incrementalIndex(String oldCommit, String newCommit) {
        log.info("Starting incremental embedding: {} → {}", oldCommit, newCommit);

        Map<String, Set<String>> symbolsByFile = detectChangedSymbols(oldCommit);
        if (symbolsByFile.isEmpty()) {
            log.info("No symbols changed, embedding is up to date");
            return new IndexResult(0, 0, 0, Collections.emptyList());
        }

        Set<String> changedFiles = symbolsByFile.keySet();
        log.info("Detected {} changed files from detect_changes", changedFiles.size());

        int deleted = store.deleteByFilePaths(changedFiles);
        log.info("Deleted {} old embeddings for changed files", deleted);

        List<String> targets = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : symbolsByFile.entrySet()) {
            targets.addAll(entry.getValue());
        }
        log.info("Re-indexing {} symbols from {} changed files", targets.size(), changedFiles.size());

        return indexTargets(targets, newCommit);
    }

    Map<String, Set<String>> detectChangedSymbols(String oldCommit) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("scope", "compare");
            args.put("baseRef", oldCommit);
            if (gitNexusRepo != null && !gitNexusRepo.isBlank()) {
                args.put("repo", gitNexusRepo);
            }

            log.info("Calling GitNexus detect_changes: scope=compare, baseRef={}", oldCommit);
            Object response = mcpService.callTool(gitNexusServer, "detect_changes", args);
            String text = extractTextContent(response);

            if (text == null || text.isBlank()) {
                log.warn("detect_changes returned empty response");
                return result;
            }

            boolean inSymbolsSection = false;
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Changed symbols:")) {
                    inSymbolsSection = true;
                    continue;
                }
                if (inSymbolsSection) {
                    if (trimmed.startsWith("Affected execution flows:")
                            || trimmed.startsWith("Affected processes:")) {
                        break;
                    }
                    int arrowIdx = trimmed.indexOf("→");
                    if (arrowIdx > 0) {
                        String left = trimmed.substring(0, arrowIdx).trim();
                        String filePath = trimmed.substring(arrowIdx + 1).trim();
                        int lastSpace = left.lastIndexOf(' ');
                        String name = lastSpace > 0 ? left.substring(lastSpace + 1) : left;

                        if (!name.isBlank() && !filePath.isBlank()) {
                            result.computeIfAbsent(filePath, k -> new LinkedHashSet<>()).add(name);
                        }
                    }
                }
            }
            log.info("detect_changes parsed: {} symbols in {} files",
                    result.values().stream().mapToInt(Set::size).sum(), result.size());
        } catch (Exception e) {
            log.warn("detect_changes call failed, falling back to empty delta: {}", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    String extractTextContent(Object response) {
        if (response == null) return null;
        if (response instanceof String s) return s;
        if (response instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m) {
                Object text = m.get("text");
                if (text instanceof String s) return s;
            }
        }
        if (response instanceof Map<?, ?> m) {
            Object content = m.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> cm) {
                    Object text = cm.get("text");
                    if (text instanceof String s) return s;
                }
            }
        }
        log.warn("Unable to extract text from response type: {}",
                response.getClass().getName());
        return null;
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
     * 从 GitNexus 图谱中枚举全部节点。优先走 cypher，失败时退回旧的 query 抽样逻辑。
     */
    List<DiscoveredNode> discoverGraphNodes() {
        List<DiscoveredNode> nodes = discoverGraphNodesViaCypher();
        if (!nodes.isEmpty()) {
            return nodes;
        }

        List<DiscoveredNode> fallback = new ArrayList<>();
        for (String symbol : discoverSymbols()) {
            fallback.add(DiscoveredNode.fromTarget(symbol));
        }
        return fallback;
    }

    private List<DiscoveredNode> discoverGraphNodesViaCypher() {
        Map<String, DiscoveredNode> nodes = new LinkedHashMap<>();
        for (int skip = 0; skip < MAX_FULL_GRAPH_NODES; skip += CYPHER_PAGE_SIZE) {
            String query = "MATCH (n) RETURN "
                    + "n.id AS id, "
                    + "labels(n) AS type, "
                    + "n.name AS name, "
                    + "n.filePath AS filePath, "
                    + "n.startLine AS startLine, "
                    + "n.endLine AS endLine, "
                    + "replace(n.content, '\\n', ' ') AS content, "
                    + "n.description AS description, "
                    + "n.label AS label, "
                    + "n.processType AS processType, "
                    + "n.stepCount AS stepCount, "
                    + "n.returnType AS returnType, "
                    + "n.parameterCount AS parameterCount "
                    + "ORDER BY n.id SKIP " + skip + " LIMIT " + CYPHER_PAGE_SIZE;
            try {
                Map<String, Object> payload = callCypher(query);
                List<Map<String, String>> rows = parseCypherRows(payload);
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, String> row : rows) {
                    DiscoveredNode node = DiscoveredNode.fromRow(row);
                    if (node.key() != null && !node.key().isBlank()) {
                        nodes.putIfAbsent(node.key(), node);
                    }
                }
                if (rows.size() < CYPHER_PAGE_SIZE) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to discover GitNexus graph nodes via cypher: {}", e.getMessage());
                return Collections.emptyList();
            }
        }

        if (!nodes.isEmpty()) {
            attachRelationSummaries(nodes);
        }
        log.info("Discovered {} GitNexus nodes via cypher", nodes.size());
        return new ArrayList<>(nodes.values());
    }

    private void attachRelationSummaries(Map<String, DiscoveredNode> nodes) {
        for (int skip = 0; skip < MAX_FULL_GRAPH_NODES * 5; skip += CYPHER_PAGE_SIZE) {
            String query = "MATCH (a)-[r:CodeRelation]->(b) RETURN "
                    + "a.id AS fromId, r.type AS type, b.id AS toId, "
                    + "b.name AS toName, b.filePath AS toFilePath "
                    + "ORDER BY a.id SKIP " + skip + " LIMIT " + CYPHER_PAGE_SIZE;
            try {
                Map<String, Object> payload = callCypher(query);
                List<Map<String, String>> rows = parseCypherRows(payload);
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, String> row : rows) {
                    DiscoveredNode source = nodes.get(row.get("fromId"));
                    if (source == null) {
                        continue;
                    }
                    String relation = firstNonBlank(row.get("type"), "RELATED");
                    String target = firstNonBlank(row.get("toName"), row.get("toId"), row.get("toFilePath"));
                    if (target != null && !target.isBlank()) {
                        source.addRelation(relation, target);
                    }
                }
                if (rows.size() < CYPHER_PAGE_SIZE) {
                    break;
                }
            } catch (Exception e) {
                log.debug("Failed to attach GitNexus relation summaries: {}", e.getMessage());
                return;
            }
        }
    }

    private Map<String, Object> callCypher(String query) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        if (gitNexusRepo != null && !gitNexusRepo.isBlank()) {
            args.put("repo", gitNexusRepo);
        }
        Object response = mcpService.callTool(gitNexusServer, "cypher", args);
        return coercePayload(response);
    }

    private List<Map<String, String>> parseCypherRows(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, String>> rows = parseJsonRows(payload.get("rows"));
        if (!rows.isEmpty()) {
            return rows;
        }
        rows = parseJsonRows(payload.get("data"));
        if (!rows.isEmpty()) {
            return rows;
        }

        Object markdown = payload.get("markdown");
        if (markdown instanceof String text) {
            return parseMarkdownTable(text);
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJsonRows(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), str(entry.getValue()));
                    }
                }
                rows.add(row);
            } else if (item instanceof List<?> rawRow) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < rawRow.size(); i++) {
                    row.put(String.valueOf(i), str(rawRow.get(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, String>> parseMarkdownTable(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Collections.emptyList();
        }

        List<String> lines = markdown.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .toList();
        if (lines.size() < 3) {
            return Collections.emptyList();
        }

        List<String> headers = splitMarkdownRow(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            List<String> cells = splitMarkdownRow(lines.get(i));
            if (cells.isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int col = 0; col < headers.size() && col < cells.size(); col++) {
                row.put(headers.get(col), emptyToNull(cells.get(col)));
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> splitMarkdownRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '|' && !escaped) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
            escaped = ch == '\\' && !escaped;
            if (ch != '\\') {
                escaped = false;
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    /**
     * 索引指定的符号列表。
     */
    public IndexResult indexTargets(List<String> targets, String gitCommitHash) {
        List<DiscoveredNode> nodes = targets.stream()
                .map(DiscoveredNode::fromTarget)
                .toList();
        return indexNodes(nodes, gitCommitHash);
    }

    private IndexResult indexNodes(List<DiscoveredNode> nodes, String gitCommitHash) {
        AtomicInteger indexed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        List<String> docTexts = new ArrayList<>();
        List<EmbeddingRecord> batch = new ArrayList<>();
        for (DiscoveredNode node : nodes) {
            try {
                String key = node.key();
                if (key == null || key.isBlank()) {
                    skipped.incrementAndGet();
                    continue;
                }
                if (store.exists(key)) {
                    skipped.incrementAndGet();
                    continue;
                }
                EmbeddingRecord record = toEmbeddingRecord(node);
                if (record == null) {
                    failed.incrementAndGet();
                    errors.add(key + ": unable to build embedding document");
                    continue;
                }
                docTexts.add(record.text());
                batch.add(record);

                if (docTexts.size() >= BATCH_SIZE) {
                    embedAndStore(docTexts, batch, gitCommitHash, indexed, failed, errors);
                    docTexts.clear();
                    batch.clear();
                }
            } catch (Exception e) {
                failed.incrementAndGet();
                errors.add(node.key() + ": " + e.getMessage());
            }
        }

        if (!docTexts.isEmpty()) {
            embedAndStore(docTexts, batch, gitCommitHash, indexed, failed, errors);
        }

        return new IndexResult(indexed.get(), skipped.get(), failed.get(), errors);
    }

    private EmbeddingRecord toEmbeddingRecord(DiscoveredNode node) {
        if (node.requiresContextLookup()) {
            CodeGraphSymbol symbol = resolveSymbol(node.targetName());
            if (symbol != null) {
                String key = firstNonBlank(node.id(), symbol.getQualifiedName());
                return new EmbeddingRecord(
                        key,
                        GraphEnrichedDocument.build(symbol),
                        symbol.getFilePath().toString(),
                        firstNonBlank(node.type(), symbol.getKind().name())
                );
            }
        }
        return new EmbeddingRecord(
                node.key(),
                GraphEnrichedDocument.build(node.toDocumentFields()),
                normalizeFilePathForStorage(firstNonBlank(node.filePath(), node.id())),
                node.type()
        );
    }

    private void embedAndStore(List<String> texts, List<EmbeddingRecord> batch,
                                String gitCommitHash, AtomicInteger indexed,
                                AtomicInteger failed, List<String> errors) {
        try {
            float[][] vectors = embeddingService.embedBatch(texts);
            for (int i = 0; i < batch.size(); i++) {
                EmbeddingRecord record = batch.get(i);
                store.upsert(record.qualifiedName(), vectors[i],
                        record.filePath(),
                        record.kind(),
                        gitCommitHash);
                indexed.incrementAndGet();
            }
            log.debug("Indexed batch of {} symbols", batch.size());
        } catch (IOException e) {
            log.error("Batch embedding failed: {}", e.getMessage());
            for (EmbeddingRecord record : batch) {
                failed.incrementAndGet();
                errors.add(record.qualifiedName() + ": embedding failed");
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

    private String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeFilePathForStorage(String value) {
        String text = firstNonBlank(value, workspaceRoot.toString());
        if (text == null) {
            return workspaceRoot.toString();
        }
        if (text.startsWith("File:")) {
            return text.substring("File:".length());
        }
        int firstColon = text.indexOf(':');
        int lastColon = text.lastIndexOf(':');
        if (firstColon >= 0 && lastColon > firstColon) {
            String between = text.substring(firstColon + 1, lastColon);
            if (between.contains("/") || between.contains("\\") || between.endsWith(".java")) {
                return between;
            }
        }
        return text;
    }

    private record EmbeddingRecord(String qualifiedName, String text, String filePath, String kind) {}

    record DiscoveredNode(String id,
                          String type,
                          String name,
                          String filePath,
                          String startLine,
                          String endLine,
                          String content,
                          String description,
                          String label,
                          String processType,
                          String stepCount,
                          String returnType,
                          String parameterCount,
                          Map<String, List<String>> relations) {

        static DiscoveredNode fromTarget(String target) {
            return new DiscoveredNode(target, "Symbol", target, null, null, null, null,
                    null, null, null, null, null, null, new LinkedHashMap<>());
        }

        static DiscoveredNode fromRow(Map<String, String> row) {
            String id = firstNonBlankStatic(row.get("id"), row.get("0"), row.get("uid"));
            return new DiscoveredNode(
                    id,
                    firstNonBlankStatic(row.get("type"), row.get("kind"), typeFromId(id)),
                    firstNonBlankStatic(row.get("name"), nameFromId(id)),
                    row.get("filePath"),
                    row.get("startLine"),
                    row.get("endLine"),
                    row.get("content"),
                    row.get("description"),
                    row.get("label"),
                    row.get("processType"),
                    row.get("stepCount"),
                    row.get("returnType"),
                    row.get("parameterCount"),
                    new LinkedHashMap<>()
            );
        }

        String key() {
            return firstNonBlankStatic(id, name);
        }

        String targetName() {
            return firstNonBlankStatic(name, nameFromId(id), id);
        }

        boolean requiresContextLookup() {
            return "Symbol".equals(type);
        }

        void addRelation(String relation, String target) {
            relations.computeIfAbsent(relation, key -> new ArrayList<>());
            List<String> targets = relations.get(relation);
            if (targets.size() < 30 && !targets.contains(target)) {
                targets.add(target);
            }
        }

        GraphEnrichedDocument.DocumentFields toDocumentFields() {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            put(fields, "ID", id);
            put(fields, "TYPE", type);
            put(fields, "NAME", name);
            put(fields, "FILE", filePath);
            put(fields, "LINES", lineRange());
            put(fields, "LABEL", label);
            put(fields, "DESCRIPTION", description);
            put(fields, "PROCESS_TYPE", processType);
            put(fields, "STEP_COUNT", stepCount);
            put(fields, "RETURN_TYPE", returnType);
            put(fields, "PARAMETER_COUNT", parameterCount);
            put(fields, "CONTENT", content);
            for (Map.Entry<String, List<String>> entry : relations.entrySet()) {
                fields.put(entry.getKey(), entry.getValue());
            }
            return new GraphEnrichedDocument.DocumentFields(type, key(), filePath, fields);
        }

        private String lineRange() {
            if (startLine == null && endLine == null) {
                return null;
            }
            return firstNonBlankStatic(startLine, "?") + "-" + firstNonBlankStatic(endLine, "?");
        }

        private static void put(Map<String, List<String>> fields, String key, String value) {
            if (value != null && !value.isBlank()) {
                fields.put(key, List.of(value));
            }
        }

        private static String typeFromId(String id) {
            if (id == null) {
                return null;
            }
            int idx = id.indexOf(':');
            return idx > 0 ? id.substring(0, idx) : null;
        }

        private static String nameFromId(String id) {
            if (id == null) {
                return null;
            }
            int idx = id.lastIndexOf(':');
            return idx >= 0 && idx + 1 < id.length() ? id.substring(idx + 1) : id;
        }

        private static String firstNonBlankStatic(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }

    public record IndexResult(int total, int indexed, int skipped, int failed,
                               List<String> errors) {
        public IndexResult(int indexed, int skipped, int failed, List<String> errors) {
            this(indexed + skipped + failed, indexed, skipped, failed, errors);
        }
    }
}
