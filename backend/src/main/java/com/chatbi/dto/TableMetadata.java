package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表元数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TableMetadata {
    /**
     * 表名
     */
    private String tableName;

    /**
     * 表注释
     */
    private String tableComment;

    /**
     * 列列表
     */
    private List<ColumnMetadata> columns;

    /**
     * 外键关系（支持复杂查询）
     */
    private List<ForeignKeyMetadata> foreignKeys;
}
