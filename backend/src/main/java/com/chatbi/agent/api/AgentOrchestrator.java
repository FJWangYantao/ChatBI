package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.model.ActionResult;

public interface AgentOrchestrator {
    ActionResult execute(AgentContext context);
}
