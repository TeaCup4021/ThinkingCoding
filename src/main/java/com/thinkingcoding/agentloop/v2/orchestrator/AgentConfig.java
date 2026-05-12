package com.thinkingcoding.agentloop.v2.orchestrator;

/**
 * V2 AgentLoop 的配置类。
 */
public class AgentConfig {
    
    private boolean enabled = false;
    /** 最大 ReAct 循环迭代次数（含跳过/丢弃），安全网兜底，防止死循环 */
    private int maxReActStepsPerTurn = 50;
    /** 单轮最大实际执行工具数，只统计 isExecuted==true 的调用 */
    private int maxToolCallsPerPlan = 30;
    private boolean autoApproveDefault = false;
    private SteeringMode steeringMode = SteeringMode.STRICT;

    public enum SteeringMode {
        /** 严格模式：所有工具都需要确认 */
        STRICT,
        
        /** 宽松模式：部分安全工具可自动执行 */
        LENIENT
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxReActStepsPerTurn() {
        return maxReActStepsPerTurn;
    }

    public void setMaxReActStepsPerTurn(int maxReActStepsPerTurn) {
        this.maxReActStepsPerTurn = maxReActStepsPerTurn;
    }

    public int getMaxToolCallsPerPlan() {
        return maxToolCallsPerPlan;
    }

    public void setMaxToolCallsPerPlan(int maxToolCallsPerPlan) {
        this.maxToolCallsPerPlan = maxToolCallsPerPlan;
    }

    public boolean isAutoApproveDefault() {
        return autoApproveDefault;
    }

    public void setAutoApproveDefault(boolean autoApproveDefault) {
        this.autoApproveDefault = autoApproveDefault;
    }

    public SteeringMode getSteeringMode() {
        return steeringMode;
    }

    public void setSteeringMode(SteeringMode steeringMode) {
        this.steeringMode = steeringMode;
    }

    public static AgentConfig defaultConfig() {
        AgentConfig config = new AgentConfig();
        config.setEnabled(false); // 默认关闭，需要显式启用
        config.setMaxReActStepsPerTurn(50);
        config.setMaxToolCallsPerPlan(30);
        config.setAutoApproveDefault(false);
        config.setSteeringMode(SteeringMode.STRICT);
        return config;
    }
}
