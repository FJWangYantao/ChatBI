package com.chatbi.dto;

import lombok.Data;

/**
 * 文件导入请求
 */
@Data
public class FileImportRequest {
    /**
     * 数据源ID（导入到该数据源对应的数据库）
     */
    private Long dataSourceId;

    /**
     * 表名（创建的新表名称）
     */
    private String tableName;

    /**
     * 是否跳过已存在的表
     */
    private boolean skipIfExists = true;

    /**
     * 第一行是否为标题行
     */
    private boolean firstRowAsHeader = true;
}
