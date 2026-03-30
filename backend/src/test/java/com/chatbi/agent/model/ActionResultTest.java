package com.chatbi.agent.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionResultTest {

    @Test
    void shouldCreateSuccessfulResultWithArtifacts() {
        ActionResult result = ActionResult.success(
                "tool_result",
                Map.of("rows", 3),
                Map.of("dataRefId", "ref_1")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResultType()).isEqualTo("tool_result");
        assertThat(result.getOutput()).containsEntry("rows", 3);
        assertThat(result.getArtifacts()).containsEntry("dataRefId", "ref_1");
    }
}
