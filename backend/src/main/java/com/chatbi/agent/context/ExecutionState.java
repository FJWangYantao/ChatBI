package com.chatbi.agent.context;

import com.chatbi.agent.model.ExecutionStatus;

public class ExecutionState {

    private ExecutionStatus status;
    private int toolBudget;
    private int tokenBudget;
    private int retryCount;
    private final ExecutionTrace trace;

    private ExecutionState() {
        this.status = ExecutionStatus.PENDING;
        this.trace = new ExecutionTrace();
    }

    public static ExecutionState initial() {
        return new ExecutionState();
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public int getToolBudget() {
        return toolBudget;
    }

    public void setToolBudget(int toolBudget) {
        this.toolBudget = toolBudget;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public ExecutionTrace getTrace() {
        return trace;
    }
}
