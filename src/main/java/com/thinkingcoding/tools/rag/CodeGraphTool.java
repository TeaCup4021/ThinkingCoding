package com.thinkingcoding.tools.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.rag.codegraph.CodeGraphIndex;
import com.thinkingcoding.rag.codegraph.CodeGraphSymbol;
import com.thinkingcoding.rag.codegraph.ReferenceKind;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.rag.codegraph.GitNexusCodeGraphMapper;
import com.thinkingcoding.rag.codegraph.GitNexusCodeGraphMapper.GitNexusMappingResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CodeGraphTool extends BaseTool {
    private static final long DEFAULT_MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_MAX_DEPENDENCIES = 12;
    private static final int DEFAULT_MAX_MEMBERS = 12;

    private final AppConfig appConfig;
    private final MCPService mcpService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitNexusCodeGraphMapper graphMapper = new GitNexusCodeGraphMapper();

    public CodeGraphTool(AppConfig appConfig, MCPService mcpService) {
        super("code_graph", "Build and query a lightweight code graph via GitNexus MCP");
        this.appConfig = appConfig;
        this.mcpService = mcpService;
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
            String target = stringParam(params, "target", null);
            if (target == null || target.isBlank()) {
                return error("Missing required parameter: target", System.currentTimeMillis() - startTime);
            }

            String workspace = stringParam(params, "workspace", resolveWorkspace());
            boolean refresh = booleanParam(params, "refresh", false);
            boolean includeTests = booleanParam(params, "includeTests", false);
            int maxDependencies = intParam(params, "maxDependencies", DEFAULT_MAX_DEPENDENCIES);
            int maxMembers = intParam(params, "maxMembers", DEFAULT_MAX_MEMBERS);
            String repo = stringParam(params, "repo", resolveGitNexusRepo());

            Object response = callGitNexus(target, repo, includeTests, refresh);
            GitNexusMappingResult mapping = graphMapper.map(Path.of(workspace), target, response);
            CodeGraphSymbol targetSymbol = mapping.getTarget();
            if (targetSymbol == null) {
                return error("Target not found in GitNexus response: " + target,
                        System.currentTimeMillis() - startTime);
            }

            String output = render(targetSymbol, mapping.getIndex(), maxDependencies, maxMembers);
            return success(output, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("Code graph lookup failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "rag";
    }

    @Override
    public boolean isEnabled() {
        return appConfig == null || appConfig.getTools().getCodeGraph().isEnabled();
    }

    @Override
    public Object getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Query the code graph via GitNexus MCP for a target symbol");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("target", field("string", "Target symbol name or file path"));
        properties.put("workspace", field("string", "Workspace root; defaults to rag.workspace or user.dir"));
        properties.put("repo", field("string", "GitNexus repo name; defaults to rag.gitnexus.repo"));
        properties.put("refresh", field("boolean", "Request GitNexus to refresh the index if supported"));
        properties.put("includeTests", field("boolean", "Include tests in GitNexus queries if supported"));
        properties.put("maxDependencies", field("integer", "Maximum dependency symbols to return"));
        properties.put("maxMembers", field("integer", "Maximum public members per symbol"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"target"});
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> field(String type, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("description", description);
        return field;
    }

    private Map<String, Object> parseParams(String input) throws Exception {
        if (input != null && input.trim().startsWith("{")) {
            return mapper.readValue(input, Map.class);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("target", input);
        return fallback;
    }

    private Object callGitNexus(String target, String repo, boolean includeTests, boolean refresh) {
        String serverName = resolveGitNexusServer();
        String toolName = resolveGitNexusTool();

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", target);
        arguments.put("target", target);
        if (repo != null && !repo.isBlank()) {
            arguments.put("repo", repo);
        }
        if (includeTests) {
            arguments.put("includeTests", true);
        }
        if (refresh) {
            arguments.put("refresh", true);
        }

        return mcpService.callTool(serverName, toolName, arguments);
    }

    private String resolveGitNexusServer() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getGitnexus() != null) {
            String server = appConfig.getRag().getGitnexus().getServerName();
            if (server != null && !server.isBlank()) {
                return server;
            }
        }
        return "gitnexus";
    }

    private String resolveGitNexusTool() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getGitnexus() != null) {
            String tool = appConfig.getRag().getGitnexus().getContextTool();
            if (tool != null && !tool.isBlank()) {
                return tool;
            }
        }
        return "context";
    }

    private String resolveGitNexusRepo() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getGitnexus() != null) {
            return appConfig.getRag().getGitnexus().getRepo();
        }
        return null;
    }

    private String resolveWorkspace() {
        if (appConfig != null && appConfig.getRag() != null && appConfig.getRag().getWorkspace() != null) {
            return appConfig.getRag().getWorkspace();
        }
        return System.getProperty("user.dir");
    }

    private String render(CodeGraphSymbol target,
                          CodeGraphIndex index,
                          int maxDependencies,
                          int maxMembers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Target: ").append(target.getQualifiedName()).append(" (" + target.getKind() + ")\n");
        sb.append("File: ").append(target.getFilePath()).append("\n");
        if (target.getDeclaration() != null && !target.getDeclaration().isBlank()) {
            sb.append("Declaration: ").append(target.getDeclaration()).append("\n");
        }

        appendMembers(sb, "Public fields", target.getPublicFields(), maxMembers);
        appendMembers(sb, "Public members", target.getPublicMembers(), maxMembers);

        List<CodeGraphSymbol> dependencies = index.resolveDependencies(target, maxDependencies);
        sb.append("Dependencies (" + dependencies.size() + "):" + "\n");
        for (CodeGraphSymbol dep : dependencies) {
            EnumSet<ReferenceKind> kinds = target.getReferenceKinds().get(dep.getQualifiedName());
            if (kinds == null) {
                kinds = target.getReferenceKinds().get(dep.getName());
            }
            sb.append("- ").append(dep.getQualifiedName());
            sb.append(" [").append(formatKinds(kinds)).append("]\n");
            appendMembers(sb, "  members", dep.getPublicMembers(), Math.max(3, maxMembers / 2));
        }

        List<String> unresolved = index.unresolvedReferences(target, 8);
        if (!unresolved.isEmpty()) {
            sb.append("Unresolved references: ").append(String.join(", ", unresolved)).append("\n");
        }
        sb.append("Index symbols: ").append(index.getSymbolCount());
        return sb.toString();
    }

    private void appendMembers(StringBuilder sb, String label, List<String> members, int limit) {
        if (members == null || members.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        int count = 0;
        for (String member : members) {
            if (count >= limit) {
                sb.append("  ...").append("\n");
                break;
            }
            sb.append("  - ").append(member).append("\n");
            count++;
        }
    }

    private String formatKinds(EnumSet<ReferenceKind> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return "unknown";
        }
        List<String> names = new ArrayList<>();
        for (ReferenceKind kind : kinds) {
            names.add(kind.name());
        }
        return String.join(", ", names);
    }

    private String stringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private boolean booleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
