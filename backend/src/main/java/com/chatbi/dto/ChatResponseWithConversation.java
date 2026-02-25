package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应（包含对话ID）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseWithConversation {
    private String reply;
    private List<MessageTag> tags;
    private String conversationId;
    private ChatResponse.IntentInfo intentInfo;
    private List<String> suggestions;

    /**
     * 构造函数（兼容旧代码）
     */
    public ChatResponseWithConversation(String reply, List<MessageTag> tags, String conversationId) {
        this.reply = reply;
        this.tags = tags;
        this.conversationId = conversationId;
        this.intentInfo = null;
        this.suggestions = null;
    }
}
