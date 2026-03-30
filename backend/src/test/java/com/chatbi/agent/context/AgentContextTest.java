package com.chatbi.agent.context;

import com.chatbi.agent.model.SceneType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTest {

    @Test
    void shouldKeepRequestScopedMetadata() {
        CancelToken cancelToken = new CancelToken();
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .conversationId("conv-1")
                .userMessage("分析最近销售趋势")
                .sceneType(SceneType.ANALYSIS)
                .cancelToken(cancelToken)
                .build();

        assertThat(context.getRequestId()).isEqualTo("req-1");
        assertThat(context.getSceneType()).isEqualTo(SceneType.ANALYSIS);
        assertThat(context.getCancelToken()).isSameAs(cancelToken);
    }
}
