package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外键关系
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ForeignKeyMetadata {
    /**
     * 本表列名
     */
    private String columnName;

    /**
     * 引用表名
     */
    private String referencedTableName;

    /**
     * 引用列名
     */
    private String referencedColumnName;

    /**
     * 约束名
     */
    private String constraintName;
}
