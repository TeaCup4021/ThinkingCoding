package com.thinkingcoding.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.GitNexusStalenessChecker;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.rag.SemanticSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SemanticSearchTool 单元测试。
 * Mock MCPService，验证参数解析和响应格式化。
 */
public class SemanticSearchToolTest {

    private SemanticSearchTool tool;
    private MCPService mockMcp;
    private AppConfig mockConfig;
    private GitNexusStalenessChecker mockStalenessChecker;

    @BeforeEach
    public void setUp() {
        mockMcp = mock(MCPService.class);
        mockConfig = mock(AppConfig.class);
        mockStalenessChecker = mock(GitNexusStalenessChecker.class);

        when(mockStalenessChecker.ensureFresh())
                .thenReturn(GitNexusStalenessChecker.StalenessResult.FRESH);

        // 配置 mock config
        AppConfig.RagConfig ragConfig = new AppConfig.RagConfig();
        ragConfig.setTopK(3);
        when(mockConfig.getRag()).thenReturn(ragConfig);

        AppConfig.ToolsConfig toolsConfig = new AppConfig.ToolsConfig();
        when(mockConfig.getTools()).thenReturn(toolsConfig);

        tool = new SemanticSearchTool(mockConfig, mockMcp, mockStalenessChecker);
    }

    @Test
    public void testEnabled() {
        assertTrue(tool.isEnabled());
        assertEquals("semantic_search", tool.getName());
        assertEquals("rag", tool.getCategory());
    }

    @Test
    public void testMissingQuery() {
        ToolResult result = tool.execute("");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Missing required parameter: query"));
    }

    @Test
    public void testNullMcpService() {
        tool = new SemanticSearchTool(mockConfig, null, mockStalenessChecker);
        ToolResult result = tool.execute("find auth");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("MCP service is not available"));
    }

    @Test
    public void testSearchWithProcesses() throws Exception {
        // 模拟 GitNexus query 真实返回格式
        Map<String, Object> mockResponse = new LinkedHashMap<>();
        mockResponse.put("processes", List.of(
            Map.of("id", "proc_43_streamingchat",
                   "summary", "StreamingChat → IsEmpty",
                   "priority", 0.1,
                   "symbol_count", 2,
                   "step_count", 5)
        ));
        mockResponse.put("process_symbols", List.of(
            Map.of("name", "buildProjectContextMessage",
                   "filePath", "src/main/java/com/thinkingcoding/service/ContextManager.java",
                   "startLine", 170, "endLine", 370,
                   "process_id", "proc_43_streamingchat",
                   "step_index", 4),
            Map.of("name", "prepareMessages",
                   "filePath", "src/main/java/com/thinkingcoding/service/LangChainService.java",
                   "startLine", 455, "endLine", 478,
                   "process_id", "proc_43_streamingchat",
                   "step_index", 2)
        ));
        mockResponse.put("definitions", List.of(
            Map.of("id", "Class:src/main/java/com/thinkingcoding/service/ContextManager.java:ContextManager",
                   "name", "ContextManager",
                   "filePath", "src/main/java/com/thinkingcoding/service/ContextManager.java",
                   "startLine", 26, "endLine", 734,
                   "module", "Service"),
            Map.of("id", "Method:src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java:getContextManager",
                   "name", "getContextManager",
                   "filePath", "src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java",
                   "startLine", 336, "endLine", 336,
                   "module", "Config")
        ));

        when(mockMcp.callTool(eq("gitnexus"), eq("query"), anyMap()))
            .thenReturn(mockResponse);

        ToolResult result = tool.execute("{\"query\": \"authentication\"}");

        assertTrue(result.isSuccess());
        String output = result.getOutput();
        assertTrue(output.contains("authentication"), "Should contain query");
        assertTrue(output.contains("ContextManager"), "Should contain ContextManager definition");
        assertTrue(output.contains("buildProjectContextMessage"), "Should contain process step");
        assertTrue(output.contains("prepareMessages"), "Should contain process step");
        assertTrue(output.contains("StreamingChat"), "Should contain process summary");
        assertTrue(output.contains("code_graph"), "Should hint at code_graph");
    }

    @Test
    public void testSearchWithEmptyResponse() {
        when(mockMcp.callTool(eq("gitnexus"), eq("query"), anyMap()))
            .thenReturn(Collections.emptyMap());

        ToolResult result = tool.execute("{\"query\": \"nonexistent thing\"}");

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No results found"));
    }

    @Test
    public void testSearchWithPlainQueryString() {
        when(mockMcp.callTool(eq("gitnexus"), eq("query"), anyMap()))
            .thenReturn(Collections.emptyMap());

        ToolResult result = tool.execute("find database connection");

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("find database connection"));
    }

    @Test
    public void testInputSchema() {
        Object schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaMap = (Map<String, Object>) schema;
        assertEquals("object", schemaMap.get("type"));
    }

    @Test
    public void testStalenessFailure() {
        when(mockStalenessChecker.ensureFresh())
                .thenReturn(GitNexusStalenessChecker.StalenessResult
                        .failed("GitNexus index is stale and auto-refresh failed."));

        ToolResult result = tool.execute("find auth");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("stale"));
        // MCP should NOT be called when staleness check fails
        verify(mockMcp, never()).callTool(anyString(), anyString(), anyMap());
    }

    @Test
    public void testStalenessFreshProceedsToMcp() {
        when(mockStalenessChecker.ensureFresh())
                .thenReturn(GitNexusStalenessChecker.StalenessResult.FRESH);
        when(mockMcp.callTool(eq("gitnexus"), eq("query"), anyMap()))
                .thenReturn(Collections.emptyMap());

        ToolResult result = tool.execute("find auth");
        // Should proceed to MCP call
        verify(mockMcp, times(1)).callTool(eq("gitnexus"), eq("query"), anyMap());
    }
}
