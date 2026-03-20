package com.chatbi.service;

import com.chatbi.dto.*;
import com.chatbi.entity.Conversation;
import com.chatbi.entity.Message;
import com.chatbi.entity.QueryHistory;
import com.chatbi.repository.ConversationRepository;
import com.chatbi.repository.MessageRepository;
import com.chatbi.repository.QueryHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话管理Service
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QueryHistoryRepository queryHistoryRepository;

    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            QueryHistoryRepository queryHistoryRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.queryHistoryRepository = queryHistoryRepository;
    }

    /**
     * 创建新对话
     */
    public ConversationDTO createConversation(String title) {
        Conversation conversation = Conversation.builder()
                .title(title != null ? title : "新对话")
                .build();

        Conversation saved = conversationRepository.save(conversation);
        log.info("Created new conversation: {}", saved.getConversationId());

        return convertToDTO(saved);
    }

    /**
     * 获取对话详情（包含消息列表）
     */
    public ConversationDTO getConversation(String conversationId) {
        Conversation conversation = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        List<Message> messages = messageRepository.findByConversationId(conversationId);

        return ConversationDTO.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .messages(messages.stream()
                        .map(this::convertMessageToDTO)
                        .collect(Collectors.toList()))
                .build();
    }

    /**
     * 获取对话列表
     */
    public List<ConversationListResponse> listConversations() {
        List<Conversation> conversations = conversationRepository.findAll();

        return conversations.stream()
                .map(conv -> ConversationListResponse.builder()
                        .conversationId(conv.getConversationId())
                        .title(conv.getTitle())
                        .createdAt(conv.getCreatedAt())
                        .updatedAt(conv.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 更新对话标题
     */
    public ConversationDTO updateConversation(String conversationId, String title) {
        Conversation conversation = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        conversation.setTitle(title);
        Conversation updated = conversationRepository.save(conversation);

        log.info("Updated conversation title: {}", conversationId);

        return convertToDTO(updated);
    }

    /**
     * 删除对话（级联删除消息和查询历史）
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        // 先删子表，再删主表
        queryHistoryRepository.deleteByConversationId(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteByConversationId(conversationId);
        log.info("Deleted conversation with cascade: {}", conversationId);
    }

    /**
     * 保存消息
     */
    public MessageDTO saveMessage(String conversationId, String role, String content, List<MessageTag> tags) {
        return saveMessage(conversationId, role, content, tags, null);
    }

    /**
     * 保存消息（含处理步骤）
     */
    public MessageDTO saveMessage(String conversationId, String role, String content, List<MessageTag> tags, List<Map<String, Object>> steps) {
        // 确保对话存在
        conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        Message message = Message.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .tags(tags)
                .steps(steps)
                .build();

        Message saved = messageRepository.save(message);
        log.info("消息已保存: messageId={}, conversationId={}", saved.getMessageId(), conversationId);

        return convertMessageToDTO(saved);
    }

    /**
     * 保存消息（完整版，含所有结构化数据）
     */
    public MessageDTO saveMessage(String conversationId, String role, String content,
                                   List<MessageTag> tags, List<Map<String, Object>> steps,
                                   Map<String, Object> intentInfo, List<String> suggestions,
                                   List<Map<String, Object>> reasoningSteps) {
        conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        Message message = Message.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .tags(tags)
                .steps(steps)
                .intentInfo(intentInfo)
                .suggestions(suggestions)
                .reasoningSteps(reasoningSteps)
                .build();

        Message saved = messageRepository.save(message);
        log.info("消息已保存(完整): messageId={}, conversationId={}", saved.getMessageId(), conversationId);

        return convertMessageToDTO(saved);
    }

    /**
     * 保存查询历史
     */
    public void saveQueryHistory(String messageId, String conversationId, String sqlText,
                                  Long executionTime, Integer rowsAffected, Boolean success, String errorMessage) {
        QueryHistory queryHistory = QueryHistory.builder()
                .messageId(messageId)
                .conversationId(conversationId)
                .sqlText(sqlText)
                .executionTime(executionTime)
                .rowsAffected(rowsAffected)
                .success(success)
                .errorMessage(errorMessage)
                .build();

        queryHistoryRepository.save(queryHistory);
        log.debug("Saved query history for message: {}", messageId);
    }

    /**
     * 获取对话的消息列表
     */
    public List<MessageDTO> getMessages(String conversationId) {
        List<Message> messages = messageRepository.findByConversationId(conversationId);
        return messages.stream()
                .map(this::convertMessageToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取查询历史
     */
    public List<QueryHistory> getQueryHistory(String conversationId) {
        return queryHistoryRepository.findByConversationId(conversationId);
    }

    /**
     * 更新消息反馈
     */
    public void updateMessageFeedback(String messageId, String feedback) {
        messageRepository.updateFeedback(messageId, feedback);
        log.info("消息反馈已更新: messageId={}, feedback={}", messageId, feedback);
    }

    private ConversationDTO convertToDTO(Conversation conversation) {
        return ConversationDTO.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    private MessageDTO convertMessageToDTO(Message message) {
        return MessageDTO.builder()
                .messageId(message.getMessageId())
                .conversationId(message.getConversationId())
                .role(message.getRole())
                .content(message.getContent())
                .tags(message.getTags())
                .steps(message.getSteps())
                .intentInfo(message.getIntentInfo())
                .suggestions(message.getSuggestions())
                .reasoningSteps(message.getReasoningSteps())
                .feedback(message.getFeedback())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
