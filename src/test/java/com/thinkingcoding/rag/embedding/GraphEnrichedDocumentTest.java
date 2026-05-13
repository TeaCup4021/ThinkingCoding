package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.rag.codegraph.CodeGraphSymbol;
import com.thinkingcoding.rag.codegraph.ReferenceKind;
import com.thinkingcoding.rag.codegraph.SymbolKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphEnrichedDocumentTest {

    @Test
    void shouldBuildDocumentWithAllFields() {
        Map<String, EnumSet<ReferenceKind>> refs = new LinkedHashMap<>();
        refs.put("com.example.UserRepository", EnumSet.of(ReferenceKind.CALLS));
        refs.put("com.example.PasswordEncoder", EnumSet.of(ReferenceKind.CALLS));
        refs.put("com.example.UserDto", EnumSet.of(ReferenceKind.IMPORTS));

        CodeGraphSymbol symbol = new CodeGraphSymbol(
                "UserService",
                "com.example.UserService",
                "com.example",
                Path.of("src/main/java/com/example/UserService.java"),
                SymbolKind.CLASS,
                "public class UserService",
                List.of("getUserById", "createUser", "deleteUser"),
                List.of("logger", "MAX_RETRY"),
                refs
        );

        String doc = GraphEnrichedDocument.build(symbol);

        assertTrue(doc.contains("[TYPE] CLASS"));
        assertTrue(doc.contains("[NAME] com.example.UserService"));
        assertTrue(doc.contains("[FILE]"));
        assertTrue(doc.contains("[CALLS] com.example.UserRepository"));
        assertTrue(doc.contains("[CALLS] com.example.PasswordEncoder"));
        assertTrue(doc.contains("[IMPORTS] com.example.UserDto"));
        assertTrue(doc.contains("[MEMBER] getUserById"));
        assertTrue(doc.contains("[MEMBER] createUser"));
        assertTrue(doc.contains("[SIGNATURE] public class UserService"));
    }

    @Test
    void shouldHandleMinimalSymbol() {
        CodeGraphSymbol symbol = new CodeGraphSymbol(
                "EmptyClass",
                "com.example.EmptyClass",
                "",
                Path.of("src/main/java/com/example/EmptyClass.java"),
                SymbolKind.INTERFACE,
                null,
                List.of(),
                List.of(),
                Map.of()
        );

        String doc = GraphEnrichedDocument.build(symbol);

        assertTrue(doc.contains("[TYPE] INTERFACE"));
        assertTrue(doc.contains("[NAME] com.example.EmptyClass"));
        assertFalse(doc.contains("[MEMBER]"));
        assertFalse(doc.contains("[SIGNATURE]"));
    }

    @Test
    void shouldHandleEmptyReferenceKinds() {
        CodeGraphSymbol symbol = new CodeGraphSymbol(
                "SoloClass", "com.example.SoloClass", "",
                Path.of("src/main/SoloClass.java"),
                SymbolKind.RECORD, null, List.of("value"), List.of(), Map.of()
        );

        String doc = GraphEnrichedDocument.build(symbol);
        assertTrue(doc.contains("[MEMBER] value"));
    }
}
