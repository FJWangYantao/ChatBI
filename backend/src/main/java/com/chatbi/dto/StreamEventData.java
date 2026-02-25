package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SSE 流式事件 DTO 集合
 */
public class StreamEventData {

    /**
     * 阶段状态事件
     * event: status
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatusEventData {
        private String stage;
        private String message;
        private int progress;
        private int totalSteps;
    }

    /**
     * 文本增量事件
     * event: text_delta
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TextDeltaEventData {
        private String delta;
    }

    /**
     * 完成事件
     * event: done
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DoneEventData {
        private String conversationId;
        private long totalDuration;
    }

    /**
     * 错误事件
     * event: error
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ErrorEventData {
        private String code;
        private String message;
        private String stage;
    }
}
