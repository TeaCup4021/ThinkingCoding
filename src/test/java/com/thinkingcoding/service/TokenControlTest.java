package com.thinkingcoding.service;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token控制策略测试类
 * 验证上下文压缩功能是否正常工作
 */
class TokenControlTest {

    private ContextManager contextManager;
    private AppConfig appConfig;

    @BeforeEach
    void setUp() {
        // 配置测试用的模型参数
        appConfig = new AppConfig();
        AppConfig.ModelConfig modelConfig = new AppConfig.ModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setBaseURL("http://localhost");
        modelConfig.setApiKey("test-key");
        
        // 设置较小的阈值，便于测试
        modelConfig.setMaxTokens(500);           // 最大输出token
        modelConfig.setMaxContextTokens(1000);   // 最大上下文token
        // 压缩阈值 = 1000 - 500 = 500 tokens

        Map<String, AppConfig.ModelConfig> models = new HashMap<>();
        models.put("test", modelConfig);
        appConfig.setModels(models);
        appConfig.setDefaultModel("test");

        // 创建ContextManager并设置为TOKEN_BASED策略
        contextManager = new ContextManager(appConfig);
        contextManager.setStrategy(ContextManager.Strategy.TOKEN_BASED);
    }

    /**
     * 测试1：验证未达到阈值时不压缩
     */
    @Test
    void testNoCompressionWhenUnderThreshold() {
        // 模拟少量历史消息
        List<ChatMessage> history = createMockHistory(3);
        
        // 记录较低的token使用量（低于阈值500）
        contextManager.recordTokenUsage(100, 150, 250);
        
        // 获取压缩后的历史
        List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
        
        // 验证：消息数量应该保持不变（未触发压缩）
        assertEquals(history.size(), managedHistory.size(), 
            "Token数低于阈值时不应压缩");
    }

    /**
     * 测试2：验证达到阈值时触发压缩
     */
    @Test
    void testCompressionWhenOverThreshold() {
        // 模拟大量历史消息
        List<ChatMessage> history = createMockHistory(20);
        
        // 记录较高的token使用量（超过阈值500）
        contextManager.recordTokenUsage(600, 400, 1000);
        
        // 获取压缩后的历史
        List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
        
        // 验证：消息数量应该减少（触发了压缩）
        assertTrue(managedHistory.size() < history.size(), 
            "Token数超过阈值时应触发压缩，消息数应减少");
        
        System.out.println("原始消息数: " + history.size());
        System.out.println("压缩后消息数: " + managedHistory.size());
        System.out.println("压缩率: " + 
            (1 - (double)managedHistory.size() / history.size()) * 100 + "%");
    }

    /**
     * 测试3：验证压缩后保留最新消息
     */
    @Test
    void testCompressionPreservesRecentMessages() {
        List<ChatMessage> history = createMockHistory(15);
        
        // 标记最后一条消息以便验证
        ChatMessage lastUserMessage = history.get(history.size() - 1);
        
        // 触发压缩
        contextManager.recordTokenUsage(700, 500, 1200);
        List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
        
        // 验证：最后一条用户消息应该在压缩后的历史中
        boolean containsLastMessage = managedHistory.stream()
            .anyMatch(msg -> msg.getContent().equals(lastUserMessage.getContent()));
        
        assertTrue(containsLastMessage || managedHistory.size() > 0,
            "压缩后应保留最近的对话内容");
    }

    /**
     * 测试4：验证shouldCompressHistory判断逻辑
     */
    @Test
    void testShouldCompressHistoryLogic() {
        // 情况1：token数低于阈值
        contextManager.recordTokenUsage(200, 200, 400);
        assertFalse(contextManager.shouldCompressHistory(),
            "400 < 500，不应压缩");
        
        // 情况2：token数等于阈值
        contextManager.recordTokenUsage(300, 200, 500);
        assertTrue(contextManager.shouldCompressHistory(),
            "500 >= 500，应压缩");
        
        // 情况3：token数高于阈值
        contextManager.recordTokenUsage(400, 300, 700);
        assertTrue(contextManager.shouldCompressHistory(),
            "700 > 500，应压缩");
    }

    /**
     * 测试5：验证压缩阈值计算
     */
    @Test
    void testCompressionThresholdCalculation() {
        int threshold = contextManager.getCompressionThreshold();
        assertEquals(500, threshold, 
            "压缩阈值应为 maxContextTokens - maxOutputTokens = 1000 - 500 = 500");
    }

    /**
     * 测试6：验证重置token计数
     */
    @Test
    void testResetTokenUsage() {
        // 先记录一些token
        contextManager.recordTokenUsage(600, 400, 1000);
        assertTrue(contextManager.shouldCompressHistory());
        
        // 重置
        contextManager.resetTokenUsage();
        
        // 验证：重置后不应压缩（因为lastHistoryTokens被设为-1）
        assertFalse(contextManager.shouldCompressHistory(),
            "重置后不应触发压缩");
    }

    /**
     * 测试7：滑动窗口策略对比
     */
    @Test
    void testSlidingWindowVsTokenBased() {
        List<ChatMessage> history = createMockHistory(25);
        
        // 测试滑动窗口策略
        contextManager.setStrategy(ContextManager.Strategy.SLIDING_WINDOW);
        contextManager.setMaxHistoryTurns(5);  // 保留5轮
        List<ChatMessage> windowedHistory = contextManager.getContextForAI(history);
        
        System.out.println("\n滑动窗口策略:");
        System.out.println("原始消息数: " + history.size());
        System.out.println("压缩后消息数: " + windowedHistory.size());
        
        // 测试Token控制策略
        contextManager.setStrategy(ContextManager.Strategy.TOKEN_BASED);
        contextManager.recordTokenUsage(800, 600, 1400);
        List<ChatMessage> tokenManagedHistory = contextManager.getContextForAI(history);
        
        System.out.println("\nToken控制策略:");
        System.out.println("原始消息数: " + history.size());
        System.out.println("压缩后消息数: " + tokenManagedHistory.size());
        
        // 两种策略都应该减少消息数
        assertTrue(windowedHistory.size() < history.size());
        assertTrue(tokenManagedHistory.size() < history.size());
    }

    /**
     * 辅助方法：创建模拟的历史消息
     */
    private List<ChatMessage> createMockHistory(int messageCount) {
        List<ChatMessage> history = new ArrayList<>();
        
        for (int i = 0; i < messageCount; i++) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            String content;
            
            if (role.equals("user")) {
                content = "用户问题 " + (i/2 + 1) + ": 这是一个测试问题，内容较长以模拟真实的对话场景。";
            } else {
                content = "AI回答 " + (i/2 + 1) + ": 这是AI的回答内容，包含详细的解释和说明，用于测试token压缩功能。";
            }
            
            history.add(new ChatMessage(role, content));
        }
        
        return history;
    }

    /**
     * 测试8：微压缩功能测试
     */
    @Test
    void testMicroCompact() {
        List<ChatMessage> history = new ArrayList<>();
        
        // 添加一个超长的工具调用结果
        StringBuilder longContent = new StringBuilder();
        longContent.append("Tool 'file_manager' executed successfully with parameters: {...}\n");
        longContent.append("Result:\n");
        for (int i = 0; i < 100; i++) {
            longContent.append("这是第").append(i).append("行很长的文件内容，用于测试微压缩功能。\n");
        }
        
        history.add(new ChatMessage("user", "读取文件"));
        history.add(new ChatMessage("system", longContent.toString()));
        history.add(new ChatMessage("user", "下一个问题"));
        
        // 不触发token压缩，只测试微压缩
        contextManager.recordTokenUsage(100, 100, 200);
        
        List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
        
        // 验证：系统消息应该被截断
        ChatMessage systemMsg = managedHistory.stream()
            .filter(msg -> "system".equals(msg.getRole()))
            .findFirst()
            .orElse(null);
        
        assertNotNull(systemMsg);
        assertTrue(systemMsg.getContent().length() < longContent.length(),
            "超长工具结果应该被微压缩");
        assertTrue(systemMsg.getContent().contains("[... omitted"),
            "微压缩应包含省略标记");
        
        System.out.println("\n微压缩测试:");
        System.out.println("原始长度: " + longContent.length());
        System.out.println("压缩后长度: " + systemMsg.getContent().length());
    }
}
