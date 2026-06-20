package com.thinkingcoding.service;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager facade 在新工作记忆架构下的端到端测试。
 *
 * <p>验证：预算内不压缩、超预算挤出旧轮、保留最近内容、micro_compact 生效、
 * 不 mutate 原始历史。三策略 / 反应式压缩相关用例已随旧 API 移除。
 */
class TokenControlTest {

    private ContextManager contextManager;
    private String sid; // 每个测试独立 sessionId，避免 summary sidecar 串台

    @BeforeEach
    void setUp() {
        sid = "tk-test-" + UUID.randomUUID();
        AppConfig appConfig = new AppConfig();
        AppConfig.ModelConfig modelConfig = new AppConfig.ModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setBaseURL("http://localhost");
        modelConfig.setApiKey("test-key");
        modelConfig.setMaxTokens(500);           // 最大输出
        modelConfig.setMaxContextTokens(1000);   // 最大上下文 → 默认预算 500

        Map<String, AppConfig.ModelConfig> models = new HashMap<>();
        models.put("test", modelConfig);
        appConfig.setModels(models);
        appConfig.setDefaultModel("test");

        contextManager = new ContextManager(appConfig);
        contextManager.setConversationSummarizer(prompt -> "[对话摘要]\nstub-summary");
    }

    @AfterEach
    void tearDown() throws Exception {
        Path sidecar = Paths.get("sessions", sid + ".summary.md");
        Files.deleteIfExists(sidecar);
    }

    private List<ChatMessage> createMockHistory(int messageCount) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            String content = role.equals("user")
                    ? "用户问题 " + (i / 2 + 1) + ": 这是一个测试问题，内容较长以模拟真实的对话场景。"
                    : "AI回答 " + (i / 2 + 1) + ": 这是AI的回答内容，包含详细的解释和说明，用于测试token压缩功能。";
            history.add(new ChatMessage(role, content, sid));
        }
        return history;
    }

    @Test
    void noCompressionWhenUnderBudget() {
        List<ChatMessage> history = createMockHistory(4);
        // 大预算，应原样保留
        List<ChatMessage> managed = contextManager.getContextForAI(history, 100000);
        assertEquals(history.size(), managed.size(), "预算充足时不应压缩");
    }

    @Test
    void compressionWhenOverBudget() {
        List<ChatMessage> history = createMockHistory(30);
        List<ChatMessage> managed = contextManager.getContextForAI(history, 60);
        assertTrue(managed.size() < history.size(), "超预算时应挤出旧轮，消息数减少");
    }

    @Test
    void preservesRecentMessages() {
        List<ChatMessage> history = createMockHistory(20);
        ChatMessage last = history.get(history.size() - 1);
        List<ChatMessage> managed = contextManager.getContextForAI(history, 80);
        boolean containsLast = managed.stream()
                .anyMatch(m -> m.getContent().equals(last.getContent()));
        assertTrue(containsLast, "压缩后应保留最近的对话内容");
    }

    @Test
    void microCompactCompressesLongToolResult() {
        List<ChatMessage> history = new ArrayList<>();
        StringBuilder longContent = new StringBuilder();
        longContent.append("Tool 'file_manager' executed successfully with parameters: {...}\nResult:\n");
        for (int i = 0; i < 100; i++) {
            longContent.append("这是第").append(i).append("行很长的文件内容，用于测试微压缩功能。\n");
        }
        history.add(new ChatMessage("user", "读取文件", "sid"));
        history.add(new ChatMessage("system", longContent.toString(), "sid"));
        history.add(new ChatMessage("user", "下一个问题", "sid"));

        List<ChatMessage> managed = contextManager.getContextForAI(history, 100000);

        ChatMessage systemMsg = managed.stream()
                .filter(m -> "system".equals(m.getRole()))
                .findFirst().orElse(null);
        assertNotNull(systemMsg);
        assertTrue(systemMsg.getContent().length() < longContent.length(), "超长工具结果应被微压缩");
        assertTrue(systemMsg.getContent().contains("[... omitted"), "微压缩应包含省略标记");
    }

    @Test
    void doesNotMutateOriginalHistory() {
        List<ChatMessage> history = createMockHistory(20);
        List<ChatMessage> snapshot = new ArrayList<>(history);

        List<ChatMessage> managed = contextManager.getContextForAI(history, 60);

        assertEquals(snapshot, history, "压缩视图不应改写原始历史");
        assertNotSame(history, managed, "返回值应是新的历史视图");
        assertTrue(managed.size() < history.size(), "压缩视图应当更短");
    }

    @Test
    void usesStubSummaryWithoutNetwork() {
        List<ChatMessage> history = createMockHistory(20);
        List<ChatMessage> managed = contextManager.getContextForAI(history, 60);
        assertFalse(managed.isEmpty());
        // 被挤出的旧轮会进入滚动摘要，首条应为摘要
        assertTrue(managed.get(0).getContent().contains("[对话摘要]"),
                "挤出旧轮时窗口首条应为滚动摘要");
    }
}
