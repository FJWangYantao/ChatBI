package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.SceneType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContractsTest {

    @Test
    void shouldAllowNodeToDeclareSceneSupport() {
        AgentNode node = new AgentNode() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public boolean supports(SceneType sceneType) {
                return sceneType == SceneType.ANALYSIS;
            }

            @Override
            public ActionResult execute(AgentContext context, ExecutionState state, Map<String, Object> input) {
                return ActionResult.success("demo", Map.of(), Map.of());
            }
        };

        assertThat(node.supports(SceneType.ANALYSIS)).isTrue();
        assertThat(node.supports(SceneType.KNOWLEDGE)).isFalse();
    }
}
