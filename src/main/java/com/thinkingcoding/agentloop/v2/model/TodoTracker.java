package com.thinkingcoding.agentloop.v2.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO 跟踪器，管理当前的 TODO 列表和状态。
 */
public class TodoTracker {
    // TODO 项列表，线程安全访问
    private final List<TodoItem> items = new ArrayList<>();
    // ID 生成器，确保每个 TODO 项有唯一 ID
    private final AtomicInteger counter = new AtomicInteger(1);
    // 已完成任务内容缓存，避免重复完成
    private final Set<String> completedContents = new HashSet<>();

    public synchronized TodoItem addTodo(String content, TodoStatus status) {
        return addTodo(null, content, status);
    }

    public synchronized TodoItem addTodo(String id, String content, TodoStatus status) {
        String normalizedContent = content == null ? "" : content.trim();
        String resolvedId = (id == null || id.isBlank()) ? generateId() : ensureUniqueId(id.trim());
        TodoStatus resolvedStatus = status;
        if (!normalizedContent.isBlank() && completedContents.contains(normalizedContent)) {
            resolvedStatus = TodoStatus.DONE;
        }
        TodoItem item = new TodoItem(resolvedId, normalizedContent, resolvedStatus);
        items.add(item);
        return item;
    }

    public synchronized boolean updateStatus(String id, TodoStatus status) {
        if (id == null || id.isBlank() || status == null) {
            return false;
        }
        for (TodoItem item : items) {
            if (id.equals(item.getId())) {
                item.setStatus(status);
                if (status == TodoStatus.DONE && item.getContent() != null && !item.getContent().isBlank()) {
                    completedContents.add(item.getContent().trim());
                }
                return true;
            }
        }
        return false;
    }

    public synchronized void resetWithContents(List<String> contents) {
        items.clear();
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (String content : contents) {
            if (content == null || content.isBlank()) {
                continue;
            }
            addTodo(null, content, TodoStatus.PENDING);
        }
    }

    public synchronized List<TodoItem> getItems() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized String renderSystemReminder() {
        if (items.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<system-reminder>\n");
        builder.append("你当前的 TODO：\n");

        boolean hinted = false;
        for (TodoItem item : items) {
            String marker = item.getStatus() == TodoStatus.DONE ? "[x]" : "[ ]";
            builder.append("- ").append(marker).append(" ").append(item.getContent());

            if (item.getStatus() == TodoStatus.IN_PROGRESS) {
                builder.append(" (IN_PROGRESS)");
                if (!hinted) {
                    builder.append(" <- 你现在应该在关注这个");
                    hinted = true;
                }
            }

            builder.append("\n");
        }

        builder.append("</system-reminder>");
        return builder.toString();
    }

    private String generateId() {
        return "task-" + counter.getAndIncrement();
    }

    private String ensureUniqueId(String baseId) {
        boolean exists = items.stream().anyMatch(item -> baseId.equals(item.getId()));
        if (!exists) {
            return baseId;
        }
        return baseId + "-" + counter.getAndIncrement();
    }
}

