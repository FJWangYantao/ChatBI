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

    /**
     * 推理步骤事件
     * event: reasoning
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ReasoningEventData {
        private String step;       // "thought" 或 "observation"
        private String content;    // 推理内容
        private int stepIndex;     // 步骤序号（从0开始）
    }

    /**
     * 步骤结果事件
     * event: step_result
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StepResultEventData {
        private String stepName;   // 步骤标识：intent_detection, prompt_enhancement, clarification, data_analysis, suggestions
        private String stepLabel;  // 步骤中文名
        private long duration;     // 耗时（毫秒）
        private String status;     // "success" 或 "skipped"
        private Object result;     // 步骤结果摘要
    }
}
