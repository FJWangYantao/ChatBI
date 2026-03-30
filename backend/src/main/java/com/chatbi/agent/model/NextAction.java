package com.chatbi.agent.model;

import java.util.Map;

public class NextAction {

    private final ActionType actionType;
    private final String target;
    private final Map<String, Object> input;
    private final String reason;
    private final String expectedOutput;

    private NextAction(ActionType actionType, String target, Map<String, Object> input,
                       String reason, String expectedOutput) {
        this.actionType = actionType;
        this.target = target;
        this.input = input;
        this.reason = reason;
        this.expectedOutput = expectedOutput;
    }

    public static NextAction toolCall(String target, Map<String, Object> input,
                                      String reason, String expectedOutput) {
        return new NextAction(ActionType.CALL_TOOL, target, input, reason, expectedOutput);
    }

    public static NextAction agentCall(String target, Map<String, Object> input,
                                       String reason, String expectedOutput) {
        return new NextAction(ActionType.CALL_AGENT, target, input, reason, expectedOutput);
    }

    public static NextAction respond(String reason, String expectedOutput) {
        return new NextAction(ActionType.RESPOND, null, Map.of(), reason, expectedOutput);
    }

    public static NextAction stop(String reason) {
        return new NextAction(ActionType.STOP, null, Map.of(), reason, null);
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getTarget() {
        return target;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public String getReason() {
        return reason;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }
}
