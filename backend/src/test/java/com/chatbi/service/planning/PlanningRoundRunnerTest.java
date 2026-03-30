package com.chatbi.service.planning;

import com.chatbi.context.LLMConfigContext;
import com.chatbi.dto.StreamingTagEvent;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningRoundRunnerTest {

    @AfterEach
    void clearContext() {
        LLMConfigContext.clear();
    }

    @Test
    void shouldParseTextAndToolCallsFromSseStream() throws Exception {
        PlanningRoundRunner runner = new PlanningRoundRunner(
                mock(DynamicChatClientFactory.class),
                new ObjectMapper(),
                mock(HttpClient.class),
                List.of(),
                toolName -> "generating " + toolName
        );

        List<String> textDeltas = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"content":"hello "}}]}
                data: {"choices":[{"delta":{"content":"world"}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"query_database","arguments":"{\\"sql\\":\\"select "}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1\\"}"}}]}}]}
                data: [DONE]
                """;

        PlanningRoundRunner.RoundResult result = runner.parseSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                textDeltas::add,
                event -> {
                },
                statuses::add
        );

        assertThat(result.getFullText()).isEqualTo("hello world");
        assertThat(textDeltas).containsExactly("hello ", "world");
        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).name()).isEqualTo("query_database");
        assertThat(result.getToolCalls().get(0).arguments()).isEqualTo("{\"sql\":\"select 1\"}");
        assertThat(statuses).containsExactly("generating query_database");
    }

    @Test
    void shouldEmitStreamingCodeTagsForExecuteCodeToolCall() throws Exception {
        PlanningRoundRunner runner = new PlanningRoundRunner(
                mock(DynamicChatClientFactory.class),
                new ObjectMapper(),
                mock(HttpClient.class),
                List.of(),
                toolName -> "generating " + toolName
        );

        List<StreamingTagEvent> tagEvents = new ArrayList<>();
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"execute_code","arguments":"{\\"code\\":\\"print(1)"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\\nprint(2)\\"}"}}]}}]}
                data: [DONE]
                """;

        PlanningRoundRunner.RoundResult result = runner.parseSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                text -> {
                },
                tagEvents::add,
                status -> {
                }
        );

        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(tagEvents).hasSize(4);
        assertThat(tagEvents.get(0).eventType()).isEqualTo("start");
        assertThat(tagEvents.get(1).eventType()).isEqualTo("delta");
        assertThat(tagEvents.get(1).delta()).isEqualTo("print(1)");
        assertThat(tagEvents.get(2).eventType()).isEqualTo("delta");
        assertThat(tagEvents.get(2).delta()).isEqualTo("\nprint(2)");
        assertThat(tagEvents.get(3).eventType()).isEqualTo("end");
        assertThat(tagEvents.get(3).content()).isEqualTo("print(1)\nprint(2)");
    }

    @Test
    void shouldFallbackToSynchronousCallWhenRawAndStreamBothFail() throws Exception {
        DynamicChatClientFactory chatClientFactory = mock(DynamicChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        HttpClient httpClient = mock(HttpClient.class);

        when(chatClientFactory.createChatClient("planning")).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.error(new RuntimeException("stream failed")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("fallback answer"))
        )));
        when(httpClient.send(any(), any())).thenThrow(new RuntimeException("raw failed"));

        LLMConfigContext.LLMConfig config = new LLMConfigContext.LLMConfig();
        config.setProvider("openai");
        config.setApiKey("test-key");
        config.setModelName("test-model");
        config.setBaseUrl("https://example.test/v1");
        LLMConfigContext.set(config);

        PlanningRoundRunner runner = new PlanningRoundRunner(
                chatClientFactory,
                new ObjectMapper(),
                httpClient,
                List.<FunctionCallback>of(),
                toolName -> "generating " + toolName
        );

        List<String> textDeltas = new ArrayList<>();
        PlanningRoundRunner.RoundResult result = runner.streamOneRound(
                List.<Message>of(new UserMessage("hello")),
                OpenAiChatOptions.builder().build(),
                textDeltas::add,
                event -> {
                },
                status -> {
                }
        );

        assertThat(result.getFullText()).isEqualTo("fallback answer");
        assertThat(result.hasToolCalls()).isFalse();
        assertThat(textDeltas).containsExactly("fallback answer");
    }
}
