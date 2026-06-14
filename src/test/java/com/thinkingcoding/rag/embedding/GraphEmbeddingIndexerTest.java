package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.mcp.MCPService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class GraphEmbeddingIndexerTest {

    private GraphEmbeddingIndexer indexer;
    private MCPService mockMcp;
    private EmbeddingService mockEmbedding;
    private GraphEmbeddingStore mockStore;

    @BeforeEach
    public void setUp() {
        mockMcp = mock(MCPService.class);
        mockEmbedding = mock(EmbeddingService.class);
        mockStore = mock(GraphEmbeddingStore.class);

        indexer = new GraphEmbeddingIndexer(
                mockMcp, "gitnexus", "thinkingcoding",
                mockEmbedding, mockStore,
                Path.of(".")
        );
    }

    @Test
    public void testExtractTextFromContentList() {
        // MCP 返回 List<{type: "text", text: "..."}>
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", "Changes: 3 files, 5 symbols")
        );
        String result = indexer.extractTextContent(content);
        assertEquals("Changes: 3 files, 5 symbols", result);
    }

    @Test
    public void testExtractTextFromWrappedContent() {
        // MCP 返回 Map{content: [{type: "text", text: "..."}]}
        Map<String, Object> wrapped = Map.of(
                "content", List.of(Map.of("type", "text", "text", "hello"))
        );
        String result = indexer.extractTextContent(wrapped);
        assertEquals("hello", result);
    }

    @Test
    public void testExtractTextFromPlainString() {
        String result = indexer.extractTextContent("plain text");
        assertEquals("plain text", result);
    }

    @Test
    public void testExtractTextFromNull() {
        assertNull(indexer.extractTextContent(null));
    }

    @Test
    public void testExtractTextFromEmptyList() {
        assertNull(indexer.extractTextContent(List.of()));
    }

    @Test
    public void testDetectChangedSymbolsParsing() {
        String text = "Changes: 2 files, 3 symbols\n"
                + "Affected processes: 1\n"
                + "Risk level: medium\n"
                + "\n"
                + "Changed symbols:\n"
                + "  undefined ConfigManager → src/main/java/com/thinkingcoding/config/AppConfig.java\n"
                + "  undefined ThinkingCodingContext → src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java\n"
                + "  undefined execute → src/main/java/com/thinkingcoding/tools/rag/CodeGraphTool.java\n"
                + "\n"
                + "Affected execution flows:\n"
                + "  • Main → AppConfig (6 steps)\n";

        List<Map<String, Object>> content = List.of(Map.of("type", "text", "text", text));
        when(mockMcp.callTool(eq("gitnexus"), eq("detect_changes"), anyMap()))
                .thenReturn(content);

        Map<String, Set<String>> result = indexer.detectChangedSymbols("old-hash");

        assertEquals(3, result.size());
        assertTrue(result.containsKey("src/main/java/com/thinkingcoding/config/AppConfig.java"));
        assertTrue(result.containsKey("src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java"));
        assertTrue(result.containsKey("src/main/java/com/thinkingcoding/tools/rag/CodeGraphTool.java"));

        Set<String> appConfigSymbols = result.get("src/main/java/com/thinkingcoding/config/AppConfig.java");
        assertTrue(appConfigSymbols.contains("ConfigManager"));

        Set<String> codeGraphSymbols = result.get("src/main/java/com/thinkingcoding/tools/rag/CodeGraphTool.java");
        assertTrue(codeGraphSymbols.contains("execute"));
    }

    @Test
    public void testDetectChangedSymbolsWithNoChanges() {
        String text = "Changes: 0 files, 0 symbols\nNo changes detected.";
        List<Map<String, Object>> content = List.of(Map.of("type", "text", "text", text));
        when(mockMcp.callTool(eq("gitnexus"), eq("detect_changes"), anyMap()))
                .thenReturn(content);

        Map<String, Set<String>> result = indexer.detectChangedSymbols("hash");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testDetectChangedSymbolsWhenMcpFails() {
        when(mockMcp.callTool(eq("gitnexus"), eq("detect_changes"), anyMap()))
                .thenThrow(new RuntimeException("MCP error"));

        Map<String, Set<String>> result = indexer.detectChangedSymbols("hash");
        // 应优雅降级返回空
        assertTrue(result.isEmpty());
    }

    @Test
    public void testIncrementalIndexNoChanges() throws Exception {
        String text = "Changes: 0 files, 0 symbols\nNo changes detected.";
        List<Map<String, Object>> content = List.of(Map.of("type", "text", "text", text));
        when(mockMcp.callTool(eq("gitnexus"), eq("detect_changes"), anyMap()))
                .thenReturn(content);

        GraphEmbeddingIndexer.IndexResult result = indexer.incrementalIndex("old", "new");
        assertEquals(0, result.indexed());
        assertEquals(0, result.skipped());
        // 不应调 delete 或 embed
        verify(mockStore, never()).deleteByFilePaths(any());
        verify(mockEmbedding, never()).embedBatch(any());
    }

    @Test
    public void testIncrementalIndexWithChanges() throws Exception {
        String text = "Changes: 1 files, 1 symbols\n"
                + "Changed symbols:\n"
                + "  undefined ConfigManager → src/main/java/com/thinkingcoding/config/AppConfig.java\n";
        List<Map<String, Object>> content = List.of(Map.of("type", "text", "text", text));
        when(mockMcp.callTool(eq("gitnexus"), eq("detect_changes"), anyMap()))
                .thenReturn(content);

        // Mock context 返回：符号存在
        Map<String, Object> contextResponse = buildMockContextResponse(
                "ConfigManager", "src/main/java/com/thinkingcoding/config/AppConfig.java", "Class");
        when(mockMcp.callTool(eq("gitnexus"), eq("context"), anyMap()))
                .thenReturn(contextResponse);

        // Mock embedding
        float[] vector = new float[]{0.1f, 0.2f};
        when(mockEmbedding.embedBatch(anyList())).thenReturn(new float[][]{vector});

        GraphEmbeddingIndexer.IndexResult result = indexer.incrementalIndex("old-hash", "new-hash");

        verify(mockStore, times(1)).deleteByFilePaths(
                eq(Set.of("src/main/java/com/thinkingcoding/config/AppConfig.java")));
        assertTrue(result.indexed() > 0);
    }

    private Map<String, Object> buildMockContextResponse(String name, String filePath, String kind) {
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put("uid", kind + ":src/main/java/com/thinkingcoding/" + name + ".java:" + name);
        symbol.put("name", name);
        symbol.put("kind", kind);
        symbol.put("filePath", filePath);
        symbol.put("startLine", 1);
        symbol.put("endLine", 50);

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("calls", List.of());
        incoming.put("imports", List.of());

        Map<String, Object> outgoing = new LinkedHashMap<>();
        outgoing.put("has_method", List.of());
        outgoing.put("has_property", List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "found");
        response.put("symbol", symbol);
        response.put("incoming", incoming);
        response.put("outgoing", outgoing);
        response.put("processes", List.of());

        // MCP content wrapper
        return Map.of("content", List.of(Map.of("type", "text", "text",
                toJson(response))));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
