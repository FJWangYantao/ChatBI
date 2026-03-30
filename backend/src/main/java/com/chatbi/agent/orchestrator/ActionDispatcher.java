package com.chatbi.agent.orchestrator;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.NextAction;

public interface ActionDispatcher {
    ActionResult dispatch(AgentContext context, ExecutionState state, NextAction action);
}
