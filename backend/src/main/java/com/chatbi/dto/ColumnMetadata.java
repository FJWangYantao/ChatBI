package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 列元数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ColumnMetadata {
    /**
     * 列名
     */
    private String columnName;

    /**
     * 数据类型 (VARCHAR, INT, etc.)
     */
    private String dataType;

    /**
     * 长度
     */
    private Integer columnSize;

    /**
     * 是否可空
     */
    private Boolean nullable;

    /**
     * 默认值
     */
    private String columnDefault;

    /**
     * 列注释
     */
    private String columnComment;

    /**
     * 是否主键
     */
    private Boolean isPrimaryKey;
}
