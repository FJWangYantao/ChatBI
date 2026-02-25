package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SQL 查询结果
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class  QueryResult {
    /**
     * 列名列表
     */
    private List<String> columns;

    /**
     * 数据行列表
     */
    private List<Map<String, Object>> rows;

    /**
     * 总行数
     */
    private Integer totalRows;

    /**
     * 执行时间（毫秒）
     */
    private Long executionTime;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息（如果失败）
     */
    private String error;
}
