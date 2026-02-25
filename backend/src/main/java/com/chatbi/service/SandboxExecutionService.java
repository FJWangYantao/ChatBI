package com.chatbi.service;

import com.chatbi.dto.SandboxExecutionRequest;
import com.chatbi.dto.SandboxExecutionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;

@Slf4j
@Service
public class SandboxExecutionService {
    
    // In production, configure this via application.yml
    private static final String SANDBOX_URL = "http://localhost:8003/execute";
    private final RestTemplate restTemplate;

    public SandboxExecutionService() {
        this.restTemplate = new RestTemplate();
    }

    public SandboxExecutionResponse executeCode(String code, String dataJson) {
        log.info("Sending code to Sandbox Service (Length: {} chars)", code.length());
        try {
            SandboxExecutionRequest request = SandboxExecutionRequest.builder()
                    .code(code)
                    .data_json(dataJson)
                    .timeout(30)
                    .build();
            
            ResponseEntity<SandboxExecutionResponse> response = restTemplate.postForEntity(
                SANDBOX_URL, request, SandboxExecutionResponse.class
            );
            
            if (response.getBody() != null) {
                SandboxExecutionResponse result = response.getBody();
                log.info("Sandbox Execution Success: {}", result.isSuccess());
                if (!result.isSuccess() && result.getStderr() != null) {
                    log.error("Sandbox Execution Error: {}", result.getStderr());
                }
                return result;
            } else {
                throw new RuntimeException("Empty response body from Sandbox Service");
            }
            
        } catch (Exception e) {
            log.error("Failed to execute code in sandbox: {}", e.getMessage());
            SandboxExecutionResponse errorResp = new SandboxExecutionResponse();
            errorResp.setSuccess(false);
            errorResp.setStderr("Sandbox Service Connection Error: " + e.getMessage());
            return errorResp;
        }
    }
}
