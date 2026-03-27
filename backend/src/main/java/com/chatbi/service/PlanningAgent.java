package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.context.LLMConfigContext;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.NERResponse;
import com.chatbi.dto.StreamingTagEvent;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanningAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ChatModel chatModel;
    private final ModelOptionsProvider modelOptions;
    private final NERService nerService;
    private final ReadSchemaStructureService schemaService;
    private final FunctionCallback executeCodeFunction;
    private final FunctionCallback fixCodeFunction;
    private final FunctionCallback validateCodeFunction;
    private final FunctionCallback sandboxInfoFunction;
    private final FunctionCallback queryDatabaseFunction;
    private final FunctionCallback dispatchParallelTasksFunction;
    private final Map<String, FunctionCallback> toolMap;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = buildHttpClient();

    private static HttpClient buildHttpClient() {
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))))
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
        }
        return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    // Token 限制相关常量
    private static final int MAX_CONTEXT_TOKENS = 131072;
    private static final int TOKEN_SAFETY_MARGIN = 15000; // 预留给 tools 定义 + 模型输出
    private static final int TARGET_TOKEN_LIMIT = MAX_CONTEXT_TOKENS - TOKEN_SAFETY_MARGIN;
    private static final double CHARS_PER_TOKEN = 2.0; // 保守估算（中文约1.5-2字符/token）

    private static class TokenLimitExceededException extends RuntimeException {
        TokenLimitExceededException(String msg) { super(msg); }
    }

    public PlanningAgent(DynamicChatClientFactory chatClientFactory,
                         ChatModel chatModel,
                         ModelOptionsProvider modelOptions,
                         NERService nerService,
                         ReadSchemaStructureService schemaService,
                         @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction,
                         @Qualifier("fixCodeFunction") FunctionCallback fixCodeFunction,
                         @Qualifier("validateCodeFunction") FunctionCallback validateCodeFunction,
                         @Qualifier("sandboxInfoFunction") FunctionCallback sandboxInfoFunction,
                         @Qualifier("queryDatabaseFunction") FunctionCallback queryDatabaseFunction,
                         @Qualifier("dispatchParallelTasksFunction") FunctionCallback dispatchParallelTasksFunction) {
        this.chatClientFactory = chatClientFactory;
        this.chatModel = chatModel;
        this.modelOptions = modelOptions;
        this.nerService = nerService;
        this.schemaService = schemaService;
        this.executeCodeFunction = executeCodeFunction;
        this.fixCodeFunction = fixCodeFunction;
        this.validateCodeFunction = validateCodeFunction;
        this.sandboxInfoFunction = sandboxInfoFunction;
        this.queryDatabaseFunction = queryDatabaseFunction;
        this.dispatchParallelTasksFunction = dispatchParallelTasksFunction;

        // 构建工具名称到回调的映射
        this.toolMap = new HashMap<>();
        toolMap.put("query_database", queryDatabaseFunction);
        toolMap.put("execute_code", executeCodeFunction);
        toolMap.put("fix_code", fixCodeFunction);
        toolMap.put("validate_code", validateCodeFunction);
        toolMap.put("sandbox_info", sandboxInfoFunction);
        toolMap.put("dispatch_parallel_tasks", dispatchParallelTasksFunction);
        log.info("[PlanningAgent] 已注册工具: {}", toolMap.keySet());
    }

    /**
     * 工具名称 → 状态描述（LLM 生成参数阶段）
     */
    private static String getGeneratingStatus(String toolName) {
        return switch (toolName) {
            case "execute_code" -> "生成Python代码中...";
            case "query_database" -> "生成SQL中...";
            case "fix_code" -> "生成修复方案中...";
            case "validate_code" -> "生成验证方案中...";
            default -> "准备调用工具中...";
        };
    }

    /**
     * 工具名称 → 状态描述（工具实际执行阶段）
     */
    private static String getExecutingStatus(String toolName) {
        return switch (toolName) {
            case "execute_code" -> "执行Python代码中...";
            case "query_database" -> "查询数据库中...";
            case "fix_code" -> "修复代码中...";
            case "validate_code" -> "验证代码中...";
            case "sandbox_info" -> "获取环境信息中...";
            case "dispatch_parallel_tasks" -> "分发并行任务中...";
            default -> "执行工具中...";
        };
    }

    /**
     * 流式版本：手动实现 function calling 循环 + 流式读取
     * @param question 用户问题
     * @param onTextDelta 文本 token 实时回调
     * @param onTagEvent 流式 tag 事件回调
     * @param onStatusChange 状态变更回调，发送当前执行阶段描述
     */
    public String planWithToolsStreaming(
            String question,
            Consumer<String> onTextDelta,
            Consumer<StreamingTagEvent> onTagEvent,
            Consumer<String> onStatusChange) {

        log.info("[PlanningAgent] Planning with tools (streaming) for: {}", question);

        // 分离静态内容（system）和动态内容（user），system message 可命中 prompt caching
        String systemPrompt = buildSystemPrompt();
        String userContent = buildUserContent(question);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userContent));

        // 构建带工具定义的 options（proxyToolCalls=true 阻止自动执行）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withFunctionCallbacks(List.of(
                        queryDatabaseFunction, executeCodeFunction, fixCodeFunction,
                        validateCodeFunction, sandboxInfoFunction, dispatchParallelTasksFunction))
                .withProxyToolCalls(true)
                .build();

        int maxRounds = 10;
        try {
            for (int round = 0; round < maxRounds; round++) {
                // 检查客户端是否已断开连接，提前终止避免浪费资源
                if (SseEmitterContext.isDisconnected()) {
                    log.warn("[PlanningAgent] 客户端已断开连接，终止 function calling 循环 (round {})", round + 1);
                    return "";
                }

                log.info("[PlanningAgent] Round {} starting", round + 1);

                // 发送思考状态
                onStatusChange.accept("思考中...");

                // 注入轮次标记，ReasoningFilter 会检测并处理
                onTextDelta.accept("<!-- ROUND_" + (round + 1) + " -->");

                // 发送前检查 token 估算，必要时压缩历史消息
                trimMessagesIfNeeded(messages);

                // 流式调用 LLM（同时拦截 execute_code 的代码参数进行流式发送）
                RoundResult result = streamOneRound(messages, options, onTextDelta, onTagEvent, onStatusChange);

                if (!result.hasToolCalls) {
                    // 最终文本回复，已通过 onTextDelta 实时转发
                    log.info("[PlanningAgent] Streaming complete, response length: {}",
                            result.fullText.length());
                    return result.fullText;
                }

                // 将 assistant 消息（含 tool calls）加入历史
                messages.add(new AssistantMessage(
                        result.fullText,
                        Map.of(),
                        result.toolCalls));

                // 手动执行工具，并瘦身响应再加入历史
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : result.toolCalls) {
                    // 检查客户端是否已断开
                    if (SseEmitterContext.isDisconnected()) {
                        log.info("[PlanningAgent] 客户端已断开，停止工具执行");
                        return "";
                    }

                    // 发送工具执行状态
                    onStatusChange.accept(getExecutingStatus(toolCall.name()));

                    String toolResult = executeToolManually(toolCall);
                    String slimResult = slimToolResponse(toolCall.name(), toolResult);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), slimResult));
                }
                messages.add(new ToolResponseMessage(toolResponses));
                log.info("[PlanningAgent] Round {} completed,  tools executed",
                        round + 1, result.toolCalls.size());
            }
        } catch (TokenLimitExceededException e) {
            log.warn("[PlanningAgent] Token 超限，返回友好提示: {}", e.getMessage());
            String hint = "抱歉，本次分析过程中产生的上下文信息过多，超出了模型处理能力。请尝试：\n"
                    + "1. 简化您的问题，减少分析步骤\n"
                    + "2. 将复杂问题拆分为多个小问题分别提问";
            onTextDelta.accept(hint);
            return hint;
        } catch (Exception e) {
            log.error("[PlanningAgent] Streaming function calling failed: {}",
                    truncateString(e.getMessage(), 300));
            throw e;
        }

        return "分析超过最大轮次限制，请简化您的问题。";
    }

    /**
     * 单轮流式 LLM 调用结果
     */
    private static class RoundResult {
        final String fullText;
        final List<AssistantMessage.ToolCall> toolCalls;
        final boolean hasToolCalls;

        RoundResult(String fullText, List<AssistantMessage.ToolCall> toolCalls) {
            this.fullText = fullText;
            this.toolCalls = toolCalls;
            this.hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 从工具调用的 JSON 参数流中实时提取 "code" 字段内容。
     * 处理 JSON 转义序列（\n, \t, \\, \" 等），逐块返回解码后的代码文本。
     */
    private static class ToolArgCodeExtractor {
        private final StringBuilder args = new StringBuilder();
        private int scanPos = 0;
        private boolean inCodeValue = false;
        private boolean done = false;
        private boolean prevBackslash = false;

        /**
         * 喂入新的参数 chunk，返回解码后的代码增量（无新内容则返回 null）
         */
        String feed(String chunk) {
            if (done || chunk == null || chunk.isEmpty()) return null;
            args.append(chunk);

            String all = args.toString();
            StringBuilder delta = new StringBuilder();

            while (scanPos < all.length()) {
                char c = all.charAt(scanPos);

                if (!inCodeValue) {
                    // 查找 "code" : " 模式
                    String rest = all.substring(scanPos);
                    int keyIdx = rest.indexOf("\"code\"");
                    if (keyIdx < 0) {
                        // 还没找到 key，跳到末尾附近（保留几个字符防止截断匹配）
                        scanPos = Math.max(scanPos, all.length() - 10);
                        break;
                    }
                    // 找到 "code"，定位冒号和开引号
                    int afterKey = scanPos + keyIdx + 6;
                    int colonIdx = -1;
                    for (int i = afterKey; i < all.length(); i++) {
                        char ch = all.charAt(i);
                        if (ch == ':') { colonIdx = i; break; }
                        if (ch != ' ' && ch != '\t') break;
                    }
                    if (colonIdx < 0) break;

                    int quoteIdx = -1;
                    for (int i = colonIdx + 1; i < all.length(); i++) {
                        char ch = all.charAt(i);
                        if (ch == '"') { quoteIdx = i; break; }
                        if (ch != ' ' && ch != '\t') break;
                    }
                    if (quoteIdx < 0) break;

                    inCodeValue = true;
                    scanPos = quoteIdx + 1;
                    prevBackslash = false;
                    continue;
                }

                // 在 code 值内部 — 提取并解码内容
                if (prevBackslash) {
                    prevBackslash = false;
                    switch (c) {
                        case '"':  delta.append('"'); break;
                        case '\\': delta.append('\\'); break;
                        case 'n':  delta.append('\n'); break;
                        case 't':  delta.append('\t'); break;
                        case 'r':  delta.append('\r'); break;
                        case '/':  delta.append('/'); break;
                        default:   delta.append('\\').append(c); break;
                    }
                    scanPos++;
                } else if (c == '\\') {
                    prevBackslash = true;
                    scanPos++;
                    if (scanPos >= all.length()) break; // 需要更多数据
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

    /**
     * 从完整的工具调用 JSON 参数中提取 code 字段值
     */
    private String extractCodeFromArgs(String argsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            Object code = args.get("code");
            return code != null ? code.toString() : null;
        } catch (Exception e) {
            log.debug("解析工具参数失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 Spring AI 消息列表转换为 OpenAI API 格式的 JSON 数组
     */
    private ArrayNode convertMessagesToOpenAI(List<Message> messages) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Message msg : messages) {
            if (msg instanceof SystemMessage sm) {
                arr.add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", sm.getContent()));
            } else if (msg instanceof UserMessage um) {
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
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    arr.add(objectMapper.createObjectNode()
                            .put("role", "tool")
                            .put("tool_call_id", tr.id())
                            .put("content", tr.responseData()));
                }
            }
        }
        return arr;
    }

    /**
     * 从 FunctionCallback 列表构建 OpenAI tools 数组
     */
    private ArrayNode buildToolsArray() {
        ArrayNode tools = objectMapper.createArrayNode();
        for (FunctionCallback fc : List.of(queryDatabaseFunction, executeCodeFunction,
                fixCodeFunction, validateCodeFunction, sandboxInfoFunction, dispatchParallelTasksFunction)) {
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
                log.warn("构建工具定义失败: {}", fc.getName(), e);
            }
        }
        return tools;
    }

    /**
     * 单轮流式 LLM 调用：绕过 Spring AI，直接用 HttpClient 调 OpenAI 兼容 API。
     * 这样可以拿到逐 token 的工具调用参数，实现 Python 代码的真正流式输出。
     */
    private RoundResult streamOneRound(
            List<Message> messages,
            OpenAiChatOptions options,
            Consumer<String> onTextDelta,
            Consumer<StreamingTagEvent> onTagEvent,
            Consumer<String> onStatusChange) {

        // 必须使用前端传递的配置
        LLMConfigContext.LLMConfig customConfig = LLMConfigContext.get();
        if (customConfig == null) {
            throw new IllegalStateException("未检测到前端 LLM 配置，请先在设置中配置 LLM 供应商和 API Key");
        }

        String effectiveApiKey = customConfig.getApiKey();
        String effectiveBaseUrl = customConfig.getBaseUrl() != null
                ? customConfig.getBaseUrl() : getDefaultBaseUrl(customConfig.getProvider());

        // log.info("[PlanningAgent] 规范化后 Base URL: {}", effectiveBaseUrl);

        String model = customConfig.getModelName();
        double temperature = options.getTemperature() != null ? options.getTemperature() : 0.1;

        log.info("[PlanningAgent] 使用前端配置 - Model: {}, BaseURL: {}, Provider: {}",
                model, effectiveBaseUrl, customConfig.getProvider());

        try {
            // 构建请求体
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("stream", true);
            body.put("max_tokens", 8192); // 设置 max_tokens，避免工具调用参数被截断（不超过模型限制）
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

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[PlanningAgent] API 返回错误: statusCode={}, body={}",
                        response.statusCode(), truncateString(errBody, 500));
                // Token 超限快速失败，不走 fallback（fallback 也会 400）
                if (response.statusCode() == 400
                        && errBody.contains("maximum context length")) {
                    log.warn("[PlanningAgent] Token 超限: {}",
                            truncateString(errBody, 200));
                    throw new TokenLimitExceededException(
                            "上下文长度超过模型限制 (" + MAX_CONTEXT_TOKENS + " tokens)");
                }
                log.error("[PlanningAgent] Raw API error {}: {}",
                        response.statusCode(), truncateString(errBody, 500));
                throw new RuntimeException("API returned " + response.statusCode());
            }

            return parseSSEStream(response.body(), onTextDelta, onTagEvent, onStatusChange);

        } catch (TokenLimitExceededException e) {
            log.error("[PlanningAgent] Token 超限异常", e);
            throw e; // Token 超限直接抛出，不走 fallback
        } catch (Exception e) {
            log.warn("[PlanningAgent] Raw streaming failed, falling back to Spring AI: {}, exceptionType={}",
                    truncateString(e.getMessage(), 200), e.getClass().getSimpleName(), e);
            return fallbackStreamRound(messages, options, onTextDelta);
        }
    }

    /**
     * 解析 SSE 流，提取文本 delta 和工具调用参数（逐 token）
     */
    private RoundResult parseSSEStream(
            java.io.InputStream inputStream,
            Consumer<String> onTextDelta,
            Consumer<StreamingTagEvent> onTagEvent,
            Consumer<String> onStatusChange) throws Exception {

        StringBuilder textBuffer = new StringBuilder();
        // 工具调用累积：index -> {id, name, argsBuilder}
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();
        // 代码流式提取
        Map<Integer, ToolArgCodeExtractor> codeExtractors = new HashMap<>();
        Map<Integer, String> codeTagIds = new HashMap<>();
        Map<Integer, Boolean> tagStartSent = new HashMap<>();

        int dataChunkCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {

                // 检查客户端是否已断开连接
                if (SseEmitterContext.isDisconnected()) {
                    log.warn("[PlanningAgent] ⚠ 检测到客户端断开，停止 SSE 流解析，已累积文本长度={}", textBuffer.length());
                    break;
                }

                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    log.info("[PlanningAgent] 收到 [DONE] 标记，流结束");
                    break;
                }
                if (data.isEmpty()) continue;

                dataChunkCount++;
                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choices = chunk.get("choices");
                if (choices == null || choices.isEmpty()) continue;

                // 检测 finish_reason，诊断截断问题
                JsonNode finishReasonNode = choices.get(0).get("finish_reason");
                if (finishReasonNode != null && !finishReasonNode.isNull()) {
                    String finishReason = finishReasonNode.asText();
                    if ("length".equals(finishReason)) {
                        log.warn("[PlanningAgent] ⚠ LLM 输出因 max_tokens 限制被截断！finish_reason=length, 已累积文本长度={}", textBuffer.length());
                    } else {
                        log.info("[PlanningAgent] 流结束，finish_reason={}, 文本长度={}", finishReason, textBuffer.length());
                    }
                }

                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) continue;

                // 文本内容
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    String text = contentNode.asText();
                    if (!text.isEmpty()) {
                        textBuffer.append(text);
                        try { onTextDelta.accept(text); }
                        catch (Exception e) { log.debug("文本回调失败: {}", e.getMessage()); }
                    }
                }

                // 工具调用 delta
                JsonNode toolCallsNode = delta.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        int idx = tcNode.has("index") ? tcNode.get("index").asInt() : 0;

                        if (tcNode.has("id")) {
                            tcIds.put(idx, tcNode.get("id").asText());
                        }
                        JsonNode fnNode = tcNode.get("function");
                        if (fnNode != null) {
                            if (fnNode.has("name")) {
                                String toolName = fnNode.get("name").asText();
                                // 首次检测到工具名称时，发送"生成中"状态
                                if (!tcNames.containsKey(idx)) {
                                    try { onStatusChange.accept(getGeneratingStatus(toolName)); }
                                    catch (Exception e) { log.debug("状态回调失败: {}", e.getMessage()); }
                                }
                                tcNames.put(idx, toolName);
                            }
                            if (fnNode.has("arguments")) {
                                String argDelta = fnNode.get("arguments").asText();
                                if (!argDelta.isEmpty()) {
                                    tcArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                            .append(argDelta);
                                    // 拦截 execute_code，流式提取 code 字段
                                    handleCodeStreaming(idx, argDelta, tcNames,
                                            codeExtractors, codeTagIds, tagStartSent, onTagEvent);
                                }
                            }
                        }
                    }
                }
            }
        }


        if (dataChunkCount == 0) {
            log.warn("[PlanningAgent] 警告：SSE 流中没有有效的 data 块，可能是格式不兼容");
        }

        // 发送 tag_end
        for (Map.Entry<Integer, String> entry : codeTagIds.entrySet()) {
            int idx = entry.getKey();
            String tagId = entry.getValue();
            String argsJson = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "";
            String fullCode = extractCodeFromArgs(argsJson);
            if (fullCode != null && onTagEvent != null) {
                try {
                    onTagEvent.accept(StreamingTagEvent.end(tagId, "code", "Python 代码", fullCode));
                } catch (Exception e) {
                    log.debug("tag_end 回调失败: {}", e.getMessage());
                }
            }
        }

        // 组装工具调用列表
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

    /**
     * 处理 execute_code 工具的代码流式输出
     */
    private void handleCodeStreaming(
            int idx, String argDelta,
            Map<Integer, String> tcNames,
            Map<Integer, ToolArgCodeExtractor> codeExtractors,
            Map<Integer, String> codeTagIds,
            Map<Integer, Boolean> tagStartSent,
            Consumer<StreamingTagEvent> onTagEvent) {

        if (!"execute_code".equals(tcNames.get(idx)) || onTagEvent == null) return;

        ToolArgCodeExtractor extractor = codeExtractors
                .computeIfAbsent(idx, k -> new ToolArgCodeExtractor());
        String codeDelta = extractor.feed(argDelta);
        if (codeDelta == null) return;

        String tagId = codeTagIds.computeIfAbsent(idx,
                k -> "code-" + UUID.randomUUID().toString().substring(0, 8));
        if (!tagStartSent.containsKey(idx)) {
            tagStartSent.put(idx, true);
            try {
                onTagEvent.accept(StreamingTagEvent.start(tagId, "code", "Python 代码"));
            } catch (Exception e) {
                log.debug("tag_start 失败: {}", e.getMessage());
            }
        }
        try {
            onTagEvent.accept(StreamingTagEvent.delta(tagId, codeDelta));
        } catch (Exception e) {
            log.debug("tag_delta 失败: {}", e.getMessage());
        }
    }

    /**
     * 降级：使用 Spring AI 的 chatModel.stream()（原始 HTTP 流失败时）
     */
    private RoundResult fallbackStreamRound(
            List<Message> messages,
            OpenAiChatOptions options,
            Consumer<String> onTextDelta) {

        log.info("[PlanningAgent] Using Spring AI stream fallback, messagesCount={}", messages.size());

        // 使用前端配置创建动态 ChatClient（配置从 LLMConfigContext 自动获取）
        ChatClient dynamicClient = chatClientFactory.createChatClient("planning");

        Prompt prompt = new Prompt(messages, options);
        StringBuilder textBuffer = new StringBuilder();
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();

        try {
            dynamicClient.prompt(prompt).stream().chatResponse().doOnNext(response -> {
                if (response == null || response.getResult() == null) return;
                AssistantMessage msg = response.getResult().getOutput();
                if (msg == null) return;
                String text = msg.getContent();
                if (text != null && !text.isEmpty()) {
                    textBuffer.append(text);
                    try { onTextDelta.accept(text); }
                    catch (Exception e) { /* ignore */ }
                }
                if (msg.hasToolCalls()) {
                    for (int i = 0; i < msg.getToolCalls().size(); i++) {
                        AssistantMessage.ToolCall tc = msg.getToolCalls().get(i);
                        if (tc.id() != null && !tc.id().isEmpty()) tcIds.put(i, tc.id());
                        if (tc.name() != null && !tc.name().isEmpty()) tcNames.put(i, tc.name());
                        if (tc.arguments() != null && !tc.arguments().isEmpty())
                            tcArgs.computeIfAbsent(i, k -> new StringBuilder()).append(tc.arguments());
                    }
                }
            }).blockLast();

            log.info("[PlanningAgent] Spring AI stream 完成: textLength={}, toolCallsCount={}",
                    textBuffer.length(), tcIds.size());
        } catch (Exception e) {
            log.warn("[PlanningAgent] Spring AI stream also failed: {}, exceptionType={}",
                    truncateString(e.getMessage(), 200), e.getClass().getSimpleName(), e);
            return fallbackCallRound(messages, options, onTextDelta);
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Integer idx : tcIds.keySet()) {
            String id = tcIds.get(idx);
            String name = tcNames.getOrDefault(idx, "");
            String args = tcArgs.containsKey(idx) ? tcArgs.get(idx).toString() : "";
            if (!name.isEmpty()) toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, args));
        }
        return new RoundResult(textBuffer.toString(), toolCalls);
    }

    /**
     * 降级：同步调用（流式失败时使用）
     */
    private RoundResult fallbackCallRound(
            List<Message> messages,
            OpenAiChatOptions options,
            Consumer<String> onTextDelta) {
        log.warn("[PlanningAgent] Falling back to synchronous call");

        // 使用前端配置创建动态 ChatClient（配置从 LLMConfigContext 自动获取）
        ChatClient dynamicClient = chatClientFactory.createChatClient("planning");

        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = dynamicClient.prompt(prompt).call().chatResponse();
        Generation gen = response.getResult();
        AssistantMessage msg = gen.getOutput();

        String text = msg.getContent() != null ? msg.getContent() : "";
        if (!text.isEmpty()) {
            onTextDelta.accept(text);
        }

        List<AssistantMessage.ToolCall> toolCalls = msg.hasToolCalls()
                ? msg.getToolCalls() : List.of();
        return new RoundResult(text, toolCalls);
    }

    /**
     * 手动执行工具调用
     */
    private String executeToolManually(AssistantMessage.ToolCall toolCall) {
        String name = toolCall.name();
        String args = toolCall.arguments();
        log.info("[PlanningAgent] 调用工具: {} with args length: {}",
                name, args != null ? args.length() : 0);

        FunctionCallback callback = toolMap.get(name);
        if (callback == null) {
            log.warn("[PlanningAgent] Unknown tool: {}", name);
            return "{\"error\": \"未知工具: " + name + "\"}";
        }

        try {
            return callback.call(args);
        } catch (Exception e) {
            log.error("[PlanningAgent] Tool {} failed: {}",
                    name, e.getMessage(), e);
            return "{\"error\": \"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 估算消息列表的 token 数（字符数 / CHARS_PER_TOKEN）
     */
    private int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            if (msg instanceof SystemMessage sm) {
                totalChars += sm.getContent().length();
            } else if (msg instanceof UserMessage um) {
                totalChars += um.getContent().length();
            } else if (msg instanceof AssistantMessage am) {
                totalChars += am.getContent() != null ? am.getContent().length() : 0;
                if (am.hasToolCalls()) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        totalChars += tc.arguments() != null ? tc.arguments().length() : 0;
                    }
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    totalChars += tr.responseData() != null ? tr.responseData().length() : 0;
                }
            }
        }
        return (int) (totalChars / CHARS_PER_TOKEN);
    }

    /**
     * 发送前检查 token 估算，超限时压缩中间轮次的 tool response。
     * 保留 SystemMessage + UserMessage + 最近一轮对话，压缩中间轮次。
     */
    private void trimMessagesIfNeeded(List<Message> messages) {
        int estimated = estimateTokens(messages);
        if (estimated <= TARGET_TOKEN_LIMIT) return;

        log.warn("[PlanningAgent] 估算 token 数 {} 超过限制 {}，开始压缩中间消息",
                estimated, TARGET_TOKEN_LIMIT);

        // 从第二条消息开始（跳过首条 UserMessage），到倒数第二条（保留最近一轮）
        // 对中间的 ToolResponseMessage 进行深度压缩
        int end = messages.size() - 2; // 保留最后两条（assistant + tool response）
        for (int i = 1; i < end && i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage trm)) continue;

            List<ToolResponseMessage.ToolResponse> compressed = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                String data = tr.responseData();
                if (data == null || data.length() < 200) {
                    compressed.add(tr);
                    continue;
                }
                compressed.add(new ToolResponseMessage.ToolResponse(
                        tr.id(), tr.name(), compressToolResponseData(tr.name(), data)));
            }
            messages.set(i, new ToolResponseMessage(compressed));
        }

        int after = estimateTokens(messages);
        log.info("[PlanningAgent] 压缩后估算 token 数: {} (压缩前: {})", after, estimated);
    }

    /**
     * 深度压缩工具响应数据：只保留元信息，去掉大块数据
     */
    private String compressToolResponseData(String toolName, String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            if (!(root instanceof ObjectNode obj)) return truncateString(data, 500);

            if ("query_database".equals(toolName)) {
                // 只保留元信息，去掉 data_preview
                obj.remove("data_preview");
                if (obj.has("row_count") && obj.has("columns")) {
                    obj.put("data_preview", "[已压缩，共" + obj.get("row_count").asInt() + "行]");
                }
            } else if ("execute_code".equals(toolName)) {
                if (obj.has("images")) obj.put("images", "[已压缩]");
                if (obj.has("stdout")) {
                    String stdout = obj.get("stdout").asText();
                    if (stdout.length() > 500) {
                        obj.put("stdout", stdout.substring(0, 500) + "...[已压缩]");
                    }
                }
            }
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return truncateString(data, 500);
        }
    }

    private String truncateString(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[已截断]";
    }

    /**
     * 工具响应瘦身：在加入消息历史前裁剪过大的工具返回
     * - execute_code: 去掉 images 的 base64（已通过 SSE 发到前端），截断超长 stdout
     * - query_database: data_preview 缩减到10行
     */
    private String slimToolResponse(String toolName, String toolResult) {
        if (toolResult == null || toolResult.length() < 500) return toolResult;
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if (!(root instanceof ObjectNode obj)) return toolResult;

            if ("execute_code".equals(toolName)) {
                // 去掉 images 的 base64 数据（已通过 SSE 实时发送到前端）
                if (obj.has("images")) {
                    JsonNode images = obj.get("images");
                    if (images.isArray() && !images.isEmpty()) {
                        obj.put("images", "[已发送到前端，共" + images.size() + "张图]");
                    }
                }
                // 截断超长 stdout
                if (obj.has("stdout")) {
                    String stdout = obj.get("stdout").asText();
                    if (stdout.length() > 2000) {
                        obj.put("stdout", stdout.substring(0, 2000)
                                + "\n...[截断，原始长度" + stdout.length() + "字符]");
                    }
                }
            } else if ("query_database".equals(toolName)) {
                // data_preview 缩减到10行（LLM 只需理解数据结构）
                if (obj.has("data_preview")) {
                    String preview = obj.get("data_preview").asText();
                    String[] lines = preview.split("\n");
                    if (lines.length > 12) { // 表头+分隔+10行数据
                        String trimmed = Arrays.stream(lines, 0, 12)
                                .collect(Collectors.joining("\n"))
                                + "\n...[共" + (lines.length - 2) + "行，已截断]";
                        obj.put("data_preview", trimmed);
                    }
                }
            } else if ("dispatch_parallel_tasks".equals(toolName)) {
                // 子任务结果瘦身：去掉 code 和大块 result（已通过 SSE 实时发送到前端）
                if (obj.has("results") && obj.get("results").isArray()) {
                    ArrayNode results = (ArrayNode) obj.get("results");
                    ArrayNode slim = objectMapper.createArrayNode();
                    for (JsonNode item : results) {
                        ObjectNode s = objectMapper.createObjectNode();
                        s.put("title", item.has("title") ? item.get("title").asText() : "子任务");
                        s.put("success", item.has("success") && item.get("success").asBoolean());
                        if (item.has("error") && !item.get("error").isNull()) {
                            s.put("error", truncateString(item.get("error").asText(), 200));
                        }
                        // result 只保留摘要（stdout 已通过 SSE 发送，code 不需要回传给 LLM）
                        if (item.has("result")) {
                            String r = item.get("result").asText();
                            if (r.length() > 500) {
                                s.put("result", r.substring(0, 500) + "...[已截断]");
                            } else {
                                s.put("result", r);
                            }
                        }
                        slim.add(s);
                    }
                    obj.set("results", slim);
                }
            }
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.debug("工具响应瘦身失败，返回原始结果: {}", e.getMessage());
            return toolResult;
        }
    }

    /**
     * 构建 System Message（包含静态内容：角色定义、工具说明、规则、Schema）
     * 这部分内容在多轮对话中保持不变，可命中 provider 的 prompt caching
     */
    private String buildSystemPrompt() {
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
        return """
            ⚠️ **核心规则（必须严格遵守）**：
            - **严格禁止使用任何模拟数据、硬编码数据、假数据**
            - 所有数据必须通过 query_database 工具从数据库获取
            - 不允许在代码中写死任何数据映射（如 {1: 'Name1', 2: 'Name2'}）
            - 如果需要关联多个表的数据，使用多次 query_database + data_refs 参数

            你是一个数据分析专家，拥有以下工具：

            1. **query_database**: 查询数据库获取数据。传入自然语言描述需要什么数据。
               - 输入: {"data_description": "查询所有2024年的月度销售额"}
               - 返回: data_preview（预览）、data_ref_id（数据引用ID）、columns、row_count
            2. **execute_code**: 在安全沙盒中执行 Python 代码进行数据分析和可视化。
               支持单数据集或多数据集模式：
               - 单数据集: {"code": "...", "data_ref_id": "..."}
                 数据会自动加载为 df 变量
               - 多数据集: {"code": "...", "data_refs": {"orders_df": "ref1", "products_df": "ref2"}}
                 **重要**：data_refs 中的键名（如 orders_df, products_df）就是代码中可以直接使用的变量名
                 例如：data_refs 中定义了 "orders_df": "ref1"，则代码中可以直接使用 orders_df 变量
                 适用于需要关联多个表的分析场景
               - 返回: stdout、stderr、images、code_ref_id（代码引用ID，用于 fix_code）
            3. **fix_code**: 差量修复已执行过的代码。当 execute_code 失败时优先使用此工具，只需传入修改部分。
               支持单数据集和多数据集模式：
               - 单数据集: {"code_ref_id": "...", "data_ref_id": "...", "fixes": [{"old": "错误代码片段", "new": "修正后代码片段"}]}
               - 多数据集: {"code_ref_id": "...", "data_refs": {"df_orders": "ref1", "df_regions": "ref2"}, "fixes": [{"old": "错误代码片段", "new": "修正后代码片段"}]}
               - code_ref_id 使用 execute_code 返回的 code_ref_id
               - data_ref_id 或 data_refs 必须与原始 execute_code 调用时使用的参数一致
               - fixes 数组中每项的 old 是原代码中需要替换的文本，new 是替换后的文本
            4. **validate_code**: 预检代码安全性（通常不需要调用）
            5. **sandbox_info**: 查询沙盒环境能力（通常不需要调用）
            6. **dispatch_parallel_tasks**: 并行执行多个独立的数据分析子任务。
               当你需要对同一份数据做多个独立分析时（如统计摘要+趋势图+排名），
               使用此工具一次性派发，比逐个调用 execute_code 更快。
               - 输入: {"tasks": [{"title": "子任务标题", "description": "详细分析要求", "data_ref_id": "query_database返回的data_ref_id", "needs_chart": true}]}
               - 每个子任务会由独立的 CodeAgent 并行生成代码并执行
               - 返回: 所有子任务的执行结果汇总
               - 注意: 仅当有2个及以上独立分析任务时使用，单个分析直接用 execute_code

            **工具选择指南（按复杂度递增）：**
            - ⚡ 简单查询（最值、排名、计数、求和等）→ 仅 query_database，直接根据返回的 data_preview 回答，**不需要写 Python 代码**
            - 📊 需要可视化或复杂计算（趋势分析、占比分布、多维对比、统计建模等）→ query_database + execute_code
            - 📊 同一数据的多个独立分析 → query_database + dispatch_parallel_tasks
            - 📊 多表关联分析 → 多次 query_database + execute_code（使用 data_refs 参数）

            **判断是否需要写代码的标准：**
            - ✅ 不需要代码：问题可以用一条 SQL 直接得到最终答案（如"哪个产品销售额最高"、"总共有多少订单"、"最近一个月的销售额是多少"）
            - ✅ 不需要代码：query_database 返回的 data_preview 已经包含完整答案（行数少、结构清晰）
            - ❌ 需要代码：需要生成图表/可视化
            - ❌ 需要代码：需要复杂的数据处理（如时间序列分析、同比环比计算、多步聚合）
            - ❌ 需要代码：数据量大需要进一步加工才能回答

            **工作流程：**
            1. 先调用 query_database 获取数据
            2. 查看返回的 data_preview，判断是否已经能直接回答用户问题
            3. **如果 data_preview 已包含答案** → 直接根据数据撰写分析结论，跳过 execute_code
            4. **如果需要进一步分析或可视化** → 编写 Python 代码，调用 execute_code 并传入 data_ref_id
            5. 如需多表关联，先多次调用 query_database 获取各表数据，然后用 data_refs 参数调用 execute_code
            6. 根据结果生成分析总结

            **重要规则：**
            - ⚠️ **严格禁止使用模拟数据、硬编码数据、假数据**：
              * 绝对不允许在代码中写死任何数据（如 shipper_map = {1: 'Speedy Express', ...}）
              * 绝对不允许使用 "模拟"、"假设"、"示例" 等方式创建数据
              * 所有数据必须通过 query_database 工具从数据库获取
              * 如果某些数据无法通过 SQL 直接获取，必须先用 query_database 查询，然后在代码中处理
              * 违反此规则将导致分析结果完全不可信
            - 必须先用 query_database 获取真实数据
            - execute_code 只需传 data_ref_id 或 data_refs，不要传 data_json（数据由服务端自动注入）
            - 如果 query_database 返回 success=false，向用户说明无法获取数据
            - **数据时间范围验证（关键）：**
              * 当用户请求特定时间范围的数据时（如"1997年"），必须先检查数据库中实际的时间范围
              * 如果数据库中的时间范围与用户请求不匹配，**必须立即告知用户**，说明：
                1. 用户请求的时间范围是什么
                2. 数据库中实际可用的时间范围是什么
                3. 询问用户是否要调整分析的时间范围
              * **绝对不要**自作主张地调整时间范围进行分析
              * 示例：用户要求"1997年数据"，但数据库只有2006-2008年，应回复："数据库中没有1997年的数据，实际可用的时间范围是2006-2008年。您是否需要我分析2007年的数据（对应的相对时间位置）？"
            - 如果 execute_code 失败，**必须优先使用 fix_code 工具**修复错误代码（传入 code_ref_id 和 fixes），而不是用 execute_code 重新生成完整代码
              * fix_code 支持单数据集（data_ref_id）和多数据集（data_refs）模式
              * 使用 fix_code 时，data_ref_id 或 data_refs 参数必须与原始 execute_code 调用时完全一致
              * 只有当 code_ref_id 过期或需要完全重写逻辑时才用 execute_code 重试
            - 修复最多 2 次，仍然失败则向用户说明原因
            - Python 代码中数据已自动加载为 df (pandas DataFrame)
            - **输出要求（仅在使用 execute_code 时适用）**：
              1. 如果需要可视化，使用 matplotlib 绘图，设置中文字体: plt.rcParams['font.sans-serif'] = ['SimHei']
              2. 保存图表: plt.savefig('output.png', dpi=100, bbox_inches='tight')
              3. **必须输出分析结果的 JSON 数组**：在代码最后一行添加 print(result_df.to_json(orient='records', force_ascii=False))
              4. **重要**：result_df 必须是你分析后的结果数据（如统计、分组、排名等），不是原始数据 df
              5. **禁止**：不要输出原始数据的列名或 df.columns，必须输出实际的分析结果
            - **不使用 execute_code 时的输出要求**：
              * 直接根据 query_database 返回的 data_preview 数据撰写结论
              * 用自然语言清晰地回答用户问题，引用具体数据（如"销售额最高的产品是XX，销售额为YY元"）
              * 不需要输出 JSON 数组或图表
            - 打印关键统计结果到 stdout
            - DataFrame 输出规范（必须遵守）：
              * 禁止直接 print(df)，pandas 默认会截断列和行显示
              * 输出表格数据时使用 print(df.to_string(index=False))，确保所有列完整显示
              * 如果列数较多（>6列），只选择最关键的列：print(df[['col1','col2']].to_string(index=False))
              * 如果行数较多（>30行），只输出前20行：print(df.head(20).to_string(index=False))，并打印 f"（共{len(df)}行，仅展示前20行）"
              * 统计指标（均值、总计等单个数值）用 "指标名: 值" 格式逐行打印，不要放在 DataFrame 里
              * 每个输出块之前用 === 标题 === 格式打印标题

            **代码示例1（单数据集 - 同时输出图表和 JSON）**:
            ```python
            import pandas as pd
            import matplotlib.pyplot as plt

            # 数据分析（这是关键：对原始数据进行统计、分组、排名等）
            result_df = df.groupby('类别')['数量'].sum().reset_index()
            result_df.columns = ['类别', '总数量']
            # result_df 现在是分析结果，不是原始数据

            # 打印统计结果
            print("=== 分析结果 ===")
            print(result_df.to_string(index=False))

            # 生成图表（可选）
            plt.rcParams['font.sans-serif'] = ['SimHei']
            plt.figure(figsize=(10, 6))
            plt.bar(result_df['类别'], result_df['总数量'])
            plt.title('各类别数量统计')
            plt.xlabel('类别')
            plt.ylabel('总数量')
            plt.savefig('output.png', dpi=100, bbox_inches='tight')

            # 必须输出分析结果的 JSON（不是原始数据）
            print(result_df.to_json(orient='records', force_ascii=False))
            ```

            **代码示例2（多数据集 - 关联多个表）**:
            假设调用 execute_code 时传入：
            {"code": "...", "data_refs": {"orders_df": "ref1", "customers_df": "ref2"}}

            ```python
            import pandas as pd
            import matplotlib.pyplot as plt

            # 多数据集模式：orders_df 和 customers_df 已自动加载
            # 注意：变量名必须与 data_refs 中的键名完全一致

            # 关联两个表
            merged_df = orders_df.merge(customers_df, on='customer_id', how='left')

            # 分析
            result_df = merged_df.groupby('customer_name')['order_amount'].sum().reset_index()
            result_df.columns = ['客户名称', '总订单金额']
            result_df = result_df.sort_values('总订单金额', ascending=False).head(10)

            # 输出结果
            print("=== 前10名客户 ===")
            print(result_df.to_string(index=False))
            print(result_df.to_json(orient='records', force_ascii=False))
            ```

            **错误示例（不要这样做）**:
            ```python
            # 错误：输出原始数据的列名
            print(df.columns.tolist())  # ❌ 这是错的

            # 错误：输出原始数据
            print(df.to_json(orient='records'))  # ❌ 这是错的，应该输出分析结果

            # 正确：输出分析结果
            result_df = df.groupby('类别').size().reset_index(name='数量')
            print(result_df.to_json(orient='records'))  # ✅ 这是对的
            ```

            **沙盒限制：**
            - 仅允许导入：pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64
            - 禁止：exit, eval, exec, compile, open, __import__ 等
            - 执行超时：30 秒

            **ReAct 推理框架（必须遵守）：**
            你的文本输出必须遵循以下结构化格式：

            1. 在**第一次文本回复**中，**立即**以 `<!-- REASONING_START -->` 开头（前面不要输出任何文字）
            2. 在每轮工具调用前后，使用【思考】【观察】【行动】标记描述你的推理过程
            3. 在所有工具调用完成后的**最终回复**中，输出 `<!-- REASONING_END -->`，然后输出最终分析结论

            ⚠️ **关键约束**：
            - 你的第一个文本 token 必须是 `<` (即 `<!-- REASONING_START -->` 的开头)
            - **绝对不要**在 `<!-- REASONING_START -->` 之前输出任何文字（如"我来帮您分析"等）
            - REASONING 标记之间的所有文本必须使用【思考】【观察】【行动】格式
            - 只有 `<!-- REASONING_END -->` 之后的内容才会显示给用户作为最终回复
            - 推理过程（标记之间）会以折叠面板形式展示，用户可选择查看

            **多轮工具调用场景示例：**

            第1轮文本: <!-- REASONING_START -->
            【思考】用户想了解销售趋势，我需要先查询销售数据。
            (然后调用 query_database)

            第2轮文本: 【观察】获取了100条销售记录，包含日期和金额字段。
            【思考】数据结构清晰，需要按月聚合并绘制趋势图。
            【行动】编写Python代码进行时间序列分析。
            (然后调用 execute_code)

            最终回复: 【观察】代码执行成功，生成了趋势图。销售额在Q3达到峰值。
            <!-- REASONING_END -->

            ## 销售趋势分析
            根据数据分析，贵公司销售趋势呈现以下特征：...

            **回复要求：**
            - 用中文回复
            - 必须包含 REASONING_START/END 标记
            - 推理过程中每个步骤用【思考】、【观察】或【行动】开头
            - REASONING_END 之后的内容是最终结论，确保内容完整有价值
            - **重要**：不要在回复中描述"已生成X个图表"或"已生成图表"，图表会自动显示在前端，你只需要分析数据结果即可
            - **禁止**：不要列举图表名称（如"员工留存率柱状图"、"趋势图"等），这些是系统自动生成的，你只需要解读数据洞察

            """ + PlanningAgentSummaryFormatPolicy.instructions() + """

            数据库结构：
            """ + schemaInfo + "\n";
    }

    /**
     * 构建用户 prompt（只包含动态内容：用户问题 + 实体识别结果）
     */
    private String buildUserContent(String question) {
        NERResponse nerResponse = nerService.extractEntities(question);
        String entitiesStr = nerResponse.getEntities().stream()
                .map(e -> e.getText() + "(" + e.getType() + ")")
                .collect(Collectors.joining(", "));

        return String.format("""
            用户问题：%s
            识别到的实体：%s
            """, question, entitiesStr);
    }

    private String getDefaultBaseUrl(String provider) {
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }

}
