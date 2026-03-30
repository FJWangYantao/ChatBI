package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.model.SceneType;

public interface AgentRouter {
    SceneType route(AgentContext context);
}
