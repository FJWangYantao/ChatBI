package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    private String messageId;
    private String conversationId;
    private String role;
    private String content;
    private List<MessageTag> tags;
    private List<Map<String, Object>> steps; // 处理步骤结果
    private LocalDateTime createdAt;
}
