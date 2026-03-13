package com.chatbi.controller;

import com.chatbi.config.SandboxToolsConfig;
import com.chatbi.context.LLMConfigContext;
import com.chatbi.dto.ChatRequest;
import com.chatbi.dto.ChatResponse;
import com.chatbi.dto.ChatResponseWithConversation;
import com.chatbi.dto.ExecuteSqlRequest;
import com.chatbi.dto.NERRequest;
import com.chatbi.dto.NERResponse;
import com.chatbi.service.SqlExecutionService;
import com.chatbi.service.ChatStreamService;
import com.chatbi.service.NERService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final SqlExecutionService sqlExecutionService;
    private final ChatStreamService chatStreamService;
    private final NERService nerService;
    private final Executor sseTaskExecutor;

    @Autowired
    public ChatController(SqlExecutionService sqlExecutionService, ChatStreamService chatStreamService, NERService nerService,
                          @Qualifier("sseTaskExecutor") Executor sseTaskExecutor) {
        this.sqlExecutionService = sqlExecutionService;
        this.chatStreamService = chatStreamService;
        this.nerService = nerService;
        this.sseTaskExecutor = sseTaskExecutor;
    }

    /**
     * 流式聊天接口 (SSE)
     * POST /api/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request, HttpServletResponse response) {
        // 禁用所有中间层缓冲，确保 SSE 事件实时推送
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");

        String message = request.getMessage();
        String conversationId = request.getConversationId();

        if (message == null || message.trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"code\":\"INVALID_REQUEST\",\"message\":\"请提供有效的消息内容\",\"stage\":\"fatal\"}"));
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }

        // 10 分钟超时（复杂分析可能需要更长时间）
        SseEmitter emitter = new SseEmitter(600_000L);

        emitter.onCompletion(() -> log.debug("SSE 连接完成: conversationId={}", conversationId));
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: conversationId={}", conversationId);
            emitter.complete();
        });
        emitter.onError(ex -> log.debug("SSE 连接断开: conversationId={}, error={}", conversationId, ex.getMessage()));

        String trimmedMessage = message.trim();
        String agentType = request.getAgentType();

        // 保存当前线程的 LLM 配置，以便在异步线程中使用
        LLMConfigContext.LLMConfig llmConfig = LLMConfigContext.get();

        CompletableFuture.runAsync(() -> {
            try {
                // 在异步线程中恢复 LLM 配置
                if (llmConfig != null) {
                    LLMConfigContext.set(llmConfig);
                    log.info("[ChatController] 异步线程中恢复LLM配置: Provider={}, Model={}",
                             llmConfig.getProvider(), llmConfig.getModelName());
                }
                chatStreamService.streamChat(trimmedMessage, conversationId, emitter, agentType);
            } finally {
                // 清理 ThreadLocal
                LLMConfigContext.clear();
            }
        }, sseTaskExecutor);

        return emitter;
    }

    /**
     * 执行 SQL 接口
     * POST /api/chat/execute-sql
     */
    @PostMapping("/execute-sql")
    public ChatResponse executeSql(@RequestBody ExecuteSqlRequest request) {
        String sql = request.getSql();
        int sqlLength = sql != null ? sql.length() : 0;
        log.info("执行SQL请求: sqlLength={}", sqlLength);
        ChatResponse response = sqlExecutionService.executeSql(sql);
        log.info("执行SQL响应: hasTags={}", response.getTags() != null && !response.getTags().isEmpty());
        return response;
    }

    /**
     * 健康检查
     * GET /api/chat/health
     */
    @GetMapping("/health")
    public String health() {
        return "ChatBI Backend is running!";
    }

    /**
     * 大数据集分页获取接口
     * GET /api/chat/data/{refId}?offset=0&limit=100
     */
    @GetMapping("/data/{refId}")
    public Map<String, Object> getPagedData(
            @PathVariable String refId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("分页数据请求: refId={}, offset={}, limit={}", refId, offset, limit);
        return SandboxToolsConfig.getPagedData(refId, offset, limit);
    }

    /**
     * NER 实体提取接口
     * POST /api/chat/ner
     */
    @PostMapping("/ner")
    public NERResponse ner(@RequestBody NERRequest request) {
        String text = request.getText();
        log.info("NER请求: textLength={}", text != null ? text.length() : 0);
        NERResponse response = nerService.extractEntities(text);
        log.info("NER响应: entityCount={}", response.getEntities() != null ? response.getEntities().size() : 0);
        return response;
    }
}
