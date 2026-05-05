package com.thinkingcoding.rag.codegraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 代码图索引类，维护一个代码图符号的索引，支持根据符号名称或文件路径查找符号，以及解析符号的依赖关系
 */
public final class CodeGraphIndex {
    private final Path workspaceRoot;
    private final Map<String, CodeGraphSymbol> symbolsByQualifiedName = new LinkedHashMap<>();
    private final Map<String, List<CodeGraphSymbol>> symbolsBySimpleName = new LinkedHashMap<>();

    public CodeGraphIndex(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void addSymbol(CodeGraphSymbol symbol) {
        if (symbol == null) {
            return;
        }
        symbolsByQualifiedName.put(symbol.getQualifiedName(), symbol);
        symbolsBySimpleName.computeIfAbsent(symbol.getName(), key -> new ArrayList<>()).add(symbol);
    }

    public int getSymbolCount() {
        return symbolsByQualifiedName.size();
    }

    public Optional<CodeGraphSymbol> findTarget(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String normalized = target.trim();
        if (normalized.endsWith(".java")) {
            Path targetPath = Path.of(normalized).toAbsolutePath().normalize();
            for (CodeGraphSymbol symbol : symbolsByQualifiedName.values()) {
                if (targetPath.equals(symbol.getFilePath())) {
                    return Optional.of(symbol);
                }
            }
            return Optional.empty();
        }
        if (normalized.contains(".")) {
            CodeGraphSymbol symbol = symbolsByQualifiedName.get(normalized);
            return Optional.ofNullable(symbol);
        }
        List<CodeGraphSymbol> symbols = symbolsBySimpleName.get(normalized);
        if (symbols == null || symbols.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(symbols.get(0));
    }

    public List<CodeGraphSymbol> resolveDependencies(CodeGraphSymbol target, int maxDependencies) {
        if (target == null) {
            return Collections.emptyList();
        }
        Map<String, CodeGraphSymbol> resolved = new LinkedHashMap<>();
        for (String typeName : target.getReferenceKinds().keySet()) {
            if (resolved.size() >= maxDependencies) {
                break;
            }
            CodeGraphSymbol symbol = resolveSymbol(typeName, target.getPackageName());
            if (symbol != null && !symbol.getQualifiedName().equals(target.getQualifiedName())) {
                resolved.putIfAbsent(symbol.getQualifiedName(), symbol);
            }
        }
        return new ArrayList<>(resolved.values());
    }

    public List<String> unresolvedReferences(CodeGraphSymbol target, int maxItems) {
        if (target == null) {
            return Collections.emptyList();
        }
        List<String> unresolved = new ArrayList<>();
        for (String typeName : target.getReferenceKinds().keySet()) {
            if (unresolved.size() >= maxItems) {
                break;
            }
            CodeGraphSymbol symbol = resolveSymbol(typeName, target.getPackageName());
            if (symbol == null) {
                unresolved.add(typeName);
            }
        }
        return unresolved;
    }

    private CodeGraphSymbol resolveSymbol(String typeName, String packageName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        if (typeName.contains(".")) {
            return symbolsByQualifiedName.get(typeName);
        }
        List<CodeGraphSymbol> candidates = symbolsBySimpleName.get(typeName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (packageName != null && !packageName.isBlank()) {
            for (CodeGraphSymbol symbol : candidates) {
                if (packageName.equals(symbol.getPackageName())) {
                    return symbol;
                }
            }
        }
        return candidates.get(0);
    }
}

