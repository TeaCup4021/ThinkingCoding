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

            // AI 文本显示由 AgentEventSink 统一处理（支持流式与收尾）。

            // 如果有选项，显示提示
            if (planResult.hasOptions()) {
                // OptionManager 已在 Planner 中处理
            }

            // ===== Plan 确认环节 =====
            // 如果规划结果中包含工具调用，先展示计划让用户确认，再进入执行阶段
            if (planResult.hasToolCalls()) {
                // 展示计划摘要
                String planSummary = formatPlanSummary(planResult);
                if (!planSummary.isBlank()) {
                    context.getUi().displayInfo("\n" + planSummary);
                }

                // 非自动批准模式下询问用户确认
                if (!confirmationPolicy.isAutoApprove()) {
                    boolean confirmed = askUserPlanConfirmation();
                    if (!confirmed) {
                        context.getUi().displayWarning("⚠️  计划已取消，请重新描述你的需求");
                        history.add(new ChatMessage("system",
                                "用户取消了此计划。请等待用户重新描述需求。"));
                        sessionGateway.save(sessionId, history);
                        return;
                    }
                }

                // 用户确认 → 生成 TODO 并记入历史
                refreshTodoFromPlan(turn, planResult.getToolCalls());
                String todoSummary = formatTodoSummary(turn);
                if (!todoSummary.isBlank()) {
                    context.getUi().displayInfo("\n" + todoSummary);
                    history.add(new ChatMessage("system", todoSummary));
                }
                if (!planSummary.isBlank()) {
                    history.add(new ChatMessage("system", planSummary));
                }

                // ===== Phase 2 & 3: Execute + ReAct with Steering =====
                context.getUi().displayInfo("\n🔧 开始执行...");
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
            } else {
                // 无工具调用的纯文本回复，仅记入历史
                String planSummary = formatPlanSummary(planResult);
                if (!planSummary.isBlank()) {
                    history.add(new ChatMessage("system", planSummary));
                }
            }

            // 保存会话
            sessionGateway.save(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("❌ 处理输入时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== 与 Legacy AgentLoop 对齐的方法名，便于 CLI 统一调用 =====

    /** @see #onUserInput(String) */
    public void processInput(String input) {
        onUserInput(input);
    }

    /** @see #snapshotHistory() */
    public List<ChatMessage> getHistory() {
        return snapshotHistory();
    }

    /**
     * 处理 Steering 命令（字符串形式，由 CLI 直接调用）。
     * @return true 表示命令已处理
     */
    public boolean handleSteeringCommand(String input) {
        String command = input.toLowerCase();
        switch (command) {
            case "/stop":
                onSteeringCommand(SteeringCommand.STOP_GENERATION);
                context.getUi().displayInfo("⏸️  已停止生成");
                return true;
            case "/cancel":
                onSteeringCommand(SteeringCommand.CANCEL_TURN);
                context.getUi().displayInfo("⚠️  回合已取消");
                return true;
            case "/auto-approve-on":
                setAutoApprove(true);
                context.getUi().displaySuccess("✅ Auto-approve 已开启");
                return true;
            case "/auto-approve-off":
                setAutoApprove(false);
                context.getUi().displaySuccess("✅ Auto-approve 已关闭");
                return true;
            default:
                return false;
        }
    }

    /** 返回版本标识 */
    public String getVersion() {
        return "v2";
    }

    // ===== 原有方法 =====

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

    private boolean askUserPlanConfirmation() {
        try {
            String response = context.getUi().getLineReader().readLine(
                    "\n是否按此计划执行？ [y/n]: ");
            return response != null && (response.trim().equalsIgnoreCase("y")
                    || response.trim().equalsIgnoreCase("yes"));
        } catch (Exception e) {
            return false;
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
