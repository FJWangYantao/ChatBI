package com.chatbi.agent.model;

import java.util.Map;

public class ActionResult {

    private final boolean success;
    private final String resultType;
    private final Map<String, Object> output;
    private final Map<String, Object> artifacts;
    private final String error;

    private ActionResult(boolean success, String resultType, Map<String, Object> output,
                         Map<String, Object> artifacts, String error) {
        this.success = success;
        this.resultType = resultType;
        this.output = output;
        this.artifacts = artifacts;
        this.error = error;
    }

    public static ActionResult success(String resultType, Map<String, Object> output,
                                       Map<String, Object> artifacts) {
        return new ActionResult(true, resultType, output, artifacts, null);
    }

    public static ActionResult failure(String resultType, String error) {
        return new ActionResult(false, resultType, Map.of(), Map.of(), error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getResultType() {
        return resultType;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public Map<String, Object> getArtifacts() {
        return artifacts;
    }

    public String getError() {
        return error;
    }
}
