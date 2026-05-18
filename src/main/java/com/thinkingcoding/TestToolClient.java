package com.thinkingcoding;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.tools.rag.CodeGraphTool;
import com.thinkingcoding.mcp.MCPClient;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.tools.ToolRegistry;
import java.util.List;
public class TestToolClient {
    public static void main(String[] args) throws Exception {
        MCPService service = new MCPService(null);
        service.connectToServer("gitnexus", "npx", java.util.Arrays.asList("gitnexus", "mcp"));
        CodeGraphTool tool = new CodeGraphTool(null, service, null);
        System.out.println(tool.execute("{\"target\":\"ThinkingCodingCLI\"}"));
        System.exit(0);
    }
}
