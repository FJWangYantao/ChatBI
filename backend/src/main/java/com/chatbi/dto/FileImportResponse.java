package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件导入响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileImportResponse {
    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 创建的表名
     */
    private String tableName;

    /**
     * 导入的行数
     */
    private int rowsImported;

    /**
     * 创建的列数
     */
    private int columnsCreated;
}
