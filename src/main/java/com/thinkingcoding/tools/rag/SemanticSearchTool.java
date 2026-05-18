package com.thinkingcoding.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.GitNexusStalenessChecker;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.BaseTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语义搜索工具，包装 GitNexus MCP 的 query 工具。
 * GitNexus query 使用 BM25 + 向量嵌入 (snowflake-arctic-embed-xs) + RRF 融合排序 + 图一跳扩展，
 * 返回按执行流程分组的符号，按相关性排序。
 */
public class SemanticSearchTool extends BaseTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final double MIN_PROCESS_PRIORITY = 0.09;

    private final AppConfig appConfig;
    private final MCPService mcpService;
    private final GitNexusStalenessChecker stalenessChecker;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticSearchTool(AppConfig appConfig, MCPService mcpService,
                               GitNexusStalenessChecker stalenessChecker) {
        super("semantic_search",
              "Search the codebase using natural language (BM25 keyword + vector embedding + Reciprocal Rank Fusion + 1-hop graph expansion). " +
              "Returns execution flows and symbols grouped by relevance. " +
              "Use for concept-level queries where you do NOT know the exact terms: " +
              "e.g., 'how does authentication work', 'where is the DB pool configured', 'what handles error logging'. " +
              "When you know the exact class/method/variable name, use grep_search instead — it is faster and returns precise matches. " +
              "This tool understands meaning, not just text. Do NOT use it for literal symbol lookups.");
        this.appConfig = appConfig;
        this.mcpService = mcpService;
        this.stalenessChecker = stalenessChecker;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            if (mcpService == null) {
                return error("MCP service is not available. Ensure GitNexus MCP is configured and enabled.",
                             System.currentTimeMillis() - startTime);
            }

            Map<String, Object> params = parseParams(input);
            String query = stringParam(params, "query", null);
            if (query == null || query.isBlank()) {
                return error("Missing required parameter: query", System.currentTimeMillis() - startTime);
            }

            int topK = intParam(params, "topK", resolveTopK());
            String repo = stringParam(params, "repo", resolveRepo());

            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("query", query);
            if (repo != null && !repo.isBlank()) {
                arguments.put("repo", repo);
            }
            arguments.put("limit", topK);

            if (stalenessChecker != null) {
                GitNexusStalenessChecker.StalenessResult staleness = stalenessChecker.ensureFresh();
                if (!staleness.canProceed()) {
                    return error(staleness.getErrorMessage(), System.currentTimeMillis() - startTime);
                }
            }

            Object response = mcpService.callTool(resolveServerName(), "query", arguments);

            String output = renderResponse(query, response, topK);
            return success(output, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("Semantic search failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "rag";
    }

    @Override
    public boolean isEnabled() {
        return appConfig == null || appConfig.getTools().getSemanticSearch().isEnabled();
    }

    @Override
    public Object getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Search the codebase with natural language using GitNexus semantic search (BM25 + embeddings + graph expansion)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", field("string", "Natural language description of what to find, e.g. 'authentication logic' or 'database connection pool'"));
        properties.put("topK", field("integer", "Maximum number of results to return (default: " + DEFAULT_TOP_K + ")"));
        properties.put("repo", field("string", "GitNexus repo name; defaults to rag.gitnexus.repo"));

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

    private String renderResponse(String query, Object response, int topK) {
        Map<String, Object> payload = coerceMap(response);

        if (payload.isEmpty()) {
            return "## Semantic Search: \"" + query + "\"\n\nNo results found. " +
                   "Try different keywords or check if the GitNexus index is up to date (run `npx gitnexus analyze`).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Semantic Search: \"").append(query).append("\"\n");

        // 1. 渲染 definitions（主要搜索结果）
        List<Map<String, Object>> definitions = extractList(payload, "definitions");
        // 2. 渲染 processes（执行流程）
        List<Map<String, Object>> processes = extractList(payload, "processes");
        // 3. process_symbols（流程步骤），按 process_id 分组
        List<Map<String, Object>> processSymbols = extractList(payload, "process_symbols");

        if (definitions.isEmpty() && processes.isEmpty()) {
            sb.append("\n```\n");
            sb.append(truncate(String.valueOf(response), 3000));
            sb.append("\n```\n");
            sb.append("\n> *Use `code_graph` on any symbol above for a 360 view with callers, callees, and dependencies.*");
            return sb.toString();
        }

        // 渲染执行流程
        if (!processes.isEmpty()) {
            sb.append("\n### Execution Flows\n\n");
            int count = 0;
            for (Map<String, Object> process : processes) {
                if (count >= topK) break;
                // 过滤低优先级流程
                Object priorityObj = process.get("priority");
                if (priorityObj instanceof Number && ((Number) priorityObj).doubleValue() < MIN_PROCESS_PRIORITY) {
                    continue;
                }
                renderProcess(sb, process, processSymbols);
                count++;
            }
        }

        // 渲染定义（符号）
        if (!definitions.isEmpty()) {
            sb.append("\n### Matched Symbols (top ").append(Math.min(topK, definitions.size())).append(")\n\n");
            int count = 0;
            for (Map<String, Object> def : definitions) {
                if (count >= topK * 2) break; // definitions 可以多返回一些
                renderDefinition(sb, def);
                count++;
            }
        }

        sb.append("\n> *Use `code_graph` on any symbol above for a 360 view with callers, callees, and dependencies.*");
        return sb.toString();
    }

    private void renderProcess(StringBuilder sb, Map<String, Object> process,
                               List<Map<String, Object>> processSymbols) {
        String summary = asString(process.get("summary"));
        String processId = asString(process.get("id"));
        Object priority = process.get("priority");
        Object stepCount = process.get("step_count");

        if (summary != null) {
            sb.append("**").append(summary).append("**");
            sb.append("\n");
        }
        sb.append("  priority: ").append(priority != null ? priority : "?");
        sb.append(", steps: ").append(stepCount != null ? stepCount : "?");
        sb.append("\n");

        // 从 process_symbols 中找属于此 process 的步骤
        if (processId != null && !processSymbols.isEmpty()) {
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Map<String, Object> ps : processSymbols) {
                if (processId.equals(asString(ps.get("process_id")))) {
                    steps.add(ps);
                }
            }
            // 按 step_index 排序
            steps.sort((a, b) -> {
                int ai = a.get("step_index") instanceof Number na ? na.intValue() : 99;
                int bi = b.get("step_index") instanceof Number nb ? nb.intValue() : 99;
                return Integer.compare(ai, bi);
            });
            if (!steps.isEmpty()) {
                sb.append("  Steps:\n");
                for (Map<String, Object> step : steps) {
                    String name = asString(step.get("name"));
                    String filePath = asString(step.get("filePath"));
                    Object startLine = step.get("startLine");
                    if (name != null) {
                        sb.append("    - `").append(name).append("`");
                        if (filePath != null) {
                            sb.append(" — ").append(filePath);
                            if (startLine != null) sb.append(":").append(startLine);
                        }
                        sb.append("\n");
                    }
                }
            }
        }
        sb.append("\n");
    }

    private void renderDefinition(StringBuilder sb, Map<String, Object> def) {
        String name = asString(def.get("name"));
        String filePath = asString(def.get("filePath"));
        String id = asString(def.get("id"));
        Object startLine = def.get("startLine");
        Object endLine = def.get("endLine");
        String module = asString(def.get("module"));

        if (name == null) return;

        // 从 id 中提取类型：Method:... / Class:... / File:...
        String kind = "";
        if (id != null) {
            int colon = id.indexOf(':');
            if (colon > 0) kind = id.substring(0, colon).toLowerCase(Locale.ROOT);
        }

        sb.append("- **").append(name).append("**");
        if (!kind.isEmpty()) sb.append(" (").append(kind).append(")");
        if (module != null) sb.append(" [").append(module).append("]");
        sb.append("\n");
        if (filePath != null) {
            sb.append("  `").append(filePath);
            if (startLine != null) sb.append(":").append(startLine);
            if (endLine != null && !endLine.equals(startLine))
                sb.append("-").append(endLine);
            sb.append("`\n");
        }
        sb.append("\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceMap(Object response) {
        if (response == null) return Collections.emptyMap();

        // MCP response: content list
        if (response instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m) {
                Object text = m.get("text");
                if (text instanceof String s && s.trim().startsWith("{")) {
                    try { return mapper.readValue(s, Map.class); } catch (Exception ignored) {}
                }
            }
            return Collections.emptyMap();
        }

        // MCP response: wrapped in "content"
        if (response instanceof Map<?, ?> raw) {
            Object content = raw.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object text = m.get("text");
                    if (text instanceof String s && s.trim().startsWith("{")) {
                        try { return mapper.readValue(s, Map.class); } catch (Exception ignored) {}
                    }
                }
            }
            return castMap(raw);
        }

        // Plain JSON string
        if (response instanceof String s && s.trim().startsWith("{")) {
            try { return mapper.readValue(s, Map.class); } catch (Exception ignored) {}
        }

        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(castMap(m));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Map<String, Object> obj, String... keys) {
        for (String key : keys) {
            Object value = obj.get(key);
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s) result.add(s);
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

    private String asString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    private String resolveServerName() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getGitnexus() != null) {
            String server = appConfig.getRag().getGitnexus().getServerName();
            if (server != null && !server.isBlank()) return server;
        }
        return "gitnexus";
    }

    private String resolveRepo() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getGitnexus() != null) {
            return appConfig.getRag().getGitnexus().getRepo();
        }
        return null;
    }

    private int resolveTopK() {
        if (appConfig != null && appConfig.getRag() != null) {
            int configured = appConfig.getRag().getTopK();
            if (configured > 0) return configured;
        }
        return DEFAULT_TOP_K;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... [truncated]";
    }
}
