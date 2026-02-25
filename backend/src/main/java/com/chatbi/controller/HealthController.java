package com.chatbi.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务健康检查控制器
 * GET /api/health/services — 返回各服务的连接状态
 */
@Slf4j
@RestController
@RequestMapping("/health")
public class HealthController {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${intent-service.base-url:http://localhost:8001}")
    private String intentServiceUrl;

    @Value("${ner-model-service.base-url:http://localhost:8002}")
    private String nerServiceUrl;

    @Value("${sandbox-service.base-url:http://localhost:8003}")
    private String sandboxServiceUrl;

    @GetMapping("/services")
    public ResponseEntity<Map<String, Object>> checkServices() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 后端自身（能到这就说明正常）
        result.put("backend", Map.of("status", "ok", "port", 8080));

        // Intent Service (port 8001)
        result.put("intent", checkService(intentServiceUrl + "/health", 8001));

        // NER Service (port 8002)
        result.put("ner", checkService(nerServiceUrl + "/health", 8002));

        // Sandbox Service (port 8003)
        result.put("sandbox", checkService(sandboxServiceUrl + "/health", 8003));

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> checkService(String url, int port) {
        try {
            restTemplate.getForEntity(url, String.class);
            return Map.of("status", "ok", "port", port);
        } catch (Exception e) {
            return Map.of("status", "error", "port", port, "message", e.getClass().getSimpleName());
        }
    }
}
