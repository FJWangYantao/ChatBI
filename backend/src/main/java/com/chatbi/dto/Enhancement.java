package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prompt 增强项
 * 记录单个增强操作的详细信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Enhancement {
    /**
     * 增强类型（SCHEMA, TIME, AGGREGATION, HISTORY, FRAMEWORK, COMPARISON 等）
     */
    private String type;

    /**
     * 原始内容
     */
    private String original;

    /**
     * 增强后的内容
     */
    private String enhanced;

    /**
     * 增强原因说明
     */
    private String reason;

    /**
     * 增强数据（可选，用于存储复杂对象如NER结果）
     */
    private Object data;

    /**
     * 兼容性构造函数（不包含data字段）
     */
    public Enhancement(String type, String original, String enhanced, String reason) {
        this.type = type;
        this.original = original;
        this.enhanced = enhanced;
        this.reason = reason;
        this.data = null;
    }
}
