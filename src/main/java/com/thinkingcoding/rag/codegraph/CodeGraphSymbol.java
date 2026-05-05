package com.thinkingcoding.rag.codegraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码图符号类，表示一个Java符号（类、接口、枚举、记录）及其相关信息
 */
public final class CodeGraphSymbol {
    private final String name;
    private final String qualifiedName;
    private final String packageName;
    private final Path filePath;
    private final SymbolKind kind;
    private final String declaration;
    private final List<String> publicMembers;
    private final List<String> publicFields;
    private final Map<String, EnumSet<ReferenceKind>> referenceKinds;

    public CodeGraphSymbol(String name,
                           String qualifiedName,
                           String packageName,
                           Path filePath,
                           SymbolKind kind,
                           String declaration,
                           List<String> publicMembers,
                           List<String> publicFields,
                           Map<String, EnumSet<ReferenceKind>> referenceKinds) {
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.packageName = packageName;
        this.filePath = filePath;
        this.kind = kind;
        this.declaration = declaration;
        this.publicMembers = new ArrayList<>(publicMembers);
        this.publicFields = new ArrayList<>(publicFields);
        this.referenceKinds = new LinkedHashMap<>(referenceKinds);
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getPackageName() {
        return packageName;
    }

    public Path getFilePath() {
        return filePath;
    }

    public SymbolKind getKind() {
        return kind;
    }

    public String getDeclaration() {
        return declaration;
    }

    public List<String> getPublicMembers() {
        return Collections.unmodifiableList(publicMembers);
    }

    public List<String> getPublicFields() {
        return Collections.unmodifiableList(publicFields);
    }

    public Map<String, EnumSet<ReferenceKind>> getReferenceKinds() {
        return Collections.unmodifiableMap(referenceKinds);
    }
}


