package com.thinkingcoding.agentloop.v2.orchestrator;

import com.thinkingcoding.agentloop.v2.execute.DefaultToolExecutionEngine;
import com.thinkingcoding.agentloop.v2.execute.ToolExecutionEngine;
import com.thinkingcoding.agentloop.v2.gateway.AgentEventSink;
import com.thinkingcoding.agentloop.v2.gateway.DefaultAgentEventSink;
import com.thinkingcoding.agentloop.v2.gateway.DefaultSessionGateway;
import com.thinkingcoding.agentloop.v2.gateway.SessionGateway;
import com.thinkingcoding.agentloop.v2.model.ExecuteReactResult;
import com.thinkingcoding.agentloop.v2.model.PlanRequest;
import com.thinkingcoding.agentloop.v2.model.PlanResult;
import com.thinkingcoding.agentloop.v2.model.TurnContext;
import com.thinkingcoding.agentloop.v2.plan.LangChainPlanner;
import com.thinkingcoding.agentloop.v2.plan.Planner;
import com.thinkingcoding.agentloop.v2.steer.InteractiveToolConfirmationPolicy;
import com.thinkingcoding.agentloop.v2.steer.SteeringCommand;
import com.thinkingcoding.agentloop.v2.steer.ToolConfirmationPolicy;
import com.thinkingcoding.core.ThinkingCodingContext;
import com.thinkingcoding.core.ToolExecutionConfirmation;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.model.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Agent 编排器，实现 Plan → Execute + ReAct → Steering 三阶段流水线。
 * 
 * 这是新的 AgentLoop 核心，替代 legacy core.AgentLoop。
 */
public class AgentOrchestrator {

    /** 应用上下文，提供UI、AI服务、工具注册表等核心组件访问 */
    private final ThinkingCodingContext context;
    
    /** 当前会话的唯一标识符 */
    private final String sessionId;
    
    /** 使用的AI模型名称 */
    private final String modelName;
    
    /** 对话历史消息列表，维护完整的会话上下文 */
    private final List<ChatMessage> history;
    
    /**
     * V2架构核心组件
     * 实现Plan → Execute + ReAct → Steering三阶段流水线
     */
    
    /** Agent配置，控制最大ReAct步数、工具调用限制等行为参数 */
    private final AgentConfig config;
    
    /** 规划器接口，负责将用户意图转化为任务规划和工具调用序列 */
    private final Planner planner;
    
    /** 工具执行引擎，负责工具解析、参数转换和执行 */
    private final ToolExecutionEngine executionEngine;
    
    /** ReAct驱动器，实现执行-观察-反思的多步闭环逻辑 */
    private final ReActDriver reactDriver;
    
    /** 交互式工具确认策略，包装Legacy确认逻辑并提供Steering能力 */
    private final InteractiveToolConfirmationPolicy confirmationPolicy;
    
    /** 会话网关，负责会话历史的持久化存储和加载 */
    private final SessionGateway sessionGateway;
    
    /** 事件接收器，用于发布Token流、工具执行等生命周期事件 */
    private final AgentEventSink eventSink;
    
    private int turnIndex = 0;

    public AgentOrchestrator(ThinkingCodingContext context, String sessionId, String modelName) {
        this(context, sessionId, modelName, AgentConfig.defaultConfig());
    }

    public AgentOrchestrator(ThinkingCodingContext context, String sessionId, String modelName, AgentConfig config) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();
        this.config = config;

        // 初始化 V2 组件
        this.planner = new LangChainPlanner(context);
        this.executionEngine = new DefaultToolExecutionEngine(context);
        this.reactDriver = new ReActDriver(context, config);
        
        // 包装 legacy ToolExecutionConfirmation
        ToolExecutionConfirmation legacyConfirmation = new ToolExecutionConfirmation(
                context.getUi(),
                context.getUi().getLineReader()
        );
        this.confirmationPolicy = new InteractiveToolConfirmationPolicy(legacyConfirmation);
        this.confirmationPolicy.setAutoApprove(config.isAutoApproveDefault());
        
        this.sessionGateway = new DefaultSessionGateway(context.getSessionService());
        this.eventSink = new DefaultAgentEventSink(context);
    }

    /**
     * 处理用户输入（主入口）
     */
    public void onUserInput(String input) {
        try {
            // 创建回合上下文
            TurnContext turn = new TurnContext(sessionId, modelName, history, turnIndex++);

            // 添加用户消息到历史
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            // ===== Phase 1: Plan =====
            context.getUi().displayInfo("\n🤔 正在规划...");
            PlanRequest planRequest = new PlanRequest(
                    turn.getSessionId(),
                    turn.getModelName(),
                    input,
                    history,
                    false
            );

            PlanResult planResult = planner.plan(planRequest, eventSink, confirmationPolicy);

            if (planResult.isStopped()) {
                context.getUi().displayWarning("⚠️  生成已停止");
                return;
            }

                        refreshTodoFromPlan(turn, planResult.getToolCalls());
                        appendPlanSummary(turn, planResult);

            // AI 文本显示由 AgentEventSink 统一处理（支持流式与收尾）。

            // 如果有选项，显示提示
            if (planResult.hasOptions()) {
                // OptionManager 已在 Planner 中处理
            }

            // ===== Phase 2 & 3: Execute + ReAct with Steering =====
            // 如果规划结果中包含工具调用，则进入执行阶段
            if (planResult.hasToolCalls()) {
                context.getUi().displayInfo("\n🔧 检测到工具调用，开始执行...");

                // 启动 ReAct 驱动器，执行工具调用序列
                // ReAct 模式会循环执行：执行工具 → 观察结果 → 反思续跑
                ExecuteReactResult executeResult = reactDriver.run(
                        turn,
                        planResult,
                        planner,
                        executionEngine,
                        confirmationPolicy,
                        eventSink,
                        confirmationPolicy // 同时作为 SteeringHandle
                );

                if (executeResult.isCancelled()) {
                    context.getUi().displayWarning("⚠️  执行已取消");
                } else if (executeResult.hasExecutions()) {
                    context.getUi().displaySuccess("\n✅ 执行完成，共 " + executeResult.getSteps() + " 步");
                }
            }

            // 保存会话
            sessionGateway.save(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("❌ 处理输入时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理 Steering 命令
     */
    public void onSteeringCommand(SteeringCommand cmd) {
        confirmationPolicy.handleCommand(cmd);
        
        switch (cmd) {
            case STOP_GENERATION:
                // 尝试停止当前生成（如果 AI Service 支持）
                if (context.getAiService() instanceof com.thinkingcoding.service.LangChainService) {
                    ((com.thinkingcoding.service.LangChainService) context.getAiService()).stopCurrentGeneration();
                }
                break;
            case CANCEL_TURN:
                context.getUi().displayInfo("回合已取消");
                break;
            default:
                // 其他命令由 policy 内部处理
                break;
        }
    }

    /**
     * 获取历史消息快照
     */
    public List<ChatMessage> snapshotHistory() {
        return new ArrayList<>(history);
    }

    /**
     * 加载历史消息
     */
    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.clear();
            history.addAll(previousHistory);
        }
    }

    /**
     * 设置自动批准模式
     */
    public void setAutoApprove(boolean enabled) {
        confirmationPolicy.setAutoApprove(enabled);
    }

    /**
     * 检查是否处于自动批准模式
     */
    public boolean isAutoApproveMode() {
        return confirmationPolicy.isAutoApprove();
    }

    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    private void appendPlanSummary(TurnContext turn, PlanResult planResult) {
        String planSummary = formatPlanSummary(planResult);
        if (!planSummary.isBlank()) {
            context.getUi().displayInfo("\n" + planSummary);
            history.add(new ChatMessage("system", planSummary));
        }

        String todoSummary = formatTodoSummary(turn);
        if (!todoSummary.isBlank()) {
            context.getUi().displayInfo("\n" + todoSummary);
            history.add(new ChatMessage("system", todoSummary));
        }
    }

    private void refreshTodoFromPlan(TurnContext turn, List<ToolCall> toolCalls) {
        if (turn == null || toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        List<String> todoLines = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            if (!"manage_todo".equals(call.getToolName())) {
                continue;
            }
            Object action = call.getParameters() == null ? null : call.getParameters().get("action");
            Object content = call.getParameters() == null ? null : call.getParameters().get("content");
            if (action == null || content == null) {
                continue;
            }
            if (!"add".equalsIgnoreCase(String.valueOf(action))) {
                continue;
            }
            todoLines.add(String.valueOf(content));
        }

        if (!todoLines.isEmpty()) {
            turn.getTodoTracker().resetWithContents(todoLines);
        }
    }

    private String formatPlanSummary(PlanResult planResult) {
        if (planResult == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<plan>\n");

        String assistantText = planResult.getAssistantText();
        if (assistantText != null && !assistantText.isBlank()) {
            builder.append("计划输出:\n");
            builder.append(assistantText.trim()).append("\n");
        }

        List<ToolCall> toolCalls = planResult.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            builder.append("工具调用:\n");
            int index = 1;
            for (ToolCall call : toolCalls) {
                builder.append("-").append(index++).append(" ")
                        .append(call.getToolName());
                if (call.getParameters() != null && !call.getParameters().isEmpty()) {
                    builder.append(" ").append(call.getParameters());
                }
                builder.append("\n");
            }
        }

        builder.append("</plan>");
        return builder.toString();
    }

    private String formatTodoSummary(TurnContext turn) {
        if (turn == null) {
            return "";
        }
        return turn.getTodoTracker().renderSystemReminder();
    }
}
