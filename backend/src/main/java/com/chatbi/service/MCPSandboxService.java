package com.chatbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MCPSandboxService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mcp.sandbox.server.url:http://localhost:8003}")
    private String sandboxServerUrl;

    @Value("${mcp.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    public MCPSandboxService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 Python 代码
     */
    public Map<String, Object> executeCode(String code, String dataJson, int timeout) {
        if (!sandboxEnabled) {
            return Map.of("success", false, "stderr", "MCP Sandbox 服务未启用");
        }

        try {
            String url = sandboxServerUrl + "/tools/execute_code";

            Map<String, Object> request = new HashMap<>();
            request.put("code", code);
            if (dataJson != null) request.put("data_json", dataJson);
            request.put("timeout", timeout);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            log.info("[MCPSandbox] Executing code, length={}", code.length());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "stderr", "Sandbox 服务返回异常");

        } catch (Exception e) {
            log.error("[MCPSandbox] 执行代码失败: {}", e.getMessage());
            return Map.of("success", false, "stderr", "Sandbox 连接失败: " + e.getMessage());
        }
    }

    /**
     * 预检代码安全性
     */
    public Map<String, Object> validateCode(String code) {
        if (!sandboxEnabled) {
            return Map.of("valid", false, "errors", List.of("MCP Sandbox 服务未启用"));
        }

        try {
            String url = sandboxServerUrl + "/tools/validate_code";
            Map<String, String> request = Map.of("code", code);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("valid", false, "errors", List.of("验证请求失败"));

        } catch (Exception e) {
            log.error("[MCPSandbox] 代码验证失败: {}", e.getMessage());
            return Map.of("valid", false, "errors", List.of(e.getMessage()));
        }
    }

    /**
     * 获取沙盒环境信息
     */
    public Map<String, Object> getSandboxInfo() {
        if (!sandboxEnabled) {
            return Map.of("status", "disabled");
        }

        try {
            String url = sandboxServerUrl + "/tools/sandbox_info";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("status", "unavailable");

        } catch (Exception e) {
            log.error("[MCPSandbox] 获取沙盒信息失败: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 健康检查
     */
    public boolean isHealthy() {
        if (!sandboxEnabled) return false;
        try {
            String url = sandboxServerUrl + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
