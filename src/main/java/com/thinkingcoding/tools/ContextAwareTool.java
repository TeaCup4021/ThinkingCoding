package com.thinkingcoding.tools;

import com.thinkingcoding.agentloop.v2.model.TurnContext;

public interface ContextAwareTool {
    void setTurnContext(TurnContext turnContext);
    void clearTurnContext();
}

