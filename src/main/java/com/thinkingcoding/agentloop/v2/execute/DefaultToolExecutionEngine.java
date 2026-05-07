package com.thinkingcoding.agentloop.v2.execute;

import com.thinkingcoding.agentloop.v2.gateway.AgentEventSink;
import com.thinkingcoding.agentloop.v2.model.TurnContext;
import com.thinkingcoding.agentloop.v2.steer.SteeringHandle;
import com.thinkingcoding.agentloop.v2.steer.ToolConfirmationPolicy;
import com.thinkingcoding.agentloop.v2.steer.ToolDecision;
import com.thinkingcoding.core.ThinkingCodingContext;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.model.ToolCall;
import com.thinkingcoding.model.ToolExecution;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.tools.ContextAwareTool;

/**
 * 默认的工具执行引擎实现。
 * 负责工具解析、参数转换、执行和结果格式化。
 */
public class DefaultToolExecutionEngine implements ToolExecutionEngine {

    private final ThinkingCodingContext context;
    private final ToolResolver toolResolver;
    private final ToolResultFormatter resultFormatter;

    public DefaultToolExecutionEngine(ThinkingCodingContext context) {
        this.context = context;
        this.toolResolver = new ToolResolver();
        this.resultFormatter = new ToolResultFormatter();
    }

    @Override
    public ToolExecutionOutcome execute(
            ToolCall call,
            TurnContext turn,
            ToolConfirmationPolicy confirmationPolicy,
            AgentEventSink events,
            SteeringHandle steering
    ) {
        try {
            // 1. 创建 ToolExecution 对象用于确认
            ToolExecution execution = new ToolExecution(
                    call.getToolName(),
                    call.getDescription() != null ? call.getDescription() : "执行工具操作",
                    call.getParameters(),
                    true
            );

            // 2. 通过 Policy 决策是否执行（Steering 门禁）
            ToolDecision decision = confirmationPolicy.decide(execution, steering);

            if (decision == ToolDecision.DISCARD) {
                // 用户拒绝执行
                context.getUi().displayWarning("⏭️  操作已取消: " + call.getToolName());
                
                // 返回未执行的 outcome
                ChatMessage cancelMessage = new ChatMessage("system",
                        "Tool execution cancelled by user for: " + call.getToolName());
                
                return new ToolExecutionOutcome(call, null, cancelMessage, false);
            }

            // 3. 通知事件系统：工具计划执行
            events.onToolPlanned(call);

            // 4. 解析工具（仅原生工具名）
            ToolResolver.ResolvedTool resolved = toolResolver.resolve(call, context.getToolRegistry());
            BaseTool tool = resolved.tool();

            if (tool == null) {
                String errorMsg = "Tool not found: " + call.getToolName();
                context.getUi().displayError("❌ " + errorMsg);

                ChatMessage errorMessage = new ChatMessage("system", errorMsg);
                return new ToolExecutionOutcome(call, null, errorMessage, false);
            }

            // 5. 执行工具
            String arguments = resultFormatter.convertParametersToJson(resolved.parameters());
            ToolResult result;
            if (tool instanceof ContextAwareTool contextAwareTool) {
                contextAwareTool.setTurnContext(turn);
                try {
                    result = tool.execute(arguments);
                } finally {
                    contextAwareTool.clearTurnContext();
                }
            } else {
                result = tool.execute(arguments);
            }

            // 6. 处理执行结果
            if (result.isSuccess()) {
                context.getUi().displaySuccess("✅ 完成");

                // 显示输出（如果有）
                String output = result.getOutput();
                if (output != null && !output.trim().isEmpty()) {
                    String[] lines = output.trim().split("\n");
                    for (String line : lines) {
                        context.getUi().getTerminal().writer().println("  " + line);
                    }
                    context.getUi().getTerminal().writer().flush();
                }

                // 格式化为 history message
                String formattedResult = resultFormatter.formatSuccess(call, result);
                ChatMessage successMessage = new ChatMessage("system", formattedResult);

                // 通知事件系统：工具执行成功
                ToolExecutionOutcome outcome = new ToolExecutionOutcome(call, result, successMessage, true);
                events.onToolExecuted(outcome);

                return outcome;

            } else {
                // 执行失败
                String errorMsg = result.getError();
                context.getUi().displayError("❌ 失败: " + errorMsg);

                String formattedError = resultFormatter.formatFailure(call, errorMsg);
                ChatMessage errorMessage = new ChatMessage("system", formattedError);

                ToolExecutionOutcome outcome = new ToolExecutionOutcome(call, result, errorMessage, true);
                events.onToolExecuted(outcome);

                return outcome;
            }

        } catch (Exception e) {
            // 异常处理
            String errorMsg = "Tool execution exception: " + e.getMessage();
            context.getUi().displayError("❌ 执行异常: " + e.getMessage());

            ChatMessage exceptionMessage = new ChatMessage("system", errorMsg);
            ToolExecutionOutcome outcome = new ToolExecutionOutcome(call, null, exceptionMessage, false);
            events.onToolExecuted(outcome);

            return outcome;
        }
    }
}
