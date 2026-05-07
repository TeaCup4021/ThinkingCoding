package com.thinkingcoding;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.mcp.MCPClient;
import com.thinkingcoding.mcp.model.MCPResponse;
public class TestClient {
    public static void main(String[] args) throws Exception {
        MCPClient client = new MCPClient("gitnexus");
        client.connect("npx", java.util.Arrays.asList("gitnexus", "mcp"));
        Object response = client.callTool("context", Map.of("name", "ThinkingCodingCLI"));
        System.out.println("Type: " + (response != null ? response.getClass().getName() : "null"));
        System.out.println("Value: " + response);
        client.disconnect();
        System.exit(0);
    }
}
