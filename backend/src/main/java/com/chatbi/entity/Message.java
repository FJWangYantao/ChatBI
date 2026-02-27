package com.chatbi.entity;

import com.chatbi.dto.MessageTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息实体类
 * 对应数据库表: messages
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private Long id;
    private String messageId;
    private String conversationId;
    private String role; // "user", "assistant", "system"
    private String content;
    private List<MessageTag> tags;
    private List<Map<String, Object>> steps; // 处理步骤结果
    private LocalDateTime createdAt;
}
