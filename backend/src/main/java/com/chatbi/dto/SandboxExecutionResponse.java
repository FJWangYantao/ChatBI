package com.chatbi.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class SandboxExecutionResponse {
    private boolean success;
    private String stdout;
    private String stderr;
    private List<String> images; // Base64 strings
}
