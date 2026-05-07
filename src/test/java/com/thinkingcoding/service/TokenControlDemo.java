package com.thinkingcoding.service;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token控制策略演示程序
 * 用于实际观察压缩效果
 */
public class TokenControlDemo {

    public static void main(String[] args) {
        System.out.println("=== Token控制策略演示 ===\n");
        
        // 1. 初始化配置
        AppConfig appConfig = createTestConfig();
        ContextManager contextManager = new ContextManager(appConfig);
        contextManager.setStrategy(ContextManager.Strategy.TOKEN_BASED);
        
        System.out.println("配置信息:");
        System.out.println("  策略: " + contextManager.getStrategy());
        System.out.println("  最大上下文Token: " + getMaxContextTokens(appConfig));
        System.out.println("  最大输出Token: " + getMaxOutputTokens(appConfig));
        System.out.println("  压缩阈值: " + contextManager.getCompressionThreshold());
        System.out.println();
        
        // 2. 模拟多轮对话
        List<ChatMessage> history = new ArrayList<>();
        
        System.out.println("开始模拟对话...\n");
        
        for (int round = 1; round <= 15; round++) {
            System.out.println("--- 第 " + round + " 轮对话 ---");
            
            // 添加用户消息
            String userMessage = "用户问题 " + round + ": " + generateQuestion(round);
            history.add(new ChatMessage("user", userMessage));
            System.out.println("用户: " + userMessage.substring(0, Math.min(50, userMessage.length())) + "...");
            
            // 添加AI回复
            String aiMessage = "AI回答 " + round + ": " + generateAnswer(round);
            history.add(new ChatMessage("assistant", aiMessage));
            System.out.println("AI: " + aiMessage.substring(0, Math.min(50, aiMessage.length())) + "...");
            
            // 模拟token使用（逐轮递增）
            int promptTokens = 100 + (round - 1) * 80;
            int completionTokens = 150 + (round - 1) * 60;
            int totalTokens = promptTokens + completionTokens;
            
            contextManager.recordTokenUsage(promptTokens, completionTokens, totalTokens);
            
            System.out.println("Token统计:");
            System.out.println("  输入Token: " + promptTokens);
            System.out.println("  输出Token: " + completionTokens);
            System.out.println("  总Token: " + totalTokens);
            System.out.println("  是否触发压缩: " + contextManager.shouldCompressHistory());
            
            // 获取压缩后的历史
            List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
            
            System.out.println("历史记录:");
            System.out.println("  原始消息数: " + history.size());
            System.out.println("  压缩后消息数: " + managedHistory.size());
            System.out.println("  压缩率: " + 
                String.format("%.2f", (1 - (double)managedHistory.size() / history.size()) * 100) + "%");
            
            if (managedHistory.size() < history.size()) {
                System.out.println("  ✅ 已触发压缩！");
                
                // 检查是否有摘要消息
                boolean hasSummary = managedHistory.stream()
                    .anyMatch(msg -> msg.getContent().contains("[Conversation compressed.]"));
                if (hasSummary) {
                    System.out.println("  ✅ 发现对话摘要");
                }
            } else {
                System.out.println("  ⏸️  未触发压缩");
            }
            
            System.out.println();
            
            // 如果触发了压缩，重置历史为压缩后的结果
            if (managedHistory.size() < history.size()) {
                history = new ArrayList<>(managedHistory);
                System.out.println("  🔄 历史已更新为压缩后的内容\n");
            }
        }
        
        System.out.println("\n=== 演示结束 ===");
        System.out.println("\n关键观察点:");
        System.out.println("1. 当总Token数 >= 压缩阈值时，会触发压缩");
        System.out.println("2. 压缩后消息数量会减少");
        System.out.println("3. 压缩会保留最近的对话，用摘要替换早期对话");
        System.out.println("4. 压缩后token计数会重置，开始新的周期");
    }
    
    /**
     * 创建测试配置
     */
    private static AppConfig createTestConfig() {
        AppConfig appConfig = new AppConfig();
        AppConfig.ModelConfig modelConfig = new AppConfig.ModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setBaseURL("http://localhost");
        modelConfig.setApiKey("test-key");
        modelConfig.setMaxTokens(500);
        modelConfig.setMaxContextTokens(1000);
        
        Map<String, AppConfig.ModelConfig> models = new HashMap<>();
        models.put("test", modelConfig);
        appConfig.setModels(models);
        appConfig.setDefaultModel("test");
        
        return appConfig;
    }
    
    /**
     * 获取最大上下文Token数
     */
    private static int getMaxContextTokens(AppConfig config) {
        AppConfig.ModelConfig modelConfig = config.getModelConfig(config.getDefaultModel());
        return modelConfig != null ? modelConfig.getMaxContextTokens() : 0;
    }
    
    /**
     * 获取最大输出Token数
     */
    private static int getMaxOutputTokens(AppConfig config) {
        AppConfig.ModelConfig modelConfig = config.getModelConfig(config.getDefaultModel());
        return modelConfig != null ? modelConfig.getMaxTokens() : 0;
    }
    
    /**
     * 生成模拟问题
     */
    private static String generateQuestion(int round) {
        String[] questions = {
            "帮我创建一个Java排序算法",
            "解释一下快速排序的原理",
            "那冒泡排序呢？",
            "哪种排序更快？",
            "优化这个算法的性能",
            "添加单元测试",
            "如何处理边界情况？",
            "能画个流程图吗？",
            "时间复杂度是多少？",
            "空间复杂度呢？",
            "有没有更好的算法？",
            "在实际项目中如何使用？",
            "如何测试这个算法？",
            "性能瓶颈在哪里？",
            "如何进一步优化？"
        };
        return questions[(round - 1) % questions.length];
    }
    
    /**
     * 生成模拟回答
     */
    private static String generateAnswer(int round) {
        StringBuilder answer = new StringBuilder();
        answer.append("这是一个详细的回答，包含多个段落和代码示例。");
        answer.append("首先，我们需要理解基本概念...");
        answer.append("其次，实现细节包括以下几个步骤：");
        answer.append("1. 第一步说明");
        answer.append("2. 第二步说明");
        answer.append("3. 第三步说明");
        answer.append("最后，我们需要注意一些边界情况和性能优化点。");
        answer.append("这个解决方案在实际项目中已经得到了验证。");
        
        // 根据轮次增加长度
        for (int i = 0; i < round * 2; i++) {
            answer.append(" 补充说明第").append(i).append("点，这是额外的详细内容。");
        }
        
        return answer.toString();
    }
}
