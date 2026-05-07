package com.thinkingcoding.agentloop.v2.model;

import com.thinkingcoding.model.ChatMessage;

import java.util.List;

/**
 * 单回合上下文，包含会话和执行所需的所有状态信息。
 */
public class TurnContext {
    /** 会话唯一标识符，用于关联同一对话的所有回合 */
    private final String sessionId;
    
    /** AI模型名称，指定当前回合使用的语言模型 */
    private final String modelName;
    
    /** 对话历史消息列表，包含完整的上下文信息供AI参考 */
    private final List<ChatMessage> history;
    
    /** 回合索引，标识当前是第几次用户交互（从0开始） */
    private final int turnIndex;
    
    /** 运行ID，唯一标识本次回合执行，用于追踪和调试 */
    private final String runId;

    /** 结构化 TODO 跟踪器，用于复杂任务的规划状态 */
    private final TodoTracker todoTracker = new TodoTracker();

    public TurnContext(String sessionId, String modelName, List<ChatMessage> history, int turnIndex) {
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = history;
        this.turnIndex = turnIndex;
        this.runId = generateRunId();
    }

    private String generateRunId() {
        return "run_" + System.currentTimeMillis() + "_" + turnIndex;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getModelName() {
        return modelName;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public String getRunId() {
        return runId;
    }

    public TodoTracker getTodoTracker() {
        return todoTracker;
    }
}
