package com.thinkingcoding.agentloop.v2.model;

public class TodoItem {
    private final String id;
    private String content;
    private TodoStatus status;

    public TodoItem(String id, String content, TodoStatus status) {
        this.id = id;
        this.content = content;
        this.status = status == null ? TodoStatus.PENDING : status;
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public void setStatus(TodoStatus status) {
        if (status != null) {
            this.status = status;
        }
    }
}

