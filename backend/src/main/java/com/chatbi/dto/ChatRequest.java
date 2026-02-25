package com.chatbi.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    private String conversationId; // 可选，用于多轮对话
    private String agentType; // 可选，强制指定agent类型：DATA_ANALYSIS, DIAGNOSTIC_ANALYSIS, REPORT
}
