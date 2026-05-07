package com.thinkingcoding.rag.codegraph;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GitNexusCodeGraphMapper {
    private final ObjectMapper mapper = new ObjectMapper();

    public GitNexusMappingResult map(Path workspaceRoot, String target, Object response) {
        Map<String, Object> payload = coerceMap(response);
        CodeGraphIndex index = new CodeGraphIndex(workspaceRoot);
        if (payload == null || payload.isEmpty()) {
            return new GitNexusMappingResult(index, null);
        }

        Map<String, EnumSet<ReferenceKind>> referenceKinds = new LinkedHashMap<>();
        Map<String, DependencyInfo> dependencies = new LinkedHashMap<>();

        collectRelations(payload, "incoming", referenceKinds, dependencies);
        collectRelations(payload, "outgoing", referenceKinds, dependencies);

        Map<String, Object> symbolMap = asMap(payload.get("symbol"));
        CodeGraphSymbol targetSymbol = buildSymbol(workspaceRoot, target, symbolMap, referenceKinds);
        if (targetSymbol != null) {
            index.addSymbol(targetSymbol);
        }

        for (DependencyInfo info : dependencies.values()) {
            CodeGraphSymbol dependency = buildDependencySymbol(workspaceRoot, info);
            if (dependency != null) {
                index.addSymbol(dependency);
            }
        }

        return new GitNexusMappingResult(index, targetSymbol);
    }

    private void collectRelations(Map<String, Object> payload,
                                  String sectionKey,
                                  Map<String, EnumSet<ReferenceKind>> referenceKinds,
                                  Map<String, DependencyInfo> dependencies) {
        Map<String, Object> section = asMap(payload.get(sectionKey));
        if (section.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : section.entrySet()) {
            ReferenceKind kind = mapRelationKind(entry.getKey());
            if (kind == null) {
                continue;
            }
            List<Object> items = asList(entry.getValue());
            for (Object item : items) {
                DependencyInfo info = parseDependency(item);
                if (info == null || info.getKey() == null) {
                    continue;
                }
                dependencies.putIfAbsent(info.getKey(), info);
                referenceKinds.computeIfAbsent(info.getKey(), key -> EnumSet.noneOf(ReferenceKind.class)).add(kind);
            }
        }
    }

    private DependencyInfo parseDependency(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof String text) {
            return new DependencyInfo(text, text, null, null);
        }
        Map<String, Object> map = asMap(item);
        if (map.isEmpty()) {
            return null;
        }

        String name = firstNonBlank(
                asString(map.get("name")),
                asString(map.get("symbol")),
                extractNameFromUid(asString(map.get("uid"))),
                extractNameFromUid(asString(map.get("id"))),
                asString(map.get("qualifiedName"))
        );
        if (name == null) {
            return null;
        }
        String qualified = firstNonBlank(asString(map.get("qualifiedName")), asString(map.get("uid")), asString(map.get("id")), name);
        String filePath = firstNonBlank(asString(map.get("filePath")), asString(map.get("path")));
        String kind = asString(map.get("kind"));
        return new DependencyInfo(name, qualified, filePath, kind);
    }

    private CodeGraphSymbol buildSymbol(Path workspaceRoot,
                                        String target,
                                        Map<String, Object> symbolMap,
                                        Map<String, EnumSet<ReferenceKind>> referenceKinds) {
        String name = firstNonBlank(
                asString(symbolMap.get("name")),
                extractNameFromUid(asString(symbolMap.get("uid"))),
                extractNameFromUid(asString(symbolMap.get("id"))),
                target
        );
        String qualified = firstNonBlank(asString(symbolMap.get("qualifiedName")), asString(symbolMap.get("uid")), asString(symbolMap.get("id")), name);
        String filePath = firstNonBlank(asString(symbolMap.get("filePath")), asString(symbolMap.get("path")), asString(symbolMap.get("file")));
        Path resolvedPath = resolveFilePath(workspaceRoot, filePath, target);
        String declaration = firstNonBlank(
                asString(symbolMap.get("declaration")),
                asString(symbolMap.get("signature")),
                asString(symbolMap.get("definition"))
        );
        List<String> publicMembers = extractStringList(symbolMap, "publicMembers", "members", "methods");
        List<String> publicFields = extractStringList(symbolMap, "publicFields", "fields");
        SymbolKind kind = mapSymbolKind(asString(symbolMap.get("kind")));

        return new CodeGraphSymbol(
                name != null ? name : "unknown",
                qualified != null ? qualified : (name != null ? name : "unknown"),
                "",
                resolvedPath,
                kind,
                declaration,
                publicMembers,
                publicFields,
                referenceKinds
        );
    }

    private CodeGraphSymbol buildDependencySymbol(Path workspaceRoot, DependencyInfo info) {
        if (info == null || info.getKey() == null) {
            return null;
        }
        Path resolvedPath = resolveFilePath(workspaceRoot, info.filePath, info.name);
        SymbolKind kind = mapSymbolKind(info.kind);
        return new CodeGraphSymbol(
                info.name,
                info.qualifiedName,
                "",
                resolvedPath,
                kind,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap()
        );
    }

    private ReferenceKind mapRelationKind(String relation) {
        if (relation == null) {
            return null;
        }
        String normalized = relation.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "calls" -> ReferenceKind.CALLS;
            case "imports" -> ReferenceKind.IMPORTS;
            case "extends" -> ReferenceKind.EXTENDS;
            case "implements" -> ReferenceKind.IMPLEMENTS;
            default -> null;
        };
    }

    private SymbolKind mapSymbolKind(String rawKind) {
        if (rawKind == null) {
            return SymbolKind.CLASS;
        }
        String normalized = rawKind.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("interface")) {
            return SymbolKind.INTERFACE;
        }
        if (normalized.contains("enum")) {
            return SymbolKind.ENUM;
        }
        if (normalized.contains("record")) {
            return SymbolKind.RECORD;
        }
        return SymbolKind.CLASS;
    }

    private Path resolveFilePath(Path workspaceRoot, String filePath, String target) {
        if (filePath != null && !filePath.isBlank()) {
            return Path.of(filePath).toAbsolutePath().normalize();
        }
        if (target != null && (target.contains("/") || target.contains("\\") || target.endsWith(".java"))) {
            return Path.of(target).toAbsolutePath().normalize();
        }
        return workspaceRoot.toAbsolutePath().normalize();
    }

    private List<String> extractStringList(Map<String, Object> symbolMap, String... keys) {
        for (String key : keys) {
            List<Object> items = asList(symbolMap.get(key));
            if (!items.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof String text) {
                        values.add(text);
                    } else if (item instanceof Map<?, ?> map) {
                        Object name = map.get("name");
                        if (name != null) {
                            values.add(String.valueOf(name));
                        }
                    }
                }
                return values;
            }
        }
        return Collections.emptyList();
    }

    private Map<String, Object> coerceMap(Object response) {
        if (response == null) {
            return Collections.emptyMap();
        }

        // Handle MCP response where callTool directly returns "content" List
        if (response instanceof List<?> contentList && !contentList.isEmpty()) {
            Object first = contentList.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                Object textObj = firstMap.get("text");
                if (textObj instanceof String text && text.trim().startsWith("{")) {
                    try {
                        return mapper.readValue(text, Map.class);
                    } catch (Exception ignored) {
                    }
                }
            }
            return Collections.emptyMap();
        }

        // Unwrap MCP response if it contains "content"
        if (response instanceof Map<?, ?> rawMap) {
            Object contentObj = rawMap.get("content");
            if (contentObj instanceof List<?> contentList && !contentList.isEmpty()) {
                Object first = contentList.get(0);
                if (first instanceof Map<?, ?> firstMap) {
                    Object textObj = firstMap.get("text");
                    if (textObj instanceof String text && text.trim().startsWith("{")) {
                        try {
                            return mapper.readValue(text, Map.class);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return castMap(rawMap);
        }

        if (response instanceof String text && text.trim().startsWith("{")) {
            try {
                return mapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castMap(map);
        }
        return Collections.emptyMap();
    }

    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return Collections.emptyList();
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String extractNameFromUid(String uid) {
        if (uid == null) {
            return null;
        }
        int idx = uid.indexOf(':');
        if (idx >= 0 && idx + 1 < uid.length()) {
            return uid.substring(idx + 1).trim();
        }
        return uid.trim();
    }

    private static final class DependencyInfo {
        private final String name;
        private final String qualifiedName;
        private final String filePath;
        private final String kind;

        private DependencyInfo(String name, String qualifiedName, String filePath, String kind) {
            this.name = name;
            this.qualifiedName = qualifiedName;
            this.filePath = filePath;
            this.kind = kind;
        }

        private String getKey() {
            return qualifiedName != null ? qualifiedName : name;
        }
    }

    public static final class GitNexusMappingResult {
        private final CodeGraphIndex index;
        private final CodeGraphSymbol target;

        public GitNexusMappingResult(CodeGraphIndex index, CodeGraphSymbol target) {
            this.index = index;
            this.target = target;
        }

        public CodeGraphIndex getIndex() {
            return index;
        }

        public CodeGraphSymbol getTarget() {
            return target;
        }
    }
}

