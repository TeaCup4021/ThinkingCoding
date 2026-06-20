package com.thinkingcoding.service;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager（facade）行为测试。
 *
 * <p>旧的反应式压缩开关（shouldCompressHistory / setStrategy）已移除，
 * 窗口构建委托给 WorkingMemory。这里只验证 facade 职责：
 * 预算计算、token 计数入口、委托后不 mutate 入参。
 */
class ContextManagerTest {

    private ContextManager createManager(int maxTokens, int maxContextTokens) {
        AppConfig appConfig = new AppConfig();
        AppConfig.ModelConfig modelConfig = new AppConfig.ModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setBaseURL("http://localhost");
        modelConfig.setApiKey("test-key");
        modelConfig.setMaxTokens(maxTokens);
        modelConfig.setMaxContextTokens(maxContextTokens);

        Map<String, AppConfig.ModelConfig> models = new HashMap<>();
        models.put("test", modelConfig);
        appConfig.setModels(models);
        appConfig.setDefaultModel("test");

        ContextManager manager = new ContextManager(appConfig);
        manager.setConversationSummarizer(p -> "[对话摘要]\nstub");
        return manager;
    }

    @Test
    void budgetIsMaxContextMinusMaxOutput() {
        ContextManager manager = createManager(100, 300);
        assertEquals(200, manager.getCompressionThreshold());
    }

    @Test
    void estimateTokensReturnsPositiveForNonEmpty() {
        ContextManager manager = createManager(100, 300);
        assertEquals(0, manager.estimateTokens(""));
        assertTrue(manager.estimateTokens("你好，这是一段中文") > 0);
    }

    @Test
    void emptyHistoryReturnsEmpty() {
        ContextManager manager = createManager(100, 300);
        assertTrue(manager.getContextForAI(new ArrayList<>()).isEmpty());
        assertTrue(manager.getContextForAI(null).isEmpty());
    }

    @Test
    void delegatesToWorkingMemoryWithoutMutatingInput() {
        ContextManager manager = createManager(100, 300);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(new ChatMessage("user", "用户第 " + i + " 轮提问内容", "sid"));
            history.add(new ChatMessage("assistant", "助手第 " + i + " 轮回答内容", "sid"));
        }
        List<ChatMessage> snapshot = new ArrayList<>(history);

        List<ChatMessage> out = manager.getContextForAI(history, 50);

        assertEquals(snapshot, history, "委托不应改写原始历史");
        assertNotSame(history, out);
        assertFalse(out.isEmpty());
    }
}
