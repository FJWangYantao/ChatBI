package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.NERResponse;
import com.chatbi.dto.StreamingTagEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

                // 流式调用 LLM
                RoundResult result = streamOneRound(messages, options, onTextDelta);

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
     * 单轮流式 LLM 调用：收集文本和工具调用
     * 文本 token 实时通过 onTextDelta 转发
     */
    private RoundResult streamOneRound(
            List<Message> messages,
            OpenAiChatOptions options,
            Consumer<String> onTextDelta) {

        Prompt prompt = new Prompt(messages, options);
        StringBuilder textBuffer = new StringBuilder();

        // 工具调用累积器：toolCallIndex -> {id, name, arguments}
        Map<Integer, String> tcIds = new HashMap<>();
        Map<Integer, String> tcNames = new HashMap<>();
        Map<Integer, StringBuilder> tcArgs = new HashMap<>();

        try {
            Flux<ChatResponse> flux = chatModel.stream(prompt);

            flux.doOnNext(response -> {
                if (response == null || response.getResult() == null) return;
                Generation gen = response.getResult();
                AssistantMessage msg = gen.getOutput();
                if (msg == null) return;

                // 1. 文本内容 → 实时转发
                String text = msg.getContent();
                if (text != null && !text.isEmpty()) {
                    textBuffer.append(text);
                    try {
                        onTextDelta.accept(text);
                    } catch (Exception e) {
                        log.debug("文本回调失败: {}", e.getMessage());
                    }
                }

                // 2. 工具调用 → 累积
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
                            tcArgs.computeIfAbsent(i, k -> new StringBuilder())
                                    .append(tc.arguments());
                        }
                    }
                }
            }).blockLast();
        } catch (Exception e) {
            log.error("[PlanningAgent] Stream round failed: {}", e.getMessage(), e);
            // 如果流式失败，降级为同步调用
            return fallbackCallRound(messages, options, onTextDelta);
        }

        // 组装完整的工具调用列表
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
