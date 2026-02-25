package com.chatbi.controller;

import com.chatbi.dto.*;
import com.chatbi.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对话管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 创建新对话
     */
    @PostMapping
    public ResponseEntity<ConversationDTO> createConversation(@RequestBody CreateConversationRequest request) {
        log.info("创建新对话: title={}", request.getTitle());
        ConversationDTO conversation = conversationService.createConversation(request.getTitle());
        log.info("创建新对话完成: conversationId={}", conversation.getConversationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    /**
     * 获取对话列表
     */
    @GetMapping
    public ResponseEntity<List<ConversationListResponse>> listConversations() {
        List<ConversationListResponse> conversations = conversationService.listConversations();
        log.info("获取对话列表: count={}", conversations.size());
        return ResponseEntity.ok(conversations);
    }

    /**
     * 获取对话详情（包含消息列表）
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDTO> getConversation(@PathVariable String conversationId) {
        log.info("获取对话详情: conversationId={}", conversationId);
        try {
            ConversationDTO conversation = conversationService.getConversation(conversationId);
            log.info("获取对话详情完成: conversationId={}, messageCount={}",
                    conversationId, conversation.getMessages() != null ? conversation.getMessages().size() : 0);
            return ResponseEntity.ok(conversation);
        } catch (RuntimeException e) {
            log.error("获取对话失败: conversationId={}, error={}", conversationId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 更新对话标题
     */
    @PutMapping("/{conversationId}")
    public ResponseEntity<ConversationDTO> updateConversation(
            @PathVariable String conversationId,
            @RequestBody UpdateConversationRequest request) {
        log.info("更新对话标题: conversationId={}, newTitle={}", conversationId, request.getTitle());
        try {
            ConversationDTO conversation = conversationService.updateConversation(conversationId, request.getTitle());
            log.info("更新对话标题完成: conversationId={}", conversationId);
            return ResponseEntity.ok(conversation);
        } catch (RuntimeException e) {
            log.error("更新对话失败: conversationId={}, error={}", conversationId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 删除对话
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        log.info("删除对话: conversationId={}", conversationId);
        conversationService.deleteConversation(conversationId);
        log.info("删除对话完成: conversationId={}", conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取对话的消息列表
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageDTO>> getMessages(@PathVariable String conversationId) {
        log.info("获取对话消息: conversationId={}", conversationId);
        try {
            List<MessageDTO> messages = conversationService.getMessages(conversationId);
            log.info("获取对话消息完成: conversationId={}, messageCount={}", conversationId, messages.size());
            return ResponseEntity.ok(messages);
        } catch (RuntimeException e) {
            log.error("获取消息失败: conversationId={}, error={}", conversationId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
