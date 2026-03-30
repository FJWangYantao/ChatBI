package com.chatbi.agent.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NextActionTest {

    @Test
    void shouldCreateToolActionWithStructuredFields() {
        NextAction action = NextAction.toolCall(
                "query_database",
                Map.of("sql", "select 1"),
                "need raw data",
                "query result"
        );

        assertThat(action.getActionType()).isEqualTo(ActionType.CALL_TOOL);
        assertThat(action.getTarget()).isEqualTo("query_database");
        assertThat(action.getInput()).containsEntry("sql", "select 1");
        assertThat(action.getReason()).isEqualTo("need raw data");
        assertThat(action.getExpectedOutput()).isEqualTo("query result");
    }
}
