package com.chatbi.agent.orchestrator;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.NextAction;

public interface ActionGuard {
    boolean allow(AgentContext context, ExecutionState state, NextAction action);
}
