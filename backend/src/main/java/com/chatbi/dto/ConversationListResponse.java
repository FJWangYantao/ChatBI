package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话列表项DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationListResponse {
    private String conversationId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
