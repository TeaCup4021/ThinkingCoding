package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.rag.codegraph.CodeGraphSymbol;
import com.thinkingcoding.rag.codegraph.ReferenceKind;

import java.util.EnumSet;
import java.util.Map;

/**
 * 将 CodeGraphSymbol 的图谱关系拼接为结构化嵌入文本。
 */
public final class GraphEnrichedDocument {

    private GraphEnrichedDocument() {}

    public static String build(CodeGraphSymbol symbol) {
        StringBuilder sb = new StringBuilder();

        sb.append("[TYPE] ").append(symbol.getKind()).append("\n");
        sb.append("[NAME] ").append(symbol.getQualifiedName()).append("\n");
        sb.append("[FILE] ").append(symbol.getFilePath()).append("\n");

        Map<String, EnumSet<ReferenceKind>> refs = symbol.getReferenceKinds();
        if (refs != null && !refs.isEmpty()) {
            for (var entry : refs.entrySet()) {
                String target = entry.getKey();
                for (ReferenceKind kind : entry.getValue()) {
                    sb.append("[").append(kind.name()).append("] ")
                      .append(target).append("\n");
                }
            }
        }

        if (symbol.getPublicMembers() != null) {
            for (String member : symbol.getPublicMembers()) {
                sb.append("[MEMBER] ").append(member).append("\n");
            }
        }

        if (symbol.getDeclaration() != null && !symbol.getDeclaration().isBlank()) {
            sb.append("[SIGNATURE] ").append(symbol.getDeclaration()).append("\n");
        }

        return sb.toString();
    }
}