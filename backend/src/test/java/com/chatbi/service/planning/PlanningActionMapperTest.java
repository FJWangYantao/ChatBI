package com.chatbi.service.planning;

import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.ActionType;
import com.chatbi.agent.model.NextAction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningActionMapperTest {

    private final PlanningActionMapper mapper = new PlanningActionMapper();

    @Test
    void shouldConvertToolCallToNextAction() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1",
                "function",
                "query_database",
                "{\"sql\":\"select 1\"}"
        );

        NextAction action = mapper.toNextAction(toolCall);

        assertThat(action.getActionType()).isEqualTo(ActionType.CALL_TOOL);
        assertThat(action.getTarget()).isEqualTo("query_database");
        assertThat(action.getInput()).containsEntry("sql", "select 1");
    }

    @Test
    void shouldConvertSuccessfulToolJsonToActionResult() {
        NextAction action = NextAction.toolCall(
                "query_database",
                java.util.Map.of("sql", "select 1"),
                "llm requested tool",
                "query result"
        );

        ActionResult result = mapper.toActionResult(
                action,
                "{\"success\":true,\"data_ref_id\":\"ref_1\",\"row_count\":3}"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResultType()).isEqualTo("query_database");
        assertThat(result.getOutput()).containsEntry("row_count", 3);
        assertThat(result.getArtifacts()).containsEntry("data_ref_id", "ref_1");
    }

    @Test
    void shouldConvertErrorToolJsonToFailureActionResult() {
        NextAction action = NextAction.toolCall(
                "execute_code",
                java.util.Map.of("code", "print(1)"),
                "llm requested tool",
                "code result"
        );

        ActionResult result = mapper.toActionResult(
                action,
                "{\"error\":\"tool failed\"}"
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResultType()).isEqualTo("execute_code");
        assertThat(result.getError()).isEqualTo("tool failed");
    }
}
