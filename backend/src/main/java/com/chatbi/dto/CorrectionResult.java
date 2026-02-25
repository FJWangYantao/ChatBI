package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 纠错结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrectionResult {

    /**
     * 原始 SQL
     */
    private String originalSQL;

    /**
     * 纠错后的 SQL（若无错误则与原始相同）
     */
    private String correctedSQL;

    /**
     * 是否进行了修正
     */
    private boolean corrected;

    /**
     * 纠错项列表
     */
    @Builder.Default
    private List<CorrectionItem> corrections = new ArrayList<>();

    /**
     * 验证错误列表（未修正的）
     */
    @Builder.Default
    private List<ValidationError> validationErrors = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorrectionItem {
        private String errorType;
        private String location;
        private String suggestion;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        private String type;
        private String message;
        private String location;
    }
}
