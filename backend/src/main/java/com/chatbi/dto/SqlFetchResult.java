package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Text2SQL 查询结果：数据行 + 最终 SQL
 */
@Data
@AllArgsConstructor
public class SqlFetchResult {
    private final List<Map<String, Object>> data;
    private final String sql;
}
