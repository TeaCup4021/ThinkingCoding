package com.thinkingcoding.tools.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.agentloop.v2.model.TodoItem;
import com.thinkingcoding.agentloop.v2.model.TodoStatus;
import com.thinkingcoding.agentloop.v2.model.TodoTracker;
import com.thinkingcoding.agentloop.v2.model.TurnContext;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.tools.ContextAwareTool;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AgentTodoTool extends BaseTool implements ContextAwareTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TurnContext turnContext;

    public AgentTodoTool() {
        super("manage_todo", "管理结构化 TODO 列表，用于复杂任务的规划推进");
    }

    @Override
    public ToolResult execute(String input) {
        if (turnContext == null) {
            return error("TODO 工具缺少 TurnContext");
        }

        Map<String, Object> params = parseInput(input);
        String action = readString(params, "action");
        String id = readString(params, "id");
        String content = readString(params, "content");
        String statusRaw = readString(params, "status");

        if (action == null || action.isBlank()) {
            return error("缺少 action 参数");
        }

        TodoTracker tracker = turnContext.getTodoTracker();
        switch (action.toLowerCase(Locale.ROOT)) {
            case "add":
                if (content == null || content.isBlank()) {
                    return error("add 动作需要 content 参数");
                }
                TodoStatus status = parseStatus(statusRaw, TodoStatus.PENDING);
                TodoItem added = tracker.addTodo(id, content.trim(), status);
                return success("TODO 已添加: id=" + added.getId() + ", status=" + added.getStatus());
            case "update":
                if (id == null || id.isBlank()) {
                    return error("update 动作需要 id 参数");
                }
                TodoStatus updatedStatus = parseStatus(statusRaw, null);
                if (updatedStatus == null) {
                    return error("update 动作需要 status 参数");
                }
                if (!tracker.updateStatus(id.trim(), updatedStatus)) {
                    return error("未找到对应 TODO: " + id);
                }
                return success("TODO 已更新: id=" + id + ", status=" + updatedStatus);
            case "complete":
                if (id == null || id.isBlank()) {
                    return error("complete 动作需要 id 参数");
                }
                if (!tracker.updateStatus(id.trim(), TodoStatus.DONE)) {
                    return error("未找到对应 TODO: " + id);
                }
                return success("TODO 已完成: id=" + id);
            case "clear":
                tracker.clear();
                return success("TODO 已清空");
            default:
                return error("不支持的 action: " + action);
        }
    }

    @Override
    public String getCategory() {
        return "agent";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Object getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("description", "管理 TODO 列表，用于复杂任务的分解和推进");

        Map<String, Object> properties = new HashMap<>();
        properties.put("action", enumProperty(List.of("add", "update", "complete", "clear"), "操作类型"));
        properties.put("id", stringProperty("任务 ID（update/complete 时必填）"));
        properties.put("content", stringProperty("任务内容（add 时必填）"));
        properties.put("status", enumProperty(List.of("PENDING", "IN_PROGRESS", "DONE"), "任务状态（update 时必填）"));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void setTurnContext(TurnContext turnContext) {
        this.turnContext = turnContext;
    }

    @Override
    public void clearTurnContext() {
        this.turnContext = null;
    }

    private Map<String, Object> parseInput(String input) {
        if (input == null || input.isBlank()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(input, Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("content", input.trim());
            return fallback;
        }
    }

    private String readString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private TodoStatus parseStatus(String raw, TodoStatus fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return TodoStatus.valueOf(normalized);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> enumProperty(List<String> values, String description) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        schema.put("description", description);
        return schema;
    }
}

