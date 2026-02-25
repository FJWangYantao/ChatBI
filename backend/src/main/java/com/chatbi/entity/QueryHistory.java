package com.chatbi.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 查询历史实体类
 * 对应数据库表: query_history
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryHistory {
    private Long id;
    private String messageId;
    private String conversationId;
    private String sqlText;
    private Long executionTime;
    private Integer rowsAffected;
    private Boolean success;
    private String errorMessage;
    private LocalDateTime createdAt;
}
