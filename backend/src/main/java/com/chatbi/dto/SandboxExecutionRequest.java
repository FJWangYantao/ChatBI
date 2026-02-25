package com.chatbi.dto;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxExecutionRequest {
    private String code;
    private String data_json;
    private Integer timeout;
}
