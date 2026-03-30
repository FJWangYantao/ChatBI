package com.chatbi.service.planning;

import com.chatbi.agent.model.NextAction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningToolExecutorTest {

    @Test
    void shouldExecuteKnownTool() {
        FunctionCallback callback = mock(FunctionCallback.class);
        when(callback.call("{\"sql\":\"select 1\"}")).thenReturn("{\"success\":true}");

        PlanningToolExecutor executor = new PlanningToolExecutor(Map.of("query_database", callback));
        NextAction action = NextAction.toolCall(
                "query_database",
                Map.of("sql", "select 1"),
                "planning tool call",
                "query result"
        );

        String result = executor.execute(action, "{\"sql\":\"select 1\"}");

        assertThat(result).isEqualTo("{\"success\":true}");
    }

    @Test
    void shouldReturnErrorJsonWhenToolIsMissing() {
        PlanningToolExecutor executor = new PlanningToolExecutor(Map.of());
        NextAction action = NextAction.toolCall(
                "missing_tool",
                Map.of(),
                "planning tool call",
                "missing result"
        );

        String result = executor.execute(action, "{}");

        assertThat(result).contains("error");
        assertThat(result).contains("missing_tool");
    }

    @Test
    void shouldReturnErrorJsonWhenToolThrows() {
        FunctionCallback callback = mock(FunctionCallback.class);
        when(callback.call("{}")).thenThrow(new RuntimeException("boom"));

        PlanningToolExecutor executor = new PlanningToolExecutor(Map.of("execute_code", callback));
        NextAction action = NextAction.toolCall(
                "execute_code",
                Map.of(),
                "planning tool call",
                "code result"
        );

        String result = executor.execute(action, "{}");

        assertThat(result).contains("error");
        assertThat(result).contains("boom");
    }
}
