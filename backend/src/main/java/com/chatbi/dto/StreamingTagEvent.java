package com.chatbi.dto;

/**
 * 流式 tag 事件记录类
 * 用于在工具函数内部向 SSE 发送流式代码块（sql/code）
 */
public record StreamingTagEvent(
        String eventType,  // "start", "delta", "end"
        String id,
        String type,       // "sql", "code"
        String title,
        String delta,
        Object content
) {
    public static StreamingTagEvent start(String id, String type, String title) {
        return new StreamingTagEvent("start", id, type, title, null, null);
    }

    public static StreamingTagEvent delta(String id, String delta) {
        return new StreamingTagEvent("delta", id, null, null, delta, null);
    }

    public static StreamingTagEvent end(String id, String type, String title, Object content) {
        return new StreamingTagEvent("end", id, type, title, null, content);
    }
}
