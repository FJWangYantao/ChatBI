package com.chatbi.agent.context;

import com.chatbi.agent.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStateTest {

    @Test
    void shouldTrackStatusAndBudgets() {
        ExecutionState state = ExecutionState.initial();
        state.setStatus(ExecutionStatus.RUNNING);
        state.setToolBudget(6);

        assertThat(state.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(state.getToolBudget()).isEqualTo(6);
    }
}
