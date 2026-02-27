package com.chatbi.controller;

import com.chatbi.config.SandboxToolsConfig;
import com.chatbi.dto.ChatRequest;
import com.chatbi.dto.ChatResponse;
import com.chatbi.dto.ChatResponseWithConversation;
import com.chatbi.dto.ExecuteSqlRequest;
import com.chatbi.dto.NERRequest;
import com.chatbi.dto.NERResponse;
import com.chatbi.service.ChatService;
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

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;
    private final NERService nerService;
    private final Executor sseTaskExecutor;

    @Autowired
    public ChatController(ChatService chatService, ChatStreamService chatStreamService, NERService nerService,
                          @Qualifier("sseTaskExecutor") Executor sseTaskExecutor) {
        this.chatService = chatService;
        this.chatStreamService = chatStreamService;
        this.nerService = nerService;
        this.sseTaskExecutor = sseTaskExecutor;
    }

    /**
     * 统一聊天接口 - 智能识别意图（返回 conversationId）
     * POST /api/chat/message
     */
    @PostMapping("/message")
    public ChatResponseWithConversation chat(@RequestBody ChatRequest request) {
        String message = request.getMessage();
        String conversationId = request.getConversationId();
        int messageLength = message != null ? message.length() : 0;

        if (message == null || message.trim().isEmpty()) {
            log.warn("聊天请求参数无效: conversationId={}, messageLength=0", conversationId);
            return new ChatResponseWithConversation("错误：请提供有效的消息内容", null, conversationId);
        }

        log.info("聊天请求: conversationId={}, messageLength={}", conversationId, messageLength);
        ChatResponseWithConversation response = chatService.smartChatWithConversation(message.trim(), conversationId);
        boolean hasTags = response.getTags() != null && !response.getTags().isEmpty();
        int suggestionCount = response.getSuggestions() != null ? response.getSuggestions().size() : 0;
        boolean hasSuggestionsTag = response.getTags() != null && response.getTags().stream().anyMatch(t -> "suggestions".equals(t.getType()));
        log.info("聊天响应: conversationId={}, hasTags={}, suggestions={}, hasSuggestionsTag={}", response.getConversationId(), hasTags, suggestionCount, hasSuggestionsTag);
        return response;
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

        // 5 分钟超时
        SseEmitter emitter = new SseEmitter(300_000L);

        emitter.onCompletion(() -> log.debug("SSE 连接完成: conversationId={}", conversationId));
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: conversationId={}", conversationId);
            emitter.complete();
        });
        emitter.onError(ex -> log.debug("SSE 连接断开: conversationId={}, error={}", conversationId, ex.getMessage()));

        String trimmedMessage = message.trim();
        String agentType = request.getAgentType();
        CompletableFuture.runAsync(() ->
                chatStreamService.streamChat(trimmedMessage, conversationId, emitter, agentType), sseTaskExecutor);

        return emitter;
    }

    /**
     * Text2SQL 接口
     * POST /api/chat/text2sql
     */
    @PostMapping("/text2sql")
    public ChatResponse text2SQL(@RequestBody ChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.trim().isEmpty()) {
            log.warn("Text2SQL请求参数无效: messageLength=0");
            return new ChatResponse("错误：请提供有效的问题内容", null);
        }
        log.info("Text2SQL请求: messageLength={}", message.length());
        ChatResponse response = chatService.text2SQL(message.trim());
        log.info("Text2SQL响应: hasTags={}", response.getTags() != null && !response.getTags().isEmpty());
        return response;
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
        ChatResponse response = chatService.executeSql(sql);
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
