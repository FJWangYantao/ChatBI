package com.chatbi.agent.context;

import com.chatbi.agent.model.SceneType;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class AgentContext {
    private final String requestId;
    private final String conversationId;
    private final String userMessage;
    private final SceneType sceneType;
    private final CancelToken cancelToken;
    private final Map<String, Object> metadata;
}
