package com.thinkingcoding.rag.codegraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class JavaAstIndexer {
    private static final Set<String> EXCLUDED_PACKAGES = new HashSet<>(Arrays.asList(
            "java.", "javax.", "jakarta.", "org.junit.", "org.mockito.", "lombok.", "org.slf4j."
    ));

    private static final Set<String> JAVA_LANG_SIMPLE = new HashSet<>(Arrays.asList(
            "String", "Object", "Class", "Integer", "Long", "Double", "Float", "Boolean", "Short",
            "Byte", "Character", "Void", "Enum", "Throwable", "Exception", "RuntimeException"
    ));

    private final JavaParser parser;

    public JavaAstIndexer() {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(configuration);
    }

    public CodeGraphIndex indexWorkspace(Path workspaceRoot, IndexOptions options) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        CodeGraphIndex index = new CodeGraphIndex(root);
        List<Path> roots = resolveRoots(root, options);

        for (Path sourceRoot : roots) {
            if (!Files.exists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Files.isRegularFile(path))
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> parseFile(path, index, options));
            } catch (IOException ignored) {
                // Skip unreadable roots.
            }
        }
        return index;
    }

    private List<Path> resolveRoots(Path workspaceRoot, IndexOptions options) {
        List<Path> roots = new ArrayList<>();
        Path mainRoot = workspaceRoot.resolve("src/main/java");
        if (Files.exists(mainRoot)) {
            roots.add(mainRoot);
        } else {
            roots.add(workspaceRoot);
        }
        if (options.isIncludeTests()) {
            Path testRoot = workspaceRoot.resolve("src/test/java");
            if (Files.exists(testRoot)) {
                roots.add(testRoot);
            }
        }
        return roots;
    }

    private void parseFile(Path path, CodeGraphIndex index, IndexOptions options) {
        try {
            if (Files.size(path) > options.getMaxFileSizeBytes()) {
                return;
            }
            var result = parser.parse(path);
            if (result.getResult().isEmpty()) {
                return;
            }
            CompilationUnit unit = result.getResult().get();
            String packageName = unit.getPackageDeclaration()
                    .map(decl -> decl.getNameAsString())
                    .orElse("");
            ImportContext imports = buildImportContext(unit.getImports(), packageName);

            for (var type : unit.getTypes()) {
                if (type instanceof ClassOrInterfaceDeclaration declaration) {
                    index.addSymbol(buildFromClass(declaration, packageName, path, imports));
                } else if (type instanceof EnumDeclaration declaration) {
                    index.addSymbol(buildFromEnum(declaration, packageName, path));
                } else if (type instanceof RecordDeclaration declaration) {
                    index.addSymbol(buildFromRecord(declaration, packageName, path, imports));
                }
            }
        } catch (Exception ignored) {
            // Skip files with parsing errors.
        }
    }

    private CodeGraphSymbol buildFromClass(ClassOrInterfaceDeclaration declaration,
                                           String packageName,
                                           Path path,
                                           ImportContext imports) {
        SymbolKind kind = declaration.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
        String name = declaration.getNameAsString();
        String qualifiedName = qualify(packageName, name);
        String declarationLine = formatClassDeclaration(declaration);
        List<String> publicMembers = new ArrayList<>();
        List<String> publicFields = new ArrayList<>();
        Map<String, EnumSet<ReferenceKind>> references = new LinkedHashMap<>();

        declaration.getExtendedTypes().forEach(type -> addReference(type, imports, references, ReferenceKind.EXTENDS));
        declaration.getImplementedTypes().forEach(type -> addReference(type, imports, references, ReferenceKind.IMPLEMENTS));

        for (FieldDeclaration field : declaration.getFields()) {
            if (!field.isPublic() && !field.isProtected()) {
                continue;
            }
            String fieldType = field.getElementType().toString();
            field.getVariables().forEach(var -> publicFields.add(fieldType + " " + var.getNameAsString()));
            addReference(field.getElementType(), imports, references, ReferenceKind.FIELD);
        }

        for (ConstructorDeclaration ctor : declaration.getConstructors()) {
            if (!ctor.isPublic() && !ctor.isProtected()) {
                continue;
            }
            publicMembers.add(ctor.getDeclarationAsString(true, false, false));
            ctor.getParameters().forEach(param -> addReference(param.getType(), imports, references, ReferenceKind.PARAMETER));
            ctor.getThrownExceptions().forEach(type -> addReference(type, imports, references, ReferenceKind.THROWS));
        }

        for (MethodDeclaration method : declaration.getMethods()) {
            if (!method.isPublic() && !method.isProtected()) {
                continue;
            }
            publicMembers.add(method.getDeclarationAsString(true, false, false));
            addReference(method.getType(), imports, references, ReferenceKind.RETURN);
            method.getParameters().forEach(param -> addReference(param.getType(), imports, references, ReferenceKind.PARAMETER));
            method.getThrownExceptions().forEach(type -> addReference(type, imports, references, ReferenceKind.THROWS));
        }

        return new CodeGraphSymbol(name, qualifiedName, packageName, path.toAbsolutePath().normalize(),
                kind, declarationLine, publicMembers, publicFields, references);
    }

    private CodeGraphSymbol buildFromEnum(EnumDeclaration declaration, String packageName, Path path) {
        String name = declaration.getNameAsString();
        String qualifiedName = qualify(packageName, name);
        String declarationLine = formatEnumDeclaration(declaration);
        return new CodeGraphSymbol(name, qualifiedName, packageName, path.toAbsolutePath().normalize(),
                SymbolKind.ENUM, declarationLine, List.of(), List.of(), Map.of());
    }

    private CodeGraphSymbol buildFromRecord(RecordDeclaration declaration,
                                            String packageName,
                                            Path path,
                                            ImportContext imports) {
        String name = declaration.getNameAsString();
        String qualifiedName = qualify(packageName, name);
        String declarationLine = formatRecordDeclaration(declaration);
        Map<String, EnumSet<ReferenceKind>> references = new LinkedHashMap<>();
        declaration.getParameters().forEach(param -> addReference(param.getType(), imports, references, ReferenceKind.PARAMETER));
        return new CodeGraphSymbol(name, qualifiedName, packageName, path.toAbsolutePath().normalize(),
                SymbolKind.RECORD, declarationLine, List.of(), List.of(), references);
    }

    private void addReference(Type type, ImportContext imports,
                              Map<String, EnumSet<ReferenceKind>> references,
                              ReferenceKind kind) {
        for (String rawName : extractTypeNames(type)) {
            String resolved = resolveQualifiedType(rawName, imports);
            if (resolved == null || resolved.isBlank()) {
                continue;
            }
            if (isExcluded(resolved)) {
                continue;
            }
            references.computeIfAbsent(resolved, key -> EnumSet.noneOf(ReferenceKind.class)).add(kind);
        }
    }

    private List<String> extractTypeNames(Type type) {
        List<String> names = new ArrayList<>();
        if (type == null) {
            return names;
        }
        if (type.isPrimitiveType() || type.isVoidType() || type.isVarType()) {
            return names;
        }
        if (type.isArrayType()) {
            names.addAll(extractTypeNames(type.asArrayType().getComponentType()));
            return names;
        }
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            names.add(classType.getNameWithScope());
            classType.getTypeArguments().ifPresent(args -> args.forEach(arg -> names.addAll(extractTypeNames(arg))));
            return names;
        }
        if (type.isWildcardType()) {
            type.asWildcardType().getExtendedType().ifPresent(ext -> names.addAll(extractTypeNames(ext)));
            type.asWildcardType().getSuperType().ifPresent(ext -> names.addAll(extractTypeNames(ext)));
            return names;
        }
        if (type.isIntersectionType()) {
            type.asIntersectionType().getElements().forEach(elem -> names.addAll(extractTypeNames(elem)));
            return names;
        }
        if (type.isUnionType()) {
            type.asUnionType().getElements().forEach(elem -> names.addAll(extractTypeNames(elem)));
            return names;
        }
        return names;
    }

    private String resolveQualifiedType(String name, ImportContext imports) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.contains(".")) {
            return name;
        }
        if (imports.explicitImports.containsKey(name)) {
            return imports.explicitImports.get(name);
        }
        for (String wildcard : imports.wildcardImports) {
            return wildcard + "." + name;
        }
        if (!imports.packageName.isBlank()) {
            return imports.packageName + "." + name;
        }
        if (JAVA_LANG_SIMPLE.contains(name)) {
            return "java.lang." + name;
        }
        return name;
    }

    private ImportContext buildImportContext(List<ImportDeclaration> imports, String packageName) {
        Map<String, String> explicit = new HashMap<>();
        List<String> wildcards = new ArrayList<>();
        for (ImportDeclaration declaration : imports) {
            if (declaration.isStatic()) {
                continue;
            }
            String name = declaration.getNameAsString();
            if (declaration.isAsterisk()) {
                wildcards.add(name);
            } else {
                String simple = name.substring(name.lastIndexOf('.') + 1);
                explicit.put(simple, name);
            }
        }
        return new ImportContext(packageName, explicit, wildcards);
    }

    private String qualify(String packageName, String name) {
        if (packageName == null || packageName.isBlank()) {
            return name;
        }
        return packageName + "." + name;
    }

    private boolean isExcluded(String qualifiedName) {
        for (String prefix : EXCLUDED_PACKAGES) {
            if (qualifiedName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String formatClassDeclaration(ClassOrInterfaceDeclaration declaration) {
        StringBuilder sb = new StringBuilder();
        appendVisibility(sb, declaration.isPublic(), declaration.isProtected(), declaration.isPrivate());
        if (declaration.isAbstract() && !declaration.isInterface()) {
            sb.append("abstract ");
        }
        if (declaration.isFinal()) {
            sb.append("final ");
        }
        sb.append(declaration.isInterface() ? "interface " : "class ");
        sb.append(declaration.getNameAsString());
        if (!declaration.getExtendedTypes().isEmpty()) {
            sb.append(" extends ").append(joinTypes(declaration.getExtendedTypes()));
        }
        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ").append(joinTypes(declaration.getImplementedTypes()));
        }
        return sb.toString().trim();
    }

    private String formatEnumDeclaration(EnumDeclaration declaration) {
        StringBuilder sb = new StringBuilder();
        appendVisibility(sb, declaration.isPublic(), declaration.isProtected(), declaration.isPrivate());
        sb.append("enum ").append(declaration.getNameAsString());
        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ").append(joinTypes(declaration.getImplementedTypes()));
        }
        return sb.toString().trim();
    }

    private String formatRecordDeclaration(RecordDeclaration declaration) {
        StringBuilder sb = new StringBuilder();
        appendVisibility(sb, declaration.isPublic(), declaration.isProtected(), declaration.isPrivate());
        sb.append("record ").append(declaration.getNameAsString());
        sb.append("(");
        List<String> components = new ArrayList<>();
        declaration.getParameters().forEach(param -> components.add(param.getType() + " " + param.getNameAsString()));
        sb.append(String.join(", ", components));
        sb.append(")");
        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ").append(joinTypes(declaration.getImplementedTypes()));
        }
        return sb.toString().trim();
    }

    private void appendVisibility(StringBuilder sb, boolean isPublic, boolean isProtected, boolean isPrivate) {
        if (isPublic) {
            sb.append("public ");
        } else if (isProtected) {
            sb.append("protected ");
        } else if (isPrivate) {
            sb.append("private ");
        }
    }

    private String joinTypes(List<? extends Type> types) {
        List<String> names = new ArrayList<>();
        for (Type type : types) {
            names.add(type.toString());
        }
        return String.join(", ", names);
    }

    private static final class ImportContext {
        private final String packageName;
        private final Map<String, String> explicitImports;
        private final List<String> wildcardImports;

        private ImportContext(String packageName, Map<String, String> explicitImports, List<String> wildcardImports) {
            this.packageName = packageName == null ? "" : packageName;
            this.explicitImports = explicitImports;
            this.wildcardImports = wildcardImports;
        }
    }
}
