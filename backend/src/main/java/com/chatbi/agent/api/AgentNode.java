package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.SceneType;

import java.util.Map;

public interface AgentNode {
    String name();

    boolean supports(SceneType sceneType);

    ActionResult execute(AgentContext context, ExecutionState state, Map<String, Object> input);
}
