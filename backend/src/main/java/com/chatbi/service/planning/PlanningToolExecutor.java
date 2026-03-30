package com.chatbi.service.planning;

import com.chatbi.agent.model.NextAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.Map;

@Slf4j
public class PlanningToolExecutor {

    private final Map<String, FunctionCallback> toolMap;

    public PlanningToolExecutor(Map<String, FunctionCallback> toolMap) {
        this.toolMap = toolMap;
    }

    public String execute(NextAction action, String rawArguments) {
        String name = action.getTarget();
        log.info("[PlanningToolExecutor] Executing tool: {} with args length: {}",
                name, rawArguments != null ? rawArguments.length() : 0);

        FunctionCallback callback = toolMap.get(name);
        if (callback == null) {
            log.warn("[PlanningToolExecutor] Unknown tool: {}", name);
            return "{\"error\": \"unknown tool: " + name + "\"}";
        }

        try {
            return callback.call(rawArguments);
        } catch (Exception e) {
            log.error("[PlanningToolExecutor] Tool {} failed: {}", name, e.getMessage(), e);
            return "{\"error\": \"tool execution failed: " + e.getMessage() + "\"}";
        }
    }
}
