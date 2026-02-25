package com.chatbi.service;

import com.chatbi.dto.IntentRecognitionRequest;
import com.chatbi.dto.IntentRecognitionResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图识别服务
 * 调用 Python 微服务进行意图识别
 */
@Service
public class IntentRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionService.class);

    @Value("${intent-service.base-url:http://localhost:8001}")
    private String baseUrl;

    @Value("${intent-service.timeout:3000}")
    private int timeout;

    @Value("${intent-service.enabled:true}")
    private boolean enabled;

    @Value("${intent-service.fallback-to-ai:true}")
    private boolean fallbackToAi;

    private final RestTemplate restTemplate;

    // 简单的本地缓存（可选）
    private final Map<String, IntentRecognitionResponse> cache = new HashMap<>();

    public IntentRecognitionService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 识别意图
     *
     * @param request 意图识别请求
     * @return 意图识别响应
     */
    public IntentRecognitionResponse recognize(IntentRecognitionRequest request) {
        // 检查服务是否启用
        if (!enabled) {
            logger.debug("Intent service is disabled, returning fallback response");
            return createFallbackResponse(request.getText());
        }

        // 检查缓存
        String cacheKey = request.getText();
        if (cache.containsKey(cacheKey)) {
            IntentRecognitionResponse cached = cache.get(cacheKey);
            String traceId = MDC.get("traceId");
            logger.info("[STRUCT] event=intent_recognition source=cache category={} subtype={} traceId={}",
                    cached.getCategory(), cached.getSubtype(), traceId != null ? traceId : "-");
            return cached;
        }

        try {
            String url = baseUrl + "/predict";
            long startTime = System.currentTimeMillis();
            logger.debug("Calling intent service: {} with text: {}", url, request.getText());

            ResponseEntity<IntentRecognitionResponse> response = restTemplate.postForEntity(
                    url,
                    request,
                    IntentRecognitionResponse.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                IntentRecognitionResponse result = response.getBody();
                long duration = System.currentTimeMillis() - startTime;

                // 结构化日志: 意图识别结果
                String traceId = MDC.get("traceId");
                logger.info("[STRUCT] event=intent_recognition service=intent_api url={} category={} subtype={} confidence={} duration={}ms traceId={}",
                        url, result.getCategory(), result.getSubtype(), result.getCategoryConfidence(), duration,
                        traceId != null ? traceId : "-");

                // 缓存结果
                if (cache.size() < 1000) {
                    cache.put(cacheKey, result);
                }

                logger.debug("Intent recognition result: {}", result);
                return result;
            } else {
                String traceId = MDC.get("traceId");
                logger.warn("[STRUCT] event=intent_recognition status=failed reason=bad_status statusCode={} traceId={}",
                        response.getStatusCode(), traceId != null ? traceId : "-");
                return handleFailure(request.getText(), "Unexpected response status");
            }

        } catch (ResourceAccessException e) {
            String traceId = MDC.get("traceId");
            logger.error("[STRUCT] event=intent_recognition status=failed reason=connection_error error={} traceId={}",
                    e.getMessage(), traceId != null ? traceId : "-");
            logger.error("Failed to connect to intent service: {}", e.getMessage());
            return handleFailure(request.getText(), "Service unavailable");

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            logger.error("Intent service returned error: {} - {}", e.getStatusCode(), e.getMessage());
            return handleFailure(request.getText(), "Service error: " + e.getStatusCode());

        } catch (Exception e) {
            logger.error("Unexpected error during intent recognition: {}", e.getMessage(), e);
            return handleFailure(request.getText(), "Unexpected error");
        }
    }

    /**
     * 识别意图（简化方法）
     *
     * @param text 待识别的文本
     * @return 意图识别响应
     */
    public IntentRecognitionResponse recognize(String text) {
        return recognize(new IntentRecognitionRequest(text));
    }

    /**
     * 处理失败情况
     */
    private IntentRecognitionResponse handleFailure(String text, String reason) {
        String traceId = MDC.get("traceId");
        logger.warn("[STRUCT] event=intent_recognition status=fallback reason={} traceId={}", reason, traceId != null ? traceId : "-");
        logger.warn("Intent recognition failed for '{}': {}", text, reason);

        if (fallbackToAi) {
            IntentRecognitionResponse fallback = createFallbackResponse(text);
            logger.info("[STRUCT] event=intent_recognition source=fallback category={} subtype=UNKNOWN_QUERY traceId={}",
                    fallback.getCategory(), traceId != null ? traceId : "-");
            return fallback;
        } else {
            throw new RuntimeException("Intent recognition service unavailable: " + reason);
        }
    }

    /**
     * 创建降级响应
     * 当服务不可用时返回一个保守的预测
     */
    private IntentRecognitionResponse createFallbackResponse(String text) {
        IntentRecognitionResponse response = new IntentRecognitionResponse();
        response.setText(text);
        response.setCategory("GENERAL_CHAT"); // 安全默认值，避免误触发重流程
        response.setCategoryCn("日常对话");
        response.setCategoryConfidence(0.5); // 低置信度
        response.setSubtype("UNKNOWN_QUERY");
        response.setSubtypeCn("未知查询");
        response.setSubtypeConfidence(0.5);
        return response;
    }

    /**
     * 检查服务健康状态
     *
     * @return 服务是否健康
     */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }

        try {
            String url = baseUrl + "/health";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    org.springframework.http.HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            logger.debug("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        cache.clear();
        logger.info("Intent recognition cache cleared");
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        logger.info("Intent service enabled: {}", enabled);
    }

    public boolean isFallbackToAi() {
        return fallbackToAi;
    }

    public void setFallbackToAi(boolean fallbackToAi) {
        this.fallbackToAi = fallbackToAi;
    }
}
