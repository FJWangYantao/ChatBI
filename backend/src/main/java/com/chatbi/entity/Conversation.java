package com.chatbi.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话实体类
 * 对应数据库表: conversations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {
    private Long id;
    private String conversationId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
