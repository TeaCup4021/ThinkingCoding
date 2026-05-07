package com.thinkingcoding.agentloop.v2;

import com.thinkingcoding.agentloop.v2.model.TodoStatus;
import com.thinkingcoding.agentloop.v2.model.TodoTracker;
import com.thinkingcoding.agentloop.v2.model.TurnContext;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.tools.todo.AgentTodoTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TodoTrackerTest {

    @Test
    public void testTodoTrackerReminderRendering() {
        TodoTracker tracker = new TodoTracker();
        tracker.addTodo("task-1", "分析项目结构", TodoStatus.DONE);
        tracker.addTodo("task-2", "更新依赖版本", TodoStatus.IN_PROGRESS);
        tracker.addTodo("task-3", "运行测试验证", TodoStatus.PENDING);

        String reminder = tracker.renderSystemReminder();
        assertNotNull(reminder);
        assertTrue(reminder.contains("<system-reminder>"));
        assertTrue(reminder.contains("你当前的 TODO"));
        assertTrue(reminder.contains("[x] 分析项目结构"));
        assertTrue(reminder.contains("IN_PROGRESS"));
        assertTrue(reminder.contains("运行测试验证"));
    }

    @Test
    public void testAgentTodoToolLifecycle() {
        List<ChatMessage> history = new ArrayList<>();
        TurnContext turnContext = new TurnContext("session-1", "test-model", history, 0);

        AgentTodoTool tool = new AgentTodoTool();
        tool.setTurnContext(turnContext);

        ToolResult addResult = tool.execute("{\"action\":\"add\",\"content\":\"确认构建方式\",\"status\":\"IN_PROGRESS\"}");
        assertTrue(addResult.isSuccess());
        assertTrue(turnContext.getTodoTracker().renderSystemReminder().contains("确认构建方式"));

        String taskId = turnContext.getTodoTracker().getItems().get(0).getId();
        ToolResult completeResult = tool.execute("{\"action\":\"complete\",\"id\":\"" + taskId + "\"}");
        assertTrue(completeResult.isSuccess());
        assertTrue(turnContext.getTodoTracker().renderSystemReminder().contains("[x]"));

        turnContext.getTodoTracker().resetWithContents(List.of("确认构建方式", "更新依赖版本"));
        String reminderAfterReset = turnContext.getTodoTracker().renderSystemReminder();
        assertTrue(reminderAfterReset.contains("[x] 确认构建方式"));
        assertTrue(reminderAfterReset.contains("更新依赖版本"));

        ToolResult clearResult = tool.execute("{\"action\":\"clear\"}");
        assertTrue(clearResult.isSuccess());
        assertEquals("", turnContext.getTodoTracker().renderSystemReminder());

        tool.clearTurnContext();
    }
}

