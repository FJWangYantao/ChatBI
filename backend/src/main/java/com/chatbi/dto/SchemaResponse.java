package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema API 响应
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SchemaResponse {
    /**
     * 数据库名
     */
    private String databaseName;

    /**
     * 表列表
     */
    private List<TableMetadata> tables;

    /**
     * AI 友好的格式化文本
     */
    private String formattedForAI;
}
