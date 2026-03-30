package com.chatbi.service.planning;

import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.NextAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlanningActionMapper {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public PlanningActionMapper() {
        this(new ObjectMapper());
    }

    public PlanningActionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NextAction toNextAction(AssistantMessage.ToolCall toolCall) {
        Map<String, Object> input = parseMap(toolCall.arguments());
        return NextAction.toolCall(
                toolCall.name(),
                input,
                "planning tool call",
                toolCall.name() + " result"
        );
    }

    public ActionResult toActionResult(NextAction action, String toolResult) {
        Map<String, Object> payload = parseMap(toolResult);
        Object error = payload.get("error");
        if (error instanceof String errorText && !errorText.isBlank()) {
            return ActionResult.failure(action.getTarget(), errorText);
        }

        Map<String, Object> output = new LinkedHashMap<>(payload);
        output.remove("success");

        Map<String, Object> artifacts = new LinkedHashMap<>();
        moveIfPresent(output, artifacts, "data_ref_id");

        return ActionResult.success(action.getTarget(), output, artifacts);
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", json);
        }
    }

    private void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.remove(key));
        }
    }
}
