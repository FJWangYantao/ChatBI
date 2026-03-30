package com.chatbi.service.planning;

import com.chatbi.context.LLMConfigContext;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.StreamingTagEvent;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class PlanningRoundRunner {

    public static final int MAX_CONTEXT_TOKENS = 131072;

    private final DynamicChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final List<FunctionCallback> functionCallbacks;
    private final Function<String, String> generatingStatusResolver;

    public PlanningRoundRunner(DynamicChatClientFactory chatClientFactory,
                               ObjectMapper objectMapper,
                               HttpClient httpClient,
                               List<FunctionCallback> functionCallbacks,
                               Function<String, String> generatingStatusResolver) {
        this.chatClientFactory = chatClientFactory;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.functionCallbacks = functionCallbacks;
        this.generatingStatusResolver = generatingStatusResolver;
    }

    public RoundResult streamOneRound(List<Message> messages,
                                      OpenAiChatOptions options,
                                      Consumer<String> onTextDelta,
                                      Consumer<StreamingTagEvent> onTagEvent,
                                      Consumer<String> onStatusChange) {
        LLMConfigContext.LLMConfig customConfig = LLMConfigContext.get();
        if (customConfig == null) {
            throw new IllegalStateException("未检测到前端 LLM 配置，请先在设置中配置 LLM 提供商和 API Key");
        }

        String effectiveApiKey = customConfig.getApiKey();
        String effectiveBaseUrl = customConfig.getBaseUrl() != null
                ? customConfig.getBaseUrl()
                : getDefaultBaseUrl(customConfig.getProvider());
        String model = customConfig.getModelName();
        double temperature = options.getTemperature() != null ? options.getTemperature() : 0.1;

        log.info("[PlanningRoundRunner] Using model={}, baseUrl={}, provider={}",
                model, effectiveBaseUrl, customConfig.getProvider());

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("stream", true);
            body.put("max_tokens", 8192);
            body.set("messages", convertMessagesToOpenAI(messages));
            body.set("tools", buildToolsArray());

            String requestBody = objectMapper.writeValueAsString(body);
            String url = effectiveBaseUrl.replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .timeout(java.time.Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[PlanningRoundRunner] API returned error: statusCode={}, body={}",
                        response.statusCode(), truncateString(errBody, 500));
                if (response.statusCode() == 400 && errBody.contains("maximum context length")) {
                    throw new TokenLimitExceededException(
                            "上下文长度超过模型限制(" + MAX_CONTEXT_TOKENS + " tokens)");
                }
                throw new RuntimeException("API returned " + response.statusCode());
            }

            return parseSseStream(response.body(), onTextDelta, onTagEvent, onStatusChange);
        } catch (TokenLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[PlanningRoundRunner] Raw streaming failed, fallback to Spring AI: {}, type={}",
                    truncateString(e.getMessage(), 200), e.getClass().getSimpleName(), e);
            return fallbackStreamRound(messages, options, onTextDelta);
        }
    }

    public RoundResult parseSseStream(InputStream inputStream,
                                      Consumer<String> onTextDelta,
                                      Consumer<StreamingTagEvent> onTagEvent,
                                      Consumer<String> onStatusChange) throws Exception {
        StringBuilder textBuffer = new StringBuilder();
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();
        Map<Integer, ToolArgCodeExtractor> codeExtractors = new HashMap<>();
        Map<Integer, String> codeTagIds = new HashMap<>();
        Map<Integer, Boolean> tagStartSent = new HashMap<>();
        int dataChunkCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (SseEmitterContext.isDisconnected()) {
                    log.warn("[PlanningRoundRunner] SSE disconnected, stop parsing");
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }

                dataChunkCount++;
                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choices = chunk.get("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) {
                    continue;
                }

                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    String text = contentNode.asText();
                    if (!text.isEmpty()) {
                        textBuffer.append(text);
                        try {
                            onTextDelta.accept(text);
                        } catch (Exception e) {
                            log.debug("text callback failed: {}", e.getMessage());
                        }
                    }
                }

                JsonNode toolCallsNode = delta.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        int idx = tcNode.has("index") ? tcNode.get("index").asInt() : 0;
                        if (tcNode.has("id")) {
                            tcIds.put(idx, tcNode.get("id").asText());
                        }
                        JsonNode fnNode = tcNode.get("function");
                        if (fnNode == null) {
                            continue;
                        }
                        if (fnNode.has("name")) {
                            String toolName = fnNode.get("name").asText();
                            if (!tcNames.containsKey(idx)) {
                                try {
                                    onStatusChange.accept(generatingStatusResolver.apply(toolName));
                                } catch (Exception e) {
                                    log.debug("status callback failed: {}", e.getMessage());
                                }
                            }
                            tcNames.put(idx, toolName);
                        }
                        if (fnNode.has("arguments")) {
                            String argDelta = fnNode.get("arguments").asText();
                            if (!argDelta.isEmpty()) {
                                tcArgs.computeIfAbsent(idx, key -> new StringBuilder()).append(argDelta);
                                handleCodeStreaming(idx, argDelta, tcNames, codeExtractors,
                                        codeTagIds, tagStartSent, onTagEvent);
                            }
                        }
                    }
                }
            }
        }

        if (dataChunkCount == 0) {
            log.warn("[PlanningRoundRunner] No valid SSE data chunk received");
        }

        for (Map.Entry<Integer, String> entry : codeTagIds.entrySet()) {
            int idx = entry.getKey();
            String tagId = entry.getValue();
            String argsJson = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "";
            String fullCode = extractCodeFromArgs(argsJson);
            if (fullCode != null && onTagEvent != null) {
                try {
                    onTagEvent.accept(StreamingTagEvent.end(tagId, "code", "Python 代码", fullCode));
                } catch (Exception e) {
                    log.debug("tag end callback failed: {}", e.getMessage());
                }
            }
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Integer idx : tcIds.keySet()) {
            String id = tcIds.get(idx);
            String name = tcNames.getOrDefault(idx, "");
            String args = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "";
            if (!name.isEmpty()) {
                toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, args));
            }
        }

        return new RoundResult(textBuffer.toString(), toolCalls);
    }

    private void handleCodeStreaming(int idx,
                                     String argDelta,
                                     Map<Integer, String> tcNames,
                                     Map<Integer, ToolArgCodeExtractor> codeExtractors,
                                     Map<Integer, String> codeTagIds,
                                     Map<Integer, Boolean> tagStartSent,
                                     Consumer<StreamingTagEvent> onTagEvent) {
        if (!"execute_code".equals(tcNames.get(idx)) || onTagEvent == null) {
            return;
        }

        ToolArgCodeExtractor extractor = codeExtractors.computeIfAbsent(idx, key -> new ToolArgCodeExtractor());
        String codeDelta = extractor.feed(argDelta);
        if (codeDelta == null) {
            return;
        }

        String tagId = codeTagIds.computeIfAbsent(idx,
                key -> "code-" + UUID.randomUUID().toString().substring(0, 8));
        if (!tagStartSent.containsKey(idx)) {
            tagStartSent.put(idx, true);
            try {
                onTagEvent.accept(StreamingTagEvent.start(tagId, "code", "Python 代码"));
            } catch (Exception e) {
                log.debug("tag start callback failed: {}", e.getMessage());
            }
        }
        try {
            onTagEvent.accept(StreamingTagEvent.delta(tagId, codeDelta));
        } catch (Exception e) {
            log.debug("tag delta callback failed: {}", e.getMessage());
        }
    }

    private RoundResult fallbackStreamRound(List<Message> messages,
                                            OpenAiChatOptions options,
                                            Consumer<String> onTextDelta) {
        log.info("[PlanningRoundRunner] Using Spring AI stream fallback, messagesCount={}", messages.size());

        ChatClient dynamicClient = chatClientFactory.createChatClient("planning");
        Prompt prompt = new Prompt(messages, options);
        StringBuilder textBuffer = new StringBuilder();
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();

        try {
            Flux<ChatResponse> stream = dynamicClient.prompt(prompt).stream().chatResponse();
            stream.doOnNext(response -> {
                if (response == null || response.getResult() == null) {
                    return;
                }
                AssistantMessage msg = response.getResult().getOutput();
                if (msg == null) {
                    return;
                }
                String text = msg.getContent();
                if (text != null && !text.isEmpty()) {
                    textBuffer.append(text);
                    try {
                        onTextDelta.accept(text);
                    } catch (Exception ignored) {
                    }
                }
                if (msg.hasToolCalls()) {
                    for (int i = 0; i < msg.getToolCalls().size(); i++) {
                        AssistantMessage.ToolCall tc = msg.getToolCalls().get(i);
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            tcIds.put(i, tc.id());
                        }
                        if (tc.name() != null && !tc.name().isEmpty()) {
                            tcNames.put(i, tc.name());
                        }
                        if (tc.arguments() != null && !tc.arguments().isEmpty()) {
                            tcArgs.computeIfAbsent(i, key -> new StringBuilder()).append(tc.arguments());
                        }
                    }
                }
            }).blockLast();
        } catch (Exception e) {
            log.warn("[PlanningRoundRunner] Spring AI stream fallback failed: {}, type={}",
                    truncateString(e.getMessage(), 200), e.getClass().getSimpleName(), e);
            return fallbackCallRound(messages, options, onTextDelta);
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Integer idx : tcIds.keySet()) {
            String id = tcIds.get(idx);
            String name = tcNames.getOrDefault(idx, "");
            String args = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "";
            if (!name.isEmpty()) {
                toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, args));
            }
        }
        return new RoundResult(textBuffer.toString(), toolCalls);
    }

    private RoundResult fallbackCallRound(List<Message> messages,
                                          OpenAiChatOptions options,
                                          Consumer<String> onTextDelta) {
        ChatClient dynamicClient = chatClientFactory.createChatClient("planning");
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = dynamicClient.prompt(prompt).call().chatResponse();
        Generation generation = response.getResult();
        AssistantMessage msg = generation.getOutput();

        String text = msg.getContent() != null ? msg.getContent() : "";
        if (!text.isEmpty()) {
            onTextDelta.accept(text);
        }

        List<AssistantMessage.ToolCall> toolCalls = msg.hasToolCalls() ? msg.getToolCalls() : List.of();
        return new RoundResult(text, toolCalls);
    }

    private String extractCodeFromArgs(String argsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            Object code = args.get("code");
            return code != null ? code.toString() : null;
        } catch (Exception e) {
            log.debug("parse tool args failed: {}", e.getMessage());
            return null;
        }
    }

    private ArrayNode convertMessagesToOpenAI(List<Message> messages) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Message msg : messages) {
            if (msg instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
                arr.add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", sm.getContent()));
            } else if (msg instanceof org.springframework.ai.chat.messages.UserMessage um) {
                arr.add(objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", um.getContent()));
            } else if (msg instanceof AssistantMessage am) {
                ObjectNode node = objectMapper.createObjectNode()
                        .put("role", "assistant");
                if (am.getContent() != null && !am.getContent().isEmpty()) {
                    node.put("content", am.getContent());
                }
                if (am.hasToolCalls()) {
                    ArrayNode tcArr = objectMapper.createArrayNode();
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        tcArr.add(objectMapper.createObjectNode()
                                .put("id", tc.id())
                                .put("type", "function")
                                .set("function", objectMapper.createObjectNode()
                                        .put("name", tc.name())
                                        .put("arguments", tc.arguments())));
                    }
                    node.set("tool_calls", tcArr);
                }
                arr.add(node);
            } else if (msg instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    arr.add(objectMapper.createObjectNode()
                            .put("role", "tool")
                            .put("tool_call_id", tr.id())
                            .put("content", tr.responseData()));
                }
            }
        }
        return arr;
    }

    private ArrayNode buildToolsArray() {
        ArrayNode tools = objectMapper.createArrayNode();
        for (FunctionCallback fc : functionCallbacks) {
            try {
                ObjectNode fn = objectMapper.createObjectNode()
                        .put("name", fc.getName())
                        .put("description", fc.getDescription());
                JsonNode schema = objectMapper.readTree(fc.getInputTypeSchema());
                fn.set("parameters", schema);
                tools.add(objectMapper.createObjectNode()
                        .put("type", "function")
                        .set("function", fn));
            } catch (Exception e) {
                log.warn("[PlanningRoundRunner] build tool schema failed: {}", fc.getName(), e);
            }
        }
        return tools;
    }

    private String truncateString(String s, int maxLen) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }

    private String getDefaultBaseUrl(String provider) {
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    @Getter
    public static class RoundResult {
        private final String fullText;
        private final List<AssistantMessage.ToolCall> toolCalls;
        private final boolean hasToolCalls;

        public RoundResult(String fullText, List<AssistantMessage.ToolCall> toolCalls) {
            this.fullText = fullText;
            this.toolCalls = toolCalls;
            this.hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean hasToolCalls() {
            return hasToolCalls;
        }
    }

    public static class TokenLimitExceededException extends RuntimeException {
        public TokenLimitExceededException(String message) {
            super(message);
        }
    }

    private static class ToolArgCodeExtractor {
        private final StringBuilder args = new StringBuilder();
        private int scanPos = 0;
        private boolean inCodeValue = false;
        private boolean done = false;
        private boolean prevBackslash = false;

        String feed(String chunk) {
            if (done || chunk == null || chunk.isEmpty()) {
                return null;
            }
            args.append(chunk);

            String all = args.toString();
            StringBuilder delta = new StringBuilder();

            while (scanPos < all.length()) {
                char c = all.charAt(scanPos);
                if (!inCodeValue) {
                    String rest = all.substring(scanPos);
                    int keyIdx = rest.indexOf("\"code\"");
                    if (keyIdx < 0) {
                        scanPos = Math.max(scanPos, all.length() - 10);
                        break;
                    }
                    int afterKey = scanPos + keyIdx + 6;
                    int colonIdx = -1;
                    for (int i = afterKey; i < all.length(); i++) {
                        char ch = all.charAt(i);
                        if (ch == ':') {
                            colonIdx = i;
                            break;
                        }
                        if (ch != ' ' && ch != '\t') {
                            break;
                        }
                    }
                    if (colonIdx < 0) {
                        break;
                    }

                    int quoteIdx = -1;
                    for (int i = colonIdx + 1; i < all.length(); i++) {
                        char ch = all.charAt(i);
                        if (ch == '"') {
                            quoteIdx = i;
                            break;
                        }
                        if (ch != ' ' && ch != '\t') {
                            break;
                        }
                    }
                    if (quoteIdx < 0) {
                        break;
                    }

                    inCodeValue = true;
                    scanPos = quoteIdx + 1;
                    prevBackslash = false;
                    continue;
                }

                if (prevBackslash) {
                    prevBackslash = false;
                    switch (c) {
                        case '"' -> delta.append('"');
                        case '\\' -> delta.append('\\');
                        case 'n' -> delta.append('\n');
                        case 't' -> delta.append('\t');
                        case 'r' -> delta.append('\r');
                        case '/' -> delta.append('/');
                        default -> delta.append('\\').append(c);
                    }
                    scanPos++;
                } else if (c == '\\') {
                    prevBackslash = true;
                    scanPos++;
                    if (scanPos >= all.length()) {
                        break;
                    }
                } else if (c == '"') {
                    done = true;
                    scanPos++;
                    break;
                } else {
                    delta.append(c);
                    scanPos++;
                }
            }

            return delta.length() > 0 ? delta.toString() : null;
        }
    }
}
