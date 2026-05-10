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
import java.util.Map;

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

            // 🔥 将 assistant 回复以正确 role 记入 history
            if (planResult.getAssistantText() != null && !planResult.getAssistantText().isBlank()) {
                history.add(new ChatMessage("assistant", planResult.getAssistantText()));
            }

            // ===== Plan 确认环节 =====
            ExecuteReactResult executeResult = null;
            if (planResult.hasToolCalls()) {
                // 展示计划摘要（仅工具调用，不含 assistant 文本）
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
                executeResult = reactDriver.run(
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
                // 无工具调用的纯文本回复 — assistant 消息已记入 history
            }

            // 🔥 保存当前上下文数据到 ContextManager，供下一轮 Current Context 使用
            saveCurrentContext(planResult, executeResult);

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

        // 先检查是否有 AI 显式声明的 manage_todo 条目
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

        // 如果没有显式 TODO，从普通工具调用中自动生成
        if (todoLines.isEmpty()) {
            for (ToolCall call : toolCalls) {
                if ("manage_todo".equals(call.getToolName())) {
                    continue;
                }
                StringBuilder desc = new StringBuilder(call.getToolName());
                Map<String, Object> params = call.getParameters();
                if (params != null && !params.isEmpty()) {
                    // 提取有意义的参数作为描述
                    Object path = params.get("path");
                    Object name = params.get("name");
                    Object target = params.get("target");
                    Object command = params.get("command");
                    if (path != null) {
                        desc.append(" ").append(path);
                    } else if (name != null) {
                        desc.append(" ").append(name);
                    } else if (target != null) {
                        desc.append(" ").append(target);
                    } else if (command != null) {
                        desc.append(" ").append(command);
                    }
                }
                todoLines.add(desc.toString());
            }
        }

        if (!todoLines.isEmpty()) {
            turn.getTodoTracker().resetWithContents(todoLines);
        }
    }

    /**
     * 🔥 保存当前 turn 的 Plan 摘要和工具执行结果到 ContextManager，
     * 作为下一轮 Current Context 的 "当前目标" 和 "上一轮执行结果"。
     */
    private void saveCurrentContext(PlanResult planResult, ExecuteReactResult executeResult) {
        com.thinkingcoding.service.ContextManager ctxMgr = context.getContextManager();
        if (ctxMgr == null) {
            return;
        }

        // 仅在有工具执行时保存上下文；纯对话 turn 不产生 Current Context 数据
        if (executeResult == null || !executeResult.hasExecutions()) {
            return;
        }

        // 保存简洁任务摘要作为下一轮的 "当前目标"
        String taskSummary = extractTaskSummary(planResult);
        if (taskSummary != null && !taskSummary.isBlank()) {
            ctxMgr.setLastPlanSummary(taskSummary);
        }

        // 保存上一轮工具执行结果（完整保留）
        StringBuilder results = new StringBuilder();
        List<com.thinkingcoding.agentloop.v2.execute.ToolExecutionOutcome> trace = executeResult.getTrace();
        for (int i = 0; i < trace.size(); i++) {
            com.thinkingcoding.agentloop.v2.execute.ToolExecutionOutcome outcome = trace.get(i);
            if (outcome.isExecuted()) {
                results.append("**").append(outcome.getCall().getToolName()).append("**: ");
                com.thinkingcoding.model.ToolResult toolResult = outcome.getResult();
                if (toolResult != null) {
                    if (toolResult.isSuccess()) {
                        String output = toolResult.getOutput();
                        if (output != null && output.length() > 2000) {
                            output = output.substring(0, 2000) + "\n... (输出截断，完整结果见历史)";
                        }
                        results.append("✅ ").append(output != null ? output : "执行成功").append("\n");
                    } else {
                        results.append("❌ ").append(toolResult.getError() != null ? toolResult.getError() : "执行失败").append("\n");
                    }
                }
            }
        }
        if (!results.isEmpty()) {
            ctxMgr.setLastToolResults(results.toString());
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
            builder.append("方案计划:\n");
            builder.append(assistantText.trim()).append("\n");
        }

        List<ToolCall> toolCalls = planResult.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            builder.append("\n工具调用:\n");
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

    /**
     * 从 PlanResult 中提取简洁的任务摘要，用作下一轮的"当前目标"。
     * 优先取 assistant text 的第一句，fallback 到工具名列表。
     */
    private String extractTaskSummary(PlanResult planResult) {
        if (planResult == null) {
            return null;
        }

        String assistantText = planResult.getAssistantText();
        if (assistantText != null && !assistantText.isBlank()) {
            // 取第一句（以 。！？.!? 或换行 为界，最多 100 字）
            String text = assistantText.trim();
            int end = -1;
            for (char delim : new char[]{'。', '！', '？', '.', '!', '?', '\n'}) {
                int pos = text.indexOf(delim);
                if (pos > 0 && (end < 0 || pos < end)) {
                    end = pos;
                }
            }
            if (end > 0 && end < 100) {
                return text.substring(0, end + 1).trim();
            }
            if (text.length() <= 100) {
                return text;
            }
            return text.substring(0, 100) + "...";
        }

        // fallback: 工具调用名列表
        List<ToolCall> toolCalls = planResult.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                names.add(call.getToolName());
            }
            return "执行工具: " + String.join(", ", names);
        }

        return null;
    }

    private String formatTodoSummary(TurnContext turn) {
        if (turn == null) {
            return "";
        }
        return turn.getTodoTracker().renderSystemReminder();
    }
}
