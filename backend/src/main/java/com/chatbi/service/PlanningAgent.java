package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.NERResponse;
import com.chatbi.dto.StreamingTagEvent;
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

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final ModelOptionsProvider modelOptions;
    private final NERService nerService;
    private final ReadSchemaStructureService schemaService;
    private final FunctionCallback executeCodeFunction;
    private final FunctionCallback validateCodeFunction;
    private final FunctionCallback sandboxInfoFunction;
    private final FunctionCallback queryDatabaseFunction;
    private final Map<String, FunctionCallback> toolMap;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String apiBaseUrl;

    public PlanningAgent(ChatClient.Builder chatClientBuilder,
                         ChatModel chatModel,
                         ModelOptionsProvider modelOptions,
                         NERService nerService,
                         ReadSchemaStructureService schemaService,
                         @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction,
                         @Qualifier("validateCodeFunction") FunctionCallback validateCodeFunction,
                         @Qualifier("sandboxInfoFunction") FunctionCallback sandboxInfoFunction,
                         @Qualifier("queryDatabaseFunction") FunctionCallback queryDatabaseFunction) {
        this.chatClient = chatClientBuilder.build();
        this.chatModel = chatModel;
        this.modelOptions = modelOptions;
        this.nerService = nerService;
        this.schemaService = schemaService;
        this.executeCodeFunction = executeCodeFunction;
        this.validateCodeFunction = validateCodeFunction;
        this.sandboxInfoFunction = sandboxInfoFunction;
        this.queryDatabaseFunction = queryDatabaseFunction;

        // 构建工具名称到回调的映射
        this.toolMap = Map.of(
                "query_database", queryDatabaseFunction,
                "execute_code", executeCodeFunction,
                "validate_code", validateCodeFunction,
                "sandbox_info", sandboxInfoFunction
        );
    }

    /**
     * 向后兼容：使用 Function Calling 的增强规划（阻塞版）
     */
    public String planWithTools(String question) {
        return planWithToolsStreaming(question, delta -> {}, event -> {});
    }

    /**
     * 流式版本：手动实现 function calling 循环 + 流式读取
     * @param question 用户问题
     * @param onTextDelta 文本 token 实时回调
     * @param onTagEvent 流式 tag 事件回调
     */
    public String planWithToolsStreaming(
            String question,
            Consumer<String> onTextDelta,
            Consumer<StreamingTagEvent> onTagEvent) {

        log.info("[PlanningAgent] Planning with tools (streaming) for: {}", question);

        String userPrompt = buildUserPrompt(question);
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userPrompt));

        // 构建带工具定义的 options（proxyToolCalls=true 阻止自动执行）
        OpenAiChatOptions baseOptions = modelOptions.getOptions("planning");
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(baseOptions);
        options.setFunctionCallbacks(List.of(
                queryDatabaseFunction, executeCodeFunction,
                validateCodeFunction, sandboxInfoFunction));
        options.setProxyToolCalls(true);

        int maxRounds = 10;
        try {
            for (int round = 0; round < maxRounds; round++) {
                log.info("[PlanningAgent] Round {} starting", round + 1);

                // 流式调用 LLM（同时拦截 execute_code 的代码参数进行流式发送）
                RoundResult result = streamOneRound(messages, options, onTextDelta, onTagEvent);

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

                // 手动执行工具
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : result.toolCalls) {
                    String toolResult = executeToolManually(toolCall);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), toolResult));
                }
                messages.add(new ToolResponseMessage(toolResponses));
                log.info("[PlanningAgent] Round {} completed,  tools executed",
                        round + 1, result.toolCalls.size());
            }
        } catch (Exception e) {
            log.error("[PlanningAgent] Streaming function calling failed: {}", e.getMessage(), e);
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

        boolean isDone() { return done; }
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
            if (msg instanceof UserMessage um) {
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
                validateCodeFunction, sandboxInfoFunction)) {
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
            Consumer<StreamingTagEvent> onTagEvent) {

        String model = options.getModel() != null ? options.getModel() : "deepseek-chat";
        double temperature = options.getTemperature() != null ? options.getTemperature() : 0.1;

        try {
            // 构建请求体
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("stream", true);
            body.set("messages", convertMessagesToOpenAI(messages));
            body.set("tools", buildToolsArray());

            String url = apiBaseUrl.replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[PlanningAgent] Raw API error {}: {}", response.statusCode(), errBody);
                throw new RuntimeException("API returned " + response.statusCode());
            }

            return parseSSEStream(response.body(), onTextDelta, onTagEvent);

        } catch (Exception e) {
            log.warn("[PlanningAgent] Raw streaming failed, falling back to Spring AI: {}", e.getMessage());
            return fallbackStreamRound(messages, options, onTextDelta);
        }
    }

    /**
     * 解析 SSE 流，提取文本 delta 和工具调用参数（逐 token）
     */
    private RoundResult parseSSEStream(
            java.io.InputStream inputStream,
            Consumer<String> onTextDelta,
            Consumer<StreamingTagEvent> onTagEvent) throws Exception {

        StringBuilder textBuffer = new StringBuilder();
        // 工具调用累积：index -> {id, name, argsBuilder}
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();
        // 代码流式提取
        Map<Integer, ToolArgCodeExtractor> codeExtractors = new HashMap<>();
        Map<Integer, String> codeTagIds = new HashMap<>();
        Map<Integer, Boolean> tagStartSent = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                if (data.isEmpty()) continue;

                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choices = chunk.get("choices");
                if (choices == null || choices.isEmpty()) continue;
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
                                tcNames.put(idx, fnNode.get("name").asText());
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

        log.info("[PlanningAgent] Using Spring AI stream fallback");
        Prompt prompt = new Prompt(messages, options);
        StringBuilder textBuffer = new StringBuilder();
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();

        try {
            chatModel.stream(prompt).doOnNext(response -> {
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
        } catch (Exception e) {
            log.error("[PlanningAgent] Spring AI stream also failed: {}", e.getMessage());
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
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
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
        log.info("[PlanningAgent] Executing tool: {} with args length: {}",
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
     * 构建用户 prompt（包含系统指令、工具说明、用户问题）
     */
    private String buildUserPrompt(String question) {
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
        NERResponse nerResponse = nerService.extractEntities(question);
        String entitiesStr = nerResponse.getEntities().stream()
                .map(e -> e.getText() + "(" + e.getType() + ")")
                .collect(Collectors.joining(", "));

        return String.format("""
            你是一个数据分析专家，拥有以下工具：

            1. **query_database**: 查询数据库获取数据。传入自然语言描述需要什么数据。
               - 输入: {"data_description": "查询所有2024年的月度销售额"}
               - 返回: data_preview（预览）、data_ref_id（数据引用ID）、columns、row_count
            2. **execute_code**: 在安全沙盒中执行 Python 代码进行数据分析和可视化。
               - 输入: {"code": "...", "data_ref_id": "..."}
               - data_ref_id 使用 query_database 返回的 data_ref_id，系统会自动加载数据
            3. **validate_code**: 预检代码安全性（通常不需要调用）
            4. **sandbox_info**: 查询沙盒环境能力（通常不需要调用）

            **标准工作流程：**
            1. 先调用 query_database 获取数据
            2. 查看返回的 data_preview 理解数据结构和列名
            3. 编写 Python 分析代码，调用 execute_code 并传入 data_ref_id（不要传 data_json）
            4. 根据执行结果生成分析总结

            **重要规则：**
            - 必须先用 query_database 获取真实数据，绝对不要自己编造或模拟数据
            - execute_code 只需传 data_ref_id，不要传 data_json（数据由服务端自动注入）
            - 如果 query_database 返回 success=false，向用户说明无法获取数据
            - 如果 execute_code 失败，根据 error_hint 修正代码并重试（最多 2 次）
            - Python 代码中数据已自动加载为 df (pandas DataFrame)
            - 绘图使用 matplotlib，设置中文字体: plt.rcParams['font.sans-serif'] = ['SimHei']
            - 保存图表: plt.savefig('output.png', dpi=100, bbox_inches='tight')
            - 打印关键统计结果到 stdout

            **沙盒限制：**
            - 仅允许导入：pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64
            - 禁止：exit, eval, exec, compile, open, __import__ 等
            - 执行超时：30 秒

            **ReAct 推理框架（必须遵守）：**
            你必须在最终回复中展示完整的推理过程。使用以下格式：

            <!-- REASONING_START -->
            【思考】说明推理逻辑
            【观察】分析返回数据
            <!-- REASONING_END -->

            然后在标记之外给出最终结论和分析总结。

            **回复要求：**
            - 用中文回复
            - 必须包含 REASONING_START/END 标记
            - 推理过程中每个步骤用【思考】或【观察】开头
            - 标记之外的内容是最终结论

            用户问题：%s
            识别到的实体：%s
            数据库结构：
            %s
            """, question, entitiesStr, schemaInfo);
    }
}
