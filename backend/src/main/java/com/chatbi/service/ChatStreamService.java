package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.*;
import com.chatbi.service.enhancement.PromptEnhancementManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChatStreamService {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final ConversationService conversationService;
    private final IntentRecognitionService intentRecognitionService;
    private final PromptEnhancementManager enhancementManager;
    private final PlanningAgent planningAgent;
    private final ClarificationAgent clarificationAgent;
    private final SuggestionAgent suggestionAgent;
    private final DiagnosticService diagnosticService;
    private final ReportAgent reportAgent;
    private final PlanningHeartbeatManager heartbeatManager;
    private final ObjectMapper objectMapper;

    public ChatStreamService(
            ChatClient.Builder chatClientBuilder,
            ModelOptionsProvider modelOptions,
            ConversationService conversationService,
            IntentRecognitionService intentRecognitionService,
            PromptEnhancementManager enhancementManager,
            PlanningAgent planningAgent,
            ClarificationAgent clarificationAgent,
            SuggestionAgent suggestionAgent,
            DiagnosticService diagnosticService,
            ReportAgent reportAgent,
            PlanningHeartbeatManager heartbeatManager
    ) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.conversationService = conversationService;
        this.intentRecognitionService = intentRecognitionService;
        this.enhancementManager = enhancementManager;
        this.planningAgent = planningAgent;
        this.clarificationAgent = clarificationAgent;
        this.suggestionAgent = suggestionAgent;
        this.diagnosticService = diagnosticService;
        this.reportAgent = reportAgent;
        this.heartbeatManager = heartbeatManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 流式处理聊天请求
     */
    public void streamChat(String message, String conversationId, SseEmitter emitter, String forceAgentType) {
        long startTime = System.currentTimeMillis();
        AtomicBoolean completed = new AtomicBoolean(false);

        try {
            // 1. 创建对话 / 保存用户消息
            if (conversationId == null || conversationId.isEmpty()) {
                String title = message.length() <= 10 ? message : message.substring(0, Math.min(30, message.length())) + (message.length() > 30 ? "..." : "");
                var newConv = conversationService.createConversation(title);
                conversationId = newConv.getConversationId();
            }
            conversationService.saveMessage(conversationId, "user", message, null);

            // 2. 特殊意图：生成报告（含 AI Insight）
            // 匹配 "生成报告"/"生成XX报告"/"总结对话"/"分析汇报" 等，或强制触发
            if ("REPORT".equals(forceAgentType) || isReportRequest(message)) {
                emitStatus(emitter, "report_data_extraction", "正在提取对话数据...", 1, 4);
                emitStatus(emitter, "report_insight_generation", "正在生成数据洞察...", 2, 4);
                ChatResponse reportResult = reportAgent.generateInsightReport(conversationId);

                // 发送结构化 tags（关键指标、洞察、建议）
                List<MessageTag> reportTags = reportResult.getTags();
                if (reportTags != null && !reportTags.isEmpty()) {
                    emitStatus(emitter, "report_structure", "正在整理分析结构...", 3, 4);
                    for (MessageTag tag : reportTags) {
                        emitTag(emitter, tag);
                    }
                }

                // 发送报告正文
                emitStatus(emitter, "report_text", "正在生成报告正文...", 4, 4);
                emitTextDeltaFull(emitter, reportResult.getReply());

                conversationService.saveMessage(conversationId, "assistant", reportResult.getReply(), reportTags);
                emitDone(emitter, conversationId, System.currentTimeMillis() - startTime);
                completed.set(true);
                emitter.complete();
                return;
            }

            // 3. 意图识别（如果强制指定了agent类型，则跳过意图识别）
            IntentType intent;
            IntentRecognitionResponse intentResponse = null;

            if (forceAgentType != null && !forceAgentType.isEmpty()) {
                // 强制指定agent类型
                log.info("[ChatStreamService] 强制使用agent类型: {}", forceAgentType);
                try {
                    intent = IntentType.valueOf(forceAgentType);
                } catch (IllegalArgumentException e) {
                    log.warn("[ChatStreamService] 无效的agent类型: {}, 降级为意图识别", forceAgentType);
                    emitStatus(emitter, "intent_detection", "正在识别意图...", 1, 7);
                    intentResponse = recognizeIntent(message);
                    intent = IntentType.valueOf(intentResponse.getCategory());
                }
            } else {
                // 正常意图识别
                emitStatus(emitter, "intent_detection", "正在识别意图...", 1, 7);
                intentResponse = recognizeIntent(message);
                intent = IntentType.valueOf(intentResponse.getCategory());
            }

            // 发送意图事件（如果有意图识别结果）
            if (intentResponse != null) {
                emitIntent(emitter, intentResponse);
            }

            // 4. Prompt 增强
            emitStatus(emitter, "prompt_enhancement", "正在优化问题...", 2, 7);
            String promptToUse = message;
            try {
                EnhancementContext context = EnhancementContext.builder()
                        .originalMessage(message)
                        .conversationId(conversationId)
                        .intentType(intent)
                        .subtype(intentResponse.getSubtype())
                        .build();
                EnhancedPrompt enhanced = enhancementManager.enhance(message, context);
                if (enhanced.isEnhanced()) {
                    promptToUse = enhanced.getEnhancedPrompt();
                }
            } catch (Exception e) {
                log.warn("Prompt 增强失败: {}", e.getMessage());
            }

            // 5. 根据意图路由
            String reply;
            List<MessageTag> tags = new ArrayList<>();
            List<String> suggestions = null;

            switch (intent) {
                case GENERAL_CHAT:
                    reply = handleGeneralChatStream(emitter, message);
                    break;

                case DATA_QUERY:
                case HYBRID:
                case DATA_ANALYSIS:
                    reply = handleDataAnalysisStream(emitter, promptToUse, message, tags);
                    // 提取 suggestions
                    suggestions = extractSuggestions(tags);
                    break;

                case DIAGNOSTIC_ANALYSIS:
                    reply = handleDiagnosticStream(emitter, promptToUse, tags);
                    break;

                default:
                    reply = handleGeneralChatStream(emitter, promptToUse);
                    break;
            }

            // 6. 保存助手消息
            conversationService.saveMessage(conversationId, "assistant", reply, tags.isEmpty() ? null : tags);

            // 7. 发送完成事件
            long duration = System.currentTimeMillis() - startTime;
            emitDone(emitter, conversationId, duration);
            completed.set(true);
            emitter.complete();

        } catch (Exception e) {
            log.error("流式处理失败: {}", e.getMessage(), e);
            if (!completed.get()) {
                try {
                    emitError(emitter, "PROCESSING_ERROR", e.getMessage(), "fatal");
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ─── GENERAL_CHAT: LLM 生成 + 分块推送 ─────────────────────

    private String handleGeneralChatStream(SseEmitter emitter, String message) throws IOException {
        emitStatus(emitter, "llm_generation", "正在生成回复...", 3, 7);

        // 使用同步 .call()（WebClient 流式 SSL 握手与 OpenRouter 不兼容）
        // 然后通过 emitTextDeltaFull 分块推送，模拟流式效果
        String response = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(message)
                .call()
                .content();

        emitTextDeltaFull(emitter, response);
        return response;
    }

    // ─── DATA_QUERY / HYBRID / DATA_ANALYSIS: Agent 协同 ──────

    private String handleDataAnalysisStream(SseEmitter emitter, String promptToUse, String originalQuestion, List<MessageTag> tags) throws IOException {
        // 澄清检查
        emitStatus(emitter, "clarification", "正在分析问题...", 3, 5);
        List<String> clarifications = clarificationAgent.clarify(originalQuestion);
        if (!clarifications.isEmpty()) {
            StringBuilder reply = new StringBuilder("为了更准确地回答您，我需要确认以下信息：\n");
            for (String q : clarifications) {
                reply.append("- ").append(q).append("\n");
            }
            emitTextDeltaFull(emitter, reply.toString());
            return reply.toString();
        }

        // Function Calling 流程（LLM 自主调用 query_database + execute_code）
        emitStatus(emitter, "planning", "正在分析数据...", 4, 5);

        String sessionId = java.util.UUID.randomUUID().toString();
        String toolResult = null;

        // 设置 ThreadLocal
        SseEmitterContext.setEmitter(emitter);

        // 启动心跳
        heartbeatManager.startHeartbeat(sessionId, (message) -> {
            try {
                emitStatus(emitter, "planning", message, 4, 5);
            } catch (Exception e) {
                log.debug("心跳发送失败: {}", e.getMessage());
            }
        });

        try {
            toolResult = planningAgent.planWithTools(promptToUse);
        } catch (Exception e) {
            log.error("[planWithTools] Function Calling 失败: {}", e.getMessage(), e);
        } finally {
            heartbeatManager.stopHeartbeat(sessionId);
            SseEmitterContext.clear();
        }

        if (toolResult == null || toolResult.isBlank()) {
            String errorMsg = "分析未返回结果，请重新描述您的问题。";
            emitTextDeltaFull(emitter, errorMsg);
            return errorMsg;
        }

        // 解析推理链：提取 <!-- REASONING_START --> 和 <!-- REASONING_END --> 之间的内容
        String reasoning = null;
        String conclusion = toolResult;
        Pattern reasoningPattern = Pattern.compile("<!--\\s*REASONING_START\\s*-->(.*?)<!--\\s*REASONING_END\\s*-->", Pattern.DOTALL);
        Matcher reasoningMatcher = reasoningPattern.matcher(toolResult);
        if (reasoningMatcher.find()) {
            reasoning = reasoningMatcher.group(1).trim();
            // 从最终回复中移除推理部分
            conclusion = toolResult.substring(0, reasoningMatcher.start()) + toolResult.substring(reasoningMatcher.end());
            conclusion = conclusion.trim();
        }

        // 推送推理步骤
        if (reasoning != null && !reasoning.isEmpty()) {
            emitReasoningSteps(emitter, reasoning);
        }

        // 发送最终结论文本
        try {
            emitTextDeltaFull(emitter, conclusion);
        } catch (Exception e) {
            log.warn("[planWithTools] SSE 发送失败（连接可能已超时）: {}", e.getMessage());
        }

        // 生成建议
        emitStatus(emitter, "suggestions", "正在生成推荐问题...", 5, 5);
        try {
            String summaryForSuggestion = toolResult.length() > 500
                    ? toolResult.substring(0, 500) : toolResult;
            List<String> suggestions = suggestionAgent.suggestAsync(originalQuestion, summaryForSuggestion).join();
            if (!suggestions.isEmpty()) {
                tags.add(new MessageTag("suggestions", suggestions, "推荐后续问题", null));
                emitSuggestions(emitter, suggestions);
            }
        } catch (Exception e) {
            log.warn("建议生成失败", e);
        }

        return conclusion;
    }

    // ─── DIAGNOSTIC_ANALYSIS ──────────────────────────────────

    private String handleDiagnosticStream(SseEmitter emitter, String question, List<MessageTag> tags) throws IOException {
        emitStatus(emitter, "diagnostic", "正在进行归因分析...", 3, 5);

        ChatResponse diagnosticResult = diagnosticService.analyzeRootCause(question);

        // 发送 tags
        if (diagnosticResult.getTags() != null) {
            for (MessageTag tag : diagnosticResult.getTags()) {
                tags.add(tag);
                emitTag(emitter, tag);
            }
        }

        // 发送回复文本
        emitStatus(emitter, "summary", "正在生成总结...", 4, 5);
        emitTextDeltaFull(emitter, diagnosticResult.getReply());

        return diagnosticResult.getReply();
    }

    // ─── 意图识别 ─────────────────────────────────────────────

    private IntentRecognitionResponse recognizeIntent(String message) {
        try {
            return intentRecognitionService.recognize(message);
        } catch (Exception e) {
            log.warn("意图识别失败，降级为 GENERAL_CHAT: {}", e.getMessage());
            IntentRecognitionResponse fallback = new IntentRecognitionResponse();
            fallback.setCategory("GENERAL_CHAT");
            fallback.setCategoryCn("日常对话");
            fallback.setCategoryConfidence(0.5);
            fallback.setSubtype("UNKNOWN_QUERY");
            fallback.setSubtypeCn("未知查询");
            fallback.setSubtypeConfidence(0.5);
            return fallback;
        }
    }

    // ─── SSE 事件发送方法 ─────────────────────────────────────

    private void emitStatus(SseEmitter emitter, String stage, String message, int progress, int totalSteps) throws IOException {
        StreamEventData.StatusEventData data = new StreamEventData.StatusEventData(stage, message, progress, totalSteps);
        emitter.send(SseEmitter.event().name("status").data(objectMapper.writeValueAsString(data)));
    }

    private void emitIntent(SseEmitter emitter, IntentRecognitionResponse intent) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", intent.getCategory() != null ? intent.getCategory() : "GENERAL_CHAT");
        data.put("categoryCn", intent.getCategoryCn() != null ? intent.getCategoryCn() : "普通对话");
        data.put("categoryConfidence", intent.getCategoryConfidence());
        data.put("subtype", intent.getSubtype() != null ? intent.getSubtype() : "UNKNOWN_QUERY");
        data.put("subtypeConfidence", intent.getSubtypeConfidence());
        data.put("subtypeCn", intent.getSubtypeCn() != null ? intent.getSubtypeCn() : "未知查询");
        emitter.send(SseEmitter.event().name("intent").data(objectMapper.writeValueAsString(data)));
    }

    private void emitTextDelta(SseEmitter emitter, String delta) throws IOException {
        StreamEventData.TextDeltaEventData data = new StreamEventData.TextDeltaEventData(delta);
        emitter.send(SseEmitter.event().name("text_delta").data(objectMapper.writeValueAsString(data)));
    }

    private void emitTextDeltaFull(SseEmitter emitter, String text) throws IOException {
        // 将完整文本逐字分块发送，加入微延迟确保浏览器逐步接收
        if (text == null || text.isEmpty()) return;
        int chunkSize = 4; // 每次 4 字符，配合 30ms 延迟产生流式效果
        for (int i = 0; i < text.length(); i += chunkSize) {
            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            emitTextDelta(emitter, chunk);
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void emitTag(SseEmitter emitter, MessageTag tag) throws IOException {
        emitter.send(SseEmitter.event().name("tag").data(objectMapper.writeValueAsString(tag)));
    }

    private void emitSuggestions(SseEmitter emitter, List<String> items) throws IOException {
        Map<String, Object> data = Map.of("items", items);
        emitter.send(SseEmitter.event().name("suggestions").data(objectMapper.writeValueAsString(data)));
    }

    private void emitDone(SseEmitter emitter, String conversationId, long totalDuration) throws IOException {
        StreamEventData.DoneEventData data = new StreamEventData.DoneEventData(conversationId, totalDuration);
        emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(data)));
    }

    private void emitError(SseEmitter emitter, String code, String message, String stage) {
        try {
            StreamEventData.ErrorEventData data = new StreamEventData.ErrorEventData(code, message, stage);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(data)));
        } catch (IOException ignored) {
        }
    }

    private void emitReasoning(SseEmitter emitter, String step, String content, int stepIndex) throws IOException {
        StreamEventData.ReasoningEventData data = new StreamEventData.ReasoningEventData(step, content, stepIndex);
        emitter.send(SseEmitter.event().name("reasoning").data(objectMapper.writeValueAsString(data)));
    }

    /**
     * 解析推理文本中的【思考】和【观察】步骤，逐步推送 reasoning 事件
     */
    private void emitReasoningSteps(SseEmitter emitter, String reasoningText) {
        // 按【思考】和【观察】分割
        Pattern stepPattern = Pattern.compile("【(思考|观察)】");
        Matcher matcher = stepPattern.matcher(reasoningText);

        List<String[]> steps = new ArrayList<>(); // [stepType, content]
        int lastEnd = 0;
        String lastType = null;

        while (matcher.find()) {
            if (lastType != null) {
                String content = reasoningText.substring(lastEnd, matcher.start()).trim();
                if (!content.isEmpty()) {
                    steps.add(new String[]{lastType, content});
                }
            }
            lastType = matcher.group(1).equals("思考") ? "thought" : "observation";
            lastEnd = matcher.end();
        }
        // 最后一段
        if (lastType != null && lastEnd < reasoningText.length()) {
            String content = reasoningText.substring(lastEnd).trim();
            if (!content.isEmpty()) {
                steps.add(new String[]{lastType, content});
            }
        }

        // 如果没有解析到步骤，整体作为一个 thought 推送
        if (steps.isEmpty() && !reasoningText.trim().isEmpty()) {
            steps.add(new String[]{"thought", reasoningText.trim()});
        }

        for (int i = 0; i < steps.size(); i++) {
            try {
                emitReasoning(emitter, steps.get(i)[0], steps.get(i)[1], i);
            } catch (IOException e) {
                log.warn("推送推理步骤失败: {}", e.getMessage());
                break;
            }
        }
    }

    public void emitCodeExecution(SseEmitter emitter, String executionId,
                                   String stage, String code, String stdout,
                                   String stderr, Boolean success, Long executionTime) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", executionId);
        data.put("stage", stage);
        if (code != null) data.put("code", code);
        if (stdout != null) data.put("stdout", stdout);
        if (stderr != null) data.put("stderr", stderr);
        if (success != null) data.put("success", success);
        if (executionTime != null) data.put("executionTime", executionTime);

        emitter.send(SseEmitter.event()
                .name("code_execution")
                .data(objectMapper.writeValueAsString(data)));
    }

    // ─── 辅助工具 ─────────────────────────────────────────────

    private List<String> extractSuggestions(List<MessageTag> tags) {
        for (MessageTag tag : tags) {
            if ("suggestions".equals(tag.getType()) && tag.getContent() instanceof List) {
                return (List<String>) tag.getContent();
            }
        }
        return null;
    }

    /**
     * 复用 ChatService 的 parseAnalysisOutput 逻辑
     */
    public Map<String, Object> parseAnalysisOutput(String stdout) {
        List<Map<String, Object>> sections = new ArrayList<>();

        if (stdout == null || stdout.trim().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sections", sections);
            return result;
        }

        String[] lines = stdout.split("\n");
        List<String> currentBlock = new ArrayList<>();
        String currentTitle = "";

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();

            java.util.regex.Matcher titleMatcher =
                    java.util.regex.Pattern.compile("^={3,}\\s*(.+?)\\s*={3,}$").matcher(line.trim());
            if (titleMatcher.matches()) {
                if (!currentBlock.isEmpty()) {
                    Map<String, Object> section = processBlock(currentBlock, currentTitle);
                    if (section != null) sections.add(section);
                    currentBlock.clear();
                }
                currentTitle = titleMatcher.group(1);
                continue;
            }

            currentBlock.add(line);
        }

        if (!currentBlock.isEmpty()) {
            Map<String, Object> section = processBlock(currentBlock, currentTitle);
            if (section != null) sections.add(section);
        }

        if (sections.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "text");
            fallback.put("title", "");
            fallback.put("content", stdout);
            sections.add(fallback);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sections", sections);
        return result;
    }

    private Map<String, Object> processBlock(List<String> lines, String title) {
        List<String> nonEmpty = new ArrayList<>();
        for (String l : lines) {
            if (!l.trim().isEmpty()) nonEmpty.add(l);
        }
        if (nonEmpty.isEmpty()) return null;

        if (nonEmpty.size() >= 2) {
            List<String[]> potentialRows = new ArrayList<>();
            boolean isTable = true;
            int expectedCols = -1;
            for (String l : nonEmpty) {
                // 尝试用多个空格分割（优先），如果失败则尝试单个空格
                String[] parts = l.trim().split("\\s{2,}");
                if (parts.length < 2) {
                    // 尝试用单个空格分割（兼容某些格式）
                    parts = l.trim().split("\\s+");
                }
                if (parts.length < 2) { isTable = false; break; }
                if (expectedCols == -1) expectedCols = parts.length;
                else if (Math.abs(parts.length - expectedCols) > 1) { isTable = false; break; }
                potentialRows.add(parts);
            }
            if (isTable && potentialRows.size() >= 2) {
                String[] headers = potentialRows.get(0);
                List<List<String>> rows = new ArrayList<>();
                for (int i = 1; i < potentialRows.size(); i++) {
                    rows.add(java.util.Arrays.asList(potentialRows.get(i)));
                }
                Map<String, Object> section = new LinkedHashMap<>();
                section.put("type", "table");
                section.put("title", title);
                section.put("columns", java.util.Arrays.asList(headers));
                section.put("rows", rows);
                return section;
            }
        }

        List<Map<String, String>> stats = new ArrayList<>();
        boolean isStats = true;
        for (String l : nonEmpty) {
            int colonIdx = l.indexOf(':');
            if (colonIdx > 0 && colonIdx < 40) {
                String key = l.substring(0, colonIdx).trim();
                String val = l.substring(colonIdx + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("label", key);
                    item.put("value", val);
                    stats.add(item);
                    continue;
                }
            }
            isStats = false;
            break;
        }
        if (isStats && !stats.isEmpty()) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("type", "stats");
            section.put("title", title);
            section.put("items", stats);
            return section;
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "text");
        section.put("title", title);
        section.put("content", String.join("\n", lines));
        return section;
    }

    /**
     * 判断用户消息是否为报告生成请求
     * 匹配：生成报告、生成XX报告、总结对话、分析汇报 等
     */
    private static boolean isReportRequest(String message) {
        if (message == null) return false;
        if (message.contains("总结对话") || message.contains("分析汇报")) return true;
        // "生成...报告" 模式（中间最多 20 个字）
        return message.matches(".*生成.{0,20}报告.*");
    }
}
