package com.chatbi.dto;

import lombok.Data;

@Data
public class ExecuteSqlRequest {
    private String sql;
    private String conversationId;
}
