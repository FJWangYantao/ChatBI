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
    private Map<String, Object> intentInfo;           // 意图识别结果
    private List<String> suggestions;                  // 推荐后续问题
    private List<Map<String, Object>> reasoningSteps;  // 推理链
    private String feedback;                           // 用户反馈: like/dislike
    private LocalDateTime createdAt;
}
