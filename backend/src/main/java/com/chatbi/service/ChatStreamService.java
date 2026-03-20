package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.*;
import com.chatbi.factory.DynamicChatClientFactory;
import com.chatbi.service.enhancement.PromptEnhancementManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChatStreamService {

    private final DynamicChatClientFactory chatClientFactory;
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
    private final ReadSchemaStructureService schemaService;
    private final SQLCorrectionAgent sqlCorrectionAgent;
    private final Text2SQLAgent text2SQLAgent;
    private final QueryRewriteService queryRewriteService;

    public ChatStreamService(
            DynamicChatClientFactory chatClientFactory,
            ModelOptionsProvider modelOptions,
            ConversationService conversationService,
            IntentRecognitionService intentRecognitionService,
            PromptEnhancementManager enhancementManager,
            PlanningAgent planningAgent,
            ClarificationAgent clarificationAgent,
            SuggestionAgent suggestionAgent,
            DiagnosticService diagnosticService,
            ReportAgent reportAgent,
            PlanningHeartbeatManager heartbeatManager,
            ReadSchemaStructureService schemaService,
            SQLCorrectionAgent sqlCorrectionAgent,
            Text2SQLAgent text2SQLAgent,
            QueryRewriteService queryRewriteService
    ) {
        this.chatClientFactory = chatClientFactory;
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
        this.schemaService = schemaService;
        this.sqlCorrectionAgent = sqlCorrectionAgent;
        this.text2SQLAgent = text2SQLAgent;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 流式处理聊天请求
     */
    public void streamChat(String message, String conversationId, SseEmitter emitter, String forceAgentType) {
        long startTime = System.currentTimeMillis();
        AtomicBoolean completed = new AtomicBoolean(false);
        // 收集步骤结果用于持久化
        List<Map<String, Object>> collectedSteps = new ArrayList<>();
        // 收集意图信息用于持久化
        Map<String, Object> collectedIntentInfo = null;

        try {
            // 检测 /sql 前缀，进入查数模式
            String actualMessage = message;
            String agentType = forceAgentType;

            if (message.startsWith("/sql ")) {
                actualMessage = message.substring(5).trim();
                agentType = "QUERY_MODE";
            }

            // 1. 创建对话 / 保存用户消息
            if (conversationId == null || conversationId.isEmpty()) {
                String title = actualMessage.length() <= 10 ? actualMessage : actualMessage.substring(0, Math.min(30, actualMessage.length())) + (actualMessage.length() > 30 ? "..." : "");
                var newConv = conversationService.createConversation(title);
                conversationId = newConv.getConversationId();
            }
            conversationService.saveMessage(conversationId, "user", message, null);

            // 路由到查数模式
            if ("QUERY_MODE".equals(agentType)) {
                handleQueryMode(actualMessage, conversationId, emitter);
                return;
            }

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

            // 2.5 提前加载对话历史（意图识别和 Prompt 增强都需要）
            List<MessageDTO> history = conversationService.getMessages(conversationId);

            // 2.6 Query Rewriting：追问场景下改写为自包含查询
            emitStatus(emitter, "query_rewrite", "正在理解问题...", 1, 7);
            long rewriteStartTime = System.currentTimeMillis();
            String rewrittenMessage = queryRewriteService.rewriteIfNeeded(actualMessage, history);
            boolean isRewritten = !rewrittenMessage.equals(actualMessage);

            // 发送改写步骤结果
            Map<String, Object> rewriteResult = new LinkedHashMap<>();
            rewriteResult.put("isRewritten", isRewritten);
            rewriteResult.put("originalMessage", actualMessage);
            if (isRewritten) {
                rewriteResult.put("rewrittenMessage", rewrittenMessage);
            }
            emitStepResult(emitter, "query_rewrite", "查询改写",
                    System.currentTimeMillis() - rewriteStartTime, "success", rewriteResult, collectedSteps);

            // 3. 意图识别（使用改写后的文本）
            IntentType intent;
            IntentRecognitionResponse intentResponse = null;
            long intentStartTime = System.currentTimeMillis();

            if (forceAgentType != null && !forceAgentType.isEmpty()) {
                // 强制指定agent类型
                log.info("[ChatStreamService] 强制使用agent类型: {}", forceAgentType);
                try {
                    intent = IntentType.valueOf(forceAgentType);
                } catch (IllegalArgumentException e) {
                    log.warn("[ChatStreamService] 无效的agent类型: {}, 降级为意图识别", forceAgentType);
                    emitStatus(emitter, "intent_detection", "正在识别意图...", 2, 7);
                    intentResponse = recognizeIntent(rewrittenMessage);
                    intent = IntentType.valueOf(intentResponse.getCategory());
                }
            } else {
                // 正常意图识别
                emitStatus(emitter, "intent_detection", "正在识别意图...", 2, 7);
                intentResponse = recognizeIntent(rewrittenMessage);
                intent = IntentType.valueOf(intentResponse.getCategory());
                log.info("[ChatStreamService] 意图识别结果: {} (识别文本: {})", intentResponse.getCategory(), rewrittenMessage);
            }

            // 发送意图事件（如果有意图识别结果）
            if (intentResponse != null) {
                emitIntent(emitter, intentResponse);
                // 收集意图信息用于持久化
                collectedIntentInfo = new LinkedHashMap<>();
                collectedIntentInfo.put("category", intentResponse.getCategory());
                collectedIntentInfo.put("categoryCn", intentResponse.getCategoryCn());
                collectedIntentInfo.put("categoryConfidence", intentResponse.getCategoryConfidence());
                collectedIntentInfo.put("subtype", intentResponse.getSubtype());
                collectedIntentInfo.put("subtypeCn", intentResponse.getSubtypeCn());
                collectedIntentInfo.put("subtypeConfidence", intentResponse.getSubtypeConfidence());
                // 发送意图识别步骤结果
                Map<String, Object> intentResult = new LinkedHashMap<>();
                intentResult.put("categoryCn", intentResponse.getCategoryCn());
                intentResult.put("subtypeCn", intentResponse.getSubtypeCn());
                intentResult.put("confidence", intentResponse.getCategoryConfidence());
                emitStepResult(emitter, "intent_detection", "意图识别",
                        System.currentTimeMillis() - intentStartTime, "success", intentResult, collectedSteps);
            }

            // 4. Prompt 增强（使用改写后的文本）
            emitStatus(emitter, "prompt_enhancement", "正在优化问题...", 3, 7);
            long enhanceStartTime = System.currentTimeMillis();
            String promptToUse = rewrittenMessage;
            boolean isEnhanced = false;
            try {
                EnhancementContext context = EnhancementContext.builder()
                        .originalMessage(rewrittenMessage)
                        .conversationId(conversationId)
                        .conversationHistory(history)
                        .intentType(intent)
                        .subtype(intentResponse != null ? intentResponse.getSubtype() : null)
                        .build();
                EnhancedPrompt enhanced = enhancementManager.enhance(rewrittenMessage, context);
                if (enhanced.isEnhanced()) {
                    promptToUse = enhanced.getEnhancedPrompt();
                    isEnhanced = true;
                }
            } catch (Exception e) {
                log.warn("Prompt 增强失败: {}", e.getMessage());
            }
            // 发送增强步骤结果
            Map<String, Object> enhanceResult = new LinkedHashMap<>();
            enhanceResult.put("isEnhanced", isEnhanced);
            enhanceResult.put("originalPrompt", message);
            if (isEnhanced) {
                enhanceResult.put("enhancedPrompt", promptToUse);
            }
            emitStepResult(emitter, "prompt_enhancement", "问题优化",
                    System.currentTimeMillis() - enhanceStartTime, "success", enhanceResult, collectedSteps);

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
                    reply = handleDataAnalysisStream(emitter, promptToUse, message, tags, collectedSteps);
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

            // 6. 保存助手消息（含完整结构化数据）
            List<String> finalSuggestions = extractSuggestions(tags);
            conversationService.saveMessage(
                conversationId, "assistant", reply,
                tags.isEmpty() ? null : tags,
                collectedSteps.isEmpty() ? null : collectedSteps,
                collectedIntentInfo,
                finalSuggestions,
                null  // reasoningSteps 通过 steps 间接保存
            );

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

        // 动态创建 ChatClient（已包含前端配置的 model 和 temperature）
        ChatClient chatClient = chatClientFactory.createChatClient("chat");

        // 使用同步 .call()（WebClient 流式 SSL 握手与 OpenRouter 不兼容）
        // 然后通过 emitTextDeltaFull 分块推送，模拟流式效果
        // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions
        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();

        emitTextDeltaFull(emitter, response);
        return response;
    }

    // ─── DATA_QUERY / HYBRID / DATA_ANALYSIS: Agent 协同 ──────

    private String handleDataAnalysisStream(SseEmitter emitter, String promptToUse, String originalQuestion, List<MessageTag> tags, List<Map<String, Object>> collectedSteps) throws IOException {
        // 澄清检查
        emitStatus(emitter, "clarification", "正在分析问题...", 3, 5);
        long clarifyStartTime = System.currentTimeMillis();
        List<String> clarifications = clarificationAgent.clarify(originalQuestion);

        // 发送澄清步骤结果
        Map<String, Object> clarifyResult = new LinkedHashMap<>();
        clarifyResult.put("needsClarification", !clarifications.isEmpty());
        clarifyResult.put("clarifications", clarifications);
        emitStepResult(emitter, "clarification", "澄清检查",
                System.currentTimeMillis() - clarifyStartTime, "success", clarifyResult, collectedSteps);

        if (!clarifications.isEmpty()) {
            StringBuilder reply = new StringBuilder("为了更准确地回答您，我需要确认以下信息：\n");
            for (String q : clarifications) {
                reply.append("- ").append(q).append("\n");
            }
            emitTextDeltaFull(emitter, reply.toString());
            return reply.toString();
        }

        // Function Calling 流程（真流式输出）
        emitStatus(emitter, "planning", "正在分析数据...", 4, 5);

        String sessionId = java.util.UUID.randomUUID().toString();
        String toolResult = null;
        long analysisStartTime = System.currentTimeMillis();

        // 共享状态：PlanningAgent 更新，心跳读取并重发（保持连接活跃）
        java.util.concurrent.atomic.AtomicReference<String> currentStatus =
                new java.util.concurrent.atomic.AtomicReference<>("正在分析数据...");

        // 设置 ThreadLocal：emitter + 流式 tag 回调
        SseEmitterContext.setEmitter(emitter);
        SseEmitterContext.setTagStreamCallback(event -> {
            switch (event.eventType()) {
                case "start" -> emitTagStart(emitter, event.id(), event.type(), event.title());
                case "delta" -> emitTagDelta(emitter, event.id(), event.delta());
                case "end"   -> emitTagEnd(emitter, event.id(), event.type(), event.title(), event.content());
            }
        });

        // 启动心跳（读取 currentStatus，定期重发以保持 SSE 连接）
        heartbeatManager.startHeartbeat(sessionId, (message) -> {
            try {
                emitStatus(emitter, "planning", message, 4, 5);
            } catch (Exception e) {
                log.debug("心跳发送失败: {}", e.getMessage());
            }
        }, currentStatus::get);

        // 推理内容实时发送标记
        AtomicBoolean reasoningEmitted = new AtomicBoolean(false);

        // 推理内容过滤器：缓存 REASONING_START 到 REASONING_END 之间的内容，不发送到前端
        ReasoningFilter reasoningFilter = new ReasoningFilter((reasoningText, round) -> {
            try {
                emitReasoningSteps(emitter, reasoningText, round);
                reasoningEmitted.set(true);
            } catch (Exception e) {
                log.warn("实时推理事件发送失败: {}", e.getMessage());
            }
        });

        try {
            // 调用流式版 PlanningAgent：文本 token 实时转发，SQL 流式通过 tag 回调
            log.info("[handleDataAnalysisStream] 开始调用 planWithToolsStreaming, promptLength={}", promptToUse.length());

            toolResult = planningAgent.planWithToolsStreaming(
                    promptToUse,
                    delta -> {
                        try {
                            String filtered = reasoningFilter.filter(delta);
                            if (filtered != null && !filtered.isEmpty()) {
                                emitTextDelta(emitter, filtered);
                            }
                        } catch (IOException e) {
                            log.debug("文本 delta 发送失败: {}", e.getMessage());
                        }
                    },
                    event -> {
                        // 同上 tag 回调（PlanningAgent 内部也可能发送 tag 事件）
                        switch (event.eventType()) {
                            case "start" -> emitTagStart(emitter, event.id(), event.type(), event.title());
                            case "delta" -> emitTagDelta(emitter, event.id(), event.delta());
                            case "end"   -> emitTagEnd(emitter, event.id(), event.type(), event.title(), event.content());
                        }
                    },
                    statusMessage -> {
                        // 状态回调：更新共享状态 + 立即发送到前端
                        currentStatus.set(statusMessage);
                        try {
                            emitStatus(emitter, "planning", statusMessage, 4, 5);
                        } catch (Exception e) {
                            log.debug("状态更新发送失败: {}", e.getMessage());
                        }
                    }
            );
        } catch (Exception e) {
            log.error("[planWithToolsStreaming] 流式 Function Calling 失败: {}", e.getMessage(), e);
        } catch (Throwable t) {
            log.error("[planWithToolsStreaming] 严重错误 (Throwable): {}", t.getMessage(), t);
        } finally {
            // 立即停止心跳，避免在 emitter 完成后继续发送
            heartbeatManager.stopHeartbeat(sessionId);
            tags.addAll(SseEmitterContext.drainCollectedTags());
            // 刷出未闭合的推理内容（LLM 输出 START 但未输出 END 的情况）
            reasoningFilter.flush();
        }

        // 如果客户端已断开，直接返回
        if (SseEmitterContext.isDisconnected()) {
            log.info("[handleDataAnalysisStream] 客户端已断开，跳过后续处理");
            SseEmitterContext.clear();
            return toolResult != null ? toolResult : "";
        }

        if (toolResult == null || toolResult.isBlank()) {
            String errorMsg = "分析未返回结果，请重新描述您的问题。";
            emitTextDeltaFull(emitter, errorMsg);
            SseEmitterContext.clear();
            return errorMsg;
        }

        // 解析推理链（文本已通过流式实时发送，这里只做结构化提取）
        String reasoning = null;
        String conclusion = toolResult;
        Pattern reasoningPattern = Pattern.compile("<!--\\s*REASONING_START\\s*-->(.*?)<!--\\s*REASONING_END\\s*-->", Pattern.DOTALL);
        Matcher reasoningMatcher = reasoningPattern.matcher(toolResult);

        if (reasoningMatcher.find()) {
            reasoning = reasoningMatcher.group(1).trim();
            conclusion = toolResult.substring(0, reasoningMatcher.start()) + toolResult.substring(reasoningMatcher.end());
            conclusion = conclusion.trim();
        }

        // 后置推理发送：仅在实时回调未触发时作为 fallback
        if (!reasoningEmitted.get() && reasoning != null && !reasoning.isEmpty()) {
            log.info("[handleDataAnalysisStream] 实时推理未触发，使用 fallback 发送推理步骤");
            emitReasoningSteps(emitter, reasoning);
        }

        // 注意：最终文本已通过 onTextDelta 实时流式发送，不再需要 emitTextDeltaFull

        // 发送数据分析步骤结果
        Map<String, Object> analysisResult = new LinkedHashMap<>();
        analysisResult.put("hasResult", toolResult != null && !toolResult.isBlank());
        analysisResult.put("hasReasoning", reasoning != null && !reasoning.isEmpty());
        emitStepResult(emitter, "data_analysis", "数据分析",
                System.currentTimeMillis() - analysisStartTime, "success", analysisResult, collectedSteps);

        // 生成建议
        emitStatus(emitter, "suggestions", "正在生成推荐问题...", 5, 5);
        long suggestStartTime = System.currentTimeMillis();
        try {
            String summaryForSuggestion = toolResult.length() > 500
                    ? toolResult.substring(0, 500) : toolResult;
            List<String> suggestions = suggestionAgent.suggestAsync(originalQuestion, summaryForSuggestion).join();
            if (!suggestions.isEmpty()) {
                tags.add(new MessageTag("suggestions", suggestions, "推荐后续问题", null));
                emitSuggestions(emitter, suggestions);
            }
            // 发送建议生成步骤结果
            Map<String, Object> suggestResult = new LinkedHashMap<>();
            suggestResult.put("count", suggestions.size());
            emitStepResult(emitter, "suggestions", "建议生成",
                    System.currentTimeMillis() - suggestStartTime, "success", suggestResult, collectedSteps);
        } catch (Exception e) {
            log.warn("建议生成失败", e);
            emitStepResult(emitter, "suggestions", "建议生成",
                    System.currentTimeMillis() - suggestStartTime, "skipped", null, collectedSteps);
        }

        // 清理上下文
        SseEmitterContext.clear();
        return conclusion;
    }

    // ─── DIAGNOSTIC_ANALYSIS ──────────────────────────────────

    private String handleDiagnosticStream(SseEmitter emitter, String question, List<MessageTag> tags) throws IOException {
        // 启动心跳，防止长时间无 SSE 事件导致连接断开
        String sessionId = "diagnostic-" + System.currentTimeMillis();
        heartbeatManager.startHeartbeat(sessionId, (message) -> {
            try {
                emitStatus(emitter, "diagnostic", message, 3, 6);
            } catch (IOException e) {
                log.warn("[Diagnostic] 心跳发送失败: {}", e.getMessage());
            }
        }, () -> "正在进行归因分析...");

        try {
            // Phase 1: 生成 SQL + 执行查询
            emitStatus(emitter, "sql_generation", "正在生成分析SQL...", 2, 6);
            DiagnosticService.AnalysisQueryResult phase1 = diagnosticService.generateAndExecuteQueries(question);

            // 立即推送 tags（SQL + 表格 + 图表），用户可以先看到数据
            emitStatus(emitter, "sql_execution", "查询完成，正在分析结果...", 4, 6);
            if (phase1.tags() != null) {
                for (MessageTag tag : phase1.tags()) {
                    tags.add(tag);
                    emitTag(emitter, tag);
                }
            }

            // Phase 2: 流式生成归因报告（每个 token 实时推送到前端）
            emitStatus(emitter, "report_generation", "正在生成归因报告...", 5, 6);
            String report = diagnosticService.generateReportStreaming(question, phase1.dataSummary(), delta -> {
                try {
                    emitTextDelta(emitter, delta);
                } catch (IOException e) {
                    log.debug("[Diagnostic] 文本 delta 发送失败: {}", e.getMessage());
                }
            });

            return report;
        } finally {
            heartbeatManager.stopHeartbeat(sessionId);
        }
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

    /**
     * 安全发送 SSE 事件，连接已断开时静默跳过
     */
    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        if (SseEmitterContext.isDisconnected()) return;
        try {
            synchronized (emitter) {
                log.debug("[safeSend] 准备发送事件");
                emitter.send(event);
                log.debug("[safeSend] 事件发送成功");
            }
        } catch (IllegalStateException e) {
            log.warn("[safeSend] 发送事件失败，emitter 已完成: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
            throw new IOException("Emitter already completed", e);
        } catch (IOException e) {
            log.warn("[safeSend] 发送事件失败，客户端可能已断开: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
            throw e;
        }
    }

    private void emitStatus(SseEmitter emitter, String stage, String message, int progress, int totalSteps) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        StreamEventData.StatusEventData data = new StreamEventData.StatusEventData(stage, message, progress, totalSteps);
        safeSend(emitter, SseEmitter.event().name("status").data(objectMapper.writeValueAsString(data)));
    }

    private void emitIntent(SseEmitter emitter, IntentRecognitionResponse intent) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", intent.getCategory() != null ? intent.getCategory() : "GENERAL_CHAT");
        data.put("categoryCn", intent.getCategoryCn() != null ? intent.getCategoryCn() : "普通对话");
        data.put("categoryConfidence", intent.getCategoryConfidence());
        data.put("subtype", intent.getSubtype() != null ? intent.getSubtype() : "UNKNOWN_QUERY");
        data.put("subtypeConfidence", intent.getSubtypeConfidence());
        data.put("subtypeCn", intent.getSubtypeCn() != null ? intent.getSubtypeCn() : "未知查询");
        safeSend(emitter, SseEmitter.event().name("intent").data(objectMapper.writeValueAsString(data)));
    }

    private void emitTextDelta(SseEmitter emitter, String delta) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        StreamEventData.TextDeltaEventData data = new StreamEventData.TextDeltaEventData(delta);
        synchronized (emitter) {
            safeSend(emitter, SseEmitter.event().name("text_delta").data(objectMapper.writeValueAsString(data)));
        }
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
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        synchronized (emitter) {
            safeSend(emitter, SseEmitter.event().name("tag").data(objectMapper.writeValueAsString(tag)));
        }
    }

    /**
     * 发送流式 tag 开始事件
     */
    public void emitTagStart(SseEmitter emitter, String id, String type, String title) {
        if (SseEmitterContext.isDisconnected()) return;
        try {
            Map<String, String> data = Map.of("id", id, "type", type, "title", title);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("tag_start").data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            log.warn("发送 tag_start 失败，客户端可能已断开: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        } catch (IllegalStateException e) {
            log.warn("发送 tag_start 失败，emitter 已完成: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        }
    }

    /**
     * 发送流式 tag 增量事件
     */
    public void emitTagDelta(SseEmitter emitter, String id, String delta) {
        if (SseEmitterContext.isDisconnected()) return;
        try {
            Map<String, String> data = Map.of("id", id, "delta", delta);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("tag_delta").data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            log.warn("发送 tag_delta 失败，客户端可能已断开: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        } catch (IllegalStateException e) {
            log.warn("发送 tag_delta 失败，emitter 已完成: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        }
    }

    /**
     * 发送流式 tag 结束事件（附带完整内容用于持久化）
     */
    public void emitTagEnd(SseEmitter emitter, String id, String type, String title, Object content) {
        try {
            MessageTag tag = new MessageTag(type, content, title, null);
            // 无论是否断开，都收集 tag 用于持久化
            SseEmitterContext.collectTag(tag);
            if (SseEmitterContext.isDisconnected()) return;
            Map<String, Object> data = Map.of("id", id, "tag", tag);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("tag_end").data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            log.warn("发送 tag_end 失败，客户端可能已断开: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        } catch (IllegalStateException e) {
            log.warn("发送 tag_end 失败，emitter 已完成: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        }
    }

    private void emitSuggestions(SseEmitter emitter, List<String> items) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        Map<String, Object> data = Map.of("items", items);
        safeSend(emitter, SseEmitter.event().name("suggestions").data(objectMapper.writeValueAsString(data)));
    }

    private void emitDone(SseEmitter emitter, String conversationId, long totalDuration) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        StreamEventData.DoneEventData data = new StreamEventData.DoneEventData(conversationId, totalDuration);
        safeSend(emitter, SseEmitter.event().name("done").data(objectMapper.writeValueAsString(data)));
    }

    private void emitError(SseEmitter emitter, String code, String message, String stage) {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        try {
            StreamEventData.ErrorEventData data = new StreamEventData.ErrorEventData(code, message, stage);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("发送 error 事件失败: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        }
    }

    private void emitReasoning(SseEmitter emitter, String step, String content, int stepIndex) throws IOException {
        emitReasoning(emitter, step, content, stepIndex, null);
    }

    private void emitReasoning(SseEmitter emitter, String step, String content, int stepIndex, Integer round) throws IOException {
        if (SseEmitterContext.isDisconnected()) {
            return;
        }
        StreamEventData.ReasoningEventData data = new StreamEventData.ReasoningEventData(step, content, stepIndex, round);
        safeSend(emitter, SseEmitter.event().name("reasoning").data(objectMapper.writeValueAsString(data)));
    }

    private void emitStepResult(SseEmitter emitter, String stepName, String stepLabel, long duration, String status, Object result) {
        emitStepResult(emitter, stepName, stepLabel, duration, status, result, null);
    }

    private void emitStepResult(SseEmitter emitter, String stepName, String stepLabel, long duration, String status, Object result, List<Map<String, Object>> collector) {
        // 先收集步骤数据用于持久化（无论连接是否断开）
        if (collector != null) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("stepName", stepName);
            stepMap.put("stepLabel", stepLabel);
            stepMap.put("duration", duration);
            stepMap.put("status", status);
            stepMap.put("result", result);
            collector.add(stepMap);
        }

        // 检查连接状态，如果已断开则不发送
        if (SseEmitterContext.isDisconnected()) {
            return;
        }

        try {
            StreamEventData.StepResultEventData data = new StreamEventData.StepResultEventData(stepName, stepLabel, duration, status, result);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("step_result").data(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            log.warn("发送 step_result 事件失败，客户端可能已断开: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        } catch (IllegalStateException e) {
            log.warn("发送 step_result 事件失败，emitter 已完成: {}", e.getMessage());
            SseEmitterContext.markDisconnected();
        }
    }

    /**
     * 解析推理文本中的【思考】和【观察】步骤，逐步推送 reasoning 事件
     */
    private void emitReasoningSteps(SseEmitter emitter, String reasoningText) {
        emitReasoningSteps(emitter, reasoningText, null);
    }

    private void emitReasoningSteps(SseEmitter emitter, String reasoningText, Integer round) {
        // 按【思考】、【观察】、【行动】分割
        Pattern stepPattern = Pattern.compile("【(思考|观察|行动)】");
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
            lastType = switch (matcher.group(1)) {
                case "思考" -> "thought";
                case "观察" -> "observation";
                case "行动" -> "action";
                default -> "thought";
            };
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
                emitReasoning(emitter, steps.get(i)[0], steps.get(i)[1], i, round);
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

        safeSend(emitter, SseEmitter.event()
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

    /**
     * 处理查数模式：仅生成 SQL，不自动执行
     */
    private void handleQueryMode(String query, String conversationId, SseEmitter emitter) {
        log.info("[QueryMode] ========== 开始处理查数模式 ==========");
        log.info("[QueryMode] 查询: {}", query);
        log.info("[QueryMode] ConversationId: {}", conversationId);

        long startTime = System.currentTimeMillis();
        try {
            SseEmitterContext.setEmitter(emitter);

            // 1. 发送状态事件
            emitStatus(emitter, "sql_generation", "正在生成 SQL...", 1, 2);

            // 2. 流式生成 SQL
            String tagId = UUID.randomUUID().toString();
            emitTagStart(emitter, tagId, "sql_editable", "生成的 SQL");

            StringBuilder sqlBuilder = new StringBuilder();

            // 获取 Schema
            log.info("[QueryMode] 开始获取数据库 Schema");
            String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
            log.info("[QueryMode] Schema 获取成功，长度: {}", schemaInfo.length());

            // 使用 Text2SQLAgent 的 buildSqlPrompt 方法（包含 MCP 增强）
            String systemPrompt = text2SQLAgent.buildSqlPromptWithMCP(query, schemaInfo);
            log.info("[QueryMode] Prompt 构建完成（含 MCP 增强），实际Prompt:\n {}", systemPrompt.toString());

            // 动态创建 ChatClient 并流式生成 SQL
            log.info("[QueryMode] 开始调用 AI 生成 SQL");
            ChatClient chatClient = chatClientFactory.createChatClient("text2sql");
            try {
                // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
                var sqlFlux = chatClient.prompt()
                        .user(systemPrompt)
                        .stream()
                        .content();

                sqlFlux.doOnNext(token -> {
                    sqlBuilder.append(token);
                    emitTagDelta(emitter, tagId, token);
                }).blockLast();

                log.info("[QueryMode] AI 调用完成，生成的 SQL 长度: {}", sqlBuilder.length());
            } catch (Exception e) {
                log.error("[QueryMode] SQL generation failed", e);
                emitError(emitter, "sql_generation_failed", "SQL 生成失败: " + e.getMessage(), "sql_generation");
                return;
            }

            String sql = sqlBuilder.toString();
            log.info("[QueryMode] 原始 SQL:\n \n {}", sql.substring(0, Math.min(1000, sql.length())));

            if (sql.trim().isEmpty()) {
                log.warn("[QueryMode] 生成的 SQL 为空");
                emitError(emitter, "sql_generation_failed", "未能生成有效的 SQL", "sql_generation");
                return;
            }

            // 3. SQL 纠错
            log.info("[QueryMode] 开始 SQL 纠错");
            var correctionResult = sqlCorrectionAgent.correctSQL(sql, query, null);
            String finalSQL = correctionResult.getCorrectedSQL();
            log.info("[QueryMode] SQL 纠错完成");

            // 清理 SQL（去除 markdown 代码块标记）
            finalSQL = finalSQL.trim()
                    .replaceAll("^```sql\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();

            // 4. 发送 tag_end（包含完整 SQL 和可编辑标识）
            Map<String, Object> sqlContent = Map.of(
                    "sql", finalSQL,
                    "editable", true,
                    "query", query
            );

            emitTagEnd(emitter, tagId, "sql_editable", "生成的 SQL", sqlContent);

            // 5. 保存消息
            MessageTag sqlTag = new MessageTag();
            sqlTag.setType("sql_editable");
            sqlTag.setTitle("生成的 SQL");
            sqlTag.setContent(sqlContent);
            conversationService.saveMessage(conversationId, "assistant", "", List.of(sqlTag));
            log.info("[QueryMode] 消息已保存");

            // 6. 发送完成事件
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[QueryMode] 准备发送完成事件，总耗时: {}ms", totalDuration);
            emitDone(emitter, conversationId, totalDuration);
            log.info("[QueryMode] 完成事件已发送");
            log.info("[QueryMode] ========== 查数模式处理完成 ==========");

        } catch (Exception e) {
            log.error("[QueryMode] Processing failed", e);
            emitError(emitter, "query_mode_failed", "查数模式处理失败: " + e.getMessage(), "query_mode");
        } finally {
            SseEmitterContext.clear();
            log.info("[QueryMode] SseEmitterContext 已清理");
        }
    }

    /**
     * 推理内容过滤器：过滤掉 REASONING_START 到 REASONING_END 之间的内容，
     * 并检测 ROUND_N 标记来分割轮次
     */
    private static class ReasoningFilter {
        private final StringBuilder buffer = new StringBuilder();
        private boolean insideReasoning = false;
        private final StringBuilder reasoningContent = new StringBuilder();
        private static final Pattern START_PATTERN = Pattern.compile("<!--\\s*REASONING_START\\s*-->");
        private static final Pattern END_PATTERN = Pattern.compile("<!--\\s*REASONING_END\\s*-->");
        private static final Pattern ROUND_PATTERN = Pattern.compile("<!--\\s*ROUND_(\\d+)\\s*-->");
        private static final String START_TAG = "<!-- REASONING_START -->";
        private static final String END_TAG = "<!-- REASONING_END -->";
        private static final String ROUND_TAG_PREFIX = "<!-- ROUND_";
        private final BiConsumer<String, Integer> onReasoningComplete;
        private int currentRound = 0;

        public ReasoningFilter(BiConsumer<String, Integer> onReasoningComplete) {
            this.onReasoningComplete = onReasoningComplete;
        }

        public String filter(String delta) {
            buffer.append(delta);
            String buffered = buffer.toString();
            StringBuilder output = new StringBuilder();

            while (true) {
                boolean foundTag = false;

                // 优先检测 ROUND 标记（无论在推理块内外）
                if (insideReasoning) {
                    Matcher roundMatcher = ROUND_PATTERN.matcher(buffered);
                    if (roundMatcher.find()) {
                        log.info("[ReasoningFilter] 推理块内匹配到 ROUND 标记: round={}", roundMatcher.group(1));
                        // flush 当前轮的推理内容
                        reasoningContent.append(buffered, 0, roundMatcher.start());
                        if (onReasoningComplete != null && !reasoningContent.isEmpty()) {
                            onReasoningComplete.accept(reasoningContent.toString(), currentRound);
                            reasoningContent.setLength(0);
                        }
                        // 更新轮次号
                        currentRound = Integer.parseInt(roundMatcher.group(1));
                        buffered = buffered.substring(roundMatcher.end());
                        foundTag = true;
                        continue; // 继续检测后续标签
                    }
                } else {
                    Matcher roundMatcher = ROUND_PATTERN.matcher(buffered);
                    if (roundMatcher.find()) {
                        log.info("[ReasoningFilter] 推理块外匹配到 ROUND 标记: round={}", roundMatcher.group(1));
                        // 输出 ROUND 标记之前的内容，吞掉标记本身
                        output.append(buffered, 0, roundMatcher.start());
                        currentRound = Integer.parseInt(roundMatcher.group(1));
                        buffered = buffered.substring(roundMatcher.end());
                        foundTag = true;
                        continue;
                    }
                }

                // 检测 REASONING_START
                if (!insideReasoning) {
                    Matcher startMatcher = START_PATTERN.matcher(buffered);
                    if (startMatcher.find()) {
                        log.info("[ReasoningFilter] 匹配到START标签");
                        // 输出 START 之前的内容
                        output.append(buffered, 0, startMatcher.start());
                        insideReasoning = true;
                        buffered = buffered.substring(startMatcher.end());
                        foundTag = true;
                    }
                }

                // 检测 REASONING_END
                if (insideReasoning) {
                    Matcher endMatcher = END_PATTERN.matcher(buffered);
                    if (endMatcher.find()) {
                        log.info("[ReasoningFilter] 匹配到END标签");
                        // 缓存推理内容（不输出）
                        reasoningContent.append(buffered, 0, endMatcher.start());
                        // 立即发送推理内容（携带当前轮次）
                        if (onReasoningComplete != null && !reasoningContent.isEmpty()) {
                            onReasoningComplete.accept(reasoningContent.toString(), currentRound);
                            reasoningContent.setLength(0);
                        }
                        insideReasoning = false;
                        buffered = buffered.substring(endMatcher.end());
                        foundTag = true;
                    }
                }

                // 如果没有找到更多标签，退出循环
                if (!foundTag) break;
            }

            // 如果在推理块内，需要检查是否有不完整的 END 标签
            if (insideReasoning) {
                int tagIdx = findPotentialTagStartIndex(buffered);
                if (tagIdx >= 0) {
                    // 尾部可能是标签开始，缓存标签头之前的内容到推理，保留尾部
                    reasoningContent.append(buffered, 0, tagIdx);
                    buffer.setLength(0);
                    buffer.append(buffered.substring(tagIdx));
                } else {
                    // 不是标签开始，全部缓存到推理内容中
                    reasoningContent.append(buffered);
                    buffer.setLength(0);
                }
                return output.toString();
            }

            // 检查 buffer 尾部是否有不完整的标签
            int tagIdx = findPotentialTagStartIndex(buffered);
            if (tagIdx >= 0) {
                output.append(buffered, 0, tagIdx);
                buffer.setLength(0);
                buffer.append(buffered.substring(tagIdx));
                return output.toString();
            }

            // 正常内容：输出并清空 buffer
            output.append(buffered);
            buffer.setLength(0);
            return output.toString();
        }

        /**
         * 流结束时调用：如果仍在推理块内（START 已匹配但 END 未出现），强制刷出累积的推理内容
         */
        public void flush() {
            if (insideReasoning && onReasoningComplete != null) {
                // 将 buffer 中残余内容也加入推理
                if (!buffer.isEmpty()) {
                    reasoningContent.append(buffer);
                    buffer.setLength(0);
                }
                if (!reasoningContent.isEmpty()) {
                    log.info("[ReasoningFilter] flush: 强制刷出未闭合的推理内容，长度={}", reasoningContent.length());
                    onReasoningComplete.accept(reasoningContent.toString(), currentRound);
                    reasoningContent.setLength(0);
                }
                insideReasoning = false;
            }
        }

        // 从尾部向前扫描，找到可能是标签开始的位置
        private int findPotentialTagStartIndex(String s) {
            if (s.isEmpty()) return -1;
            // ROUND 标签可能更长，需要考虑 "<!-- ROUND_99 -->" 这样的长度
            int maxScanLen = Math.max(
                    Math.max(START_TAG.length(), END_TAG.length()),
                    ROUND_TAG_PREFIX.length() + 10 // "<!-- ROUND_" + digits + " -->"
            );
            for (int i = s.length() - 1; i >= Math.max(0, s.length() - maxScanLen); i--) {
                String suffix = s.substring(i);
                if (START_TAG.startsWith(suffix) || END_TAG.startsWith(suffix)
                        || ROUND_TAG_PREFIX.startsWith(suffix) || "<!--".startsWith(suffix)) {
                    return i;
                }
            }
            return -1;
        }
    }

}
