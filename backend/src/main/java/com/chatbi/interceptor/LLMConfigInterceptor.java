package com.chatbi.interceptor;

import com.chatbi.context.LLMConfigContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * LLM 配置拦截器
 * 从请求头中提取 LLM 配置并存储到 ThreadLocal
 */
@Slf4j
@Component
public class LLMConfigInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String provider = request.getHeader("X-LLM-Provider");
        String apiKey = request.getHeader("X-LLM-API-Key");
        String model = request.getHeader("X-LLM-Model");
        String baseUrl = request.getHeader("X-LLM-Base-URL");

        // 打印所有请求头用于调试
        log.info("[LLMConfigInterceptor] 请求路径: {}", request.getRequestURI());
        log.info("[LLMConfigInterceptor] X-LLM-Provider: {}", provider);
        log.info("[LLMConfigInterceptor] X-LLM-API-Key: {}", apiKey != null ? "***" : null);
        log.info("[LLMConfigInterceptor] X-LLM-Model: {}", model);
        log.info("[LLMConfigInterceptor] X-LLM-Base-URL: {}", baseUrl);

        if (provider != null && apiKey != null && model != null) {
            LLMConfigContext.LLMConfig config = new LLMConfigContext.LLMConfig();
            config.setProvider(provider);
            config.setApiKey(apiKey);
            config.setModelName(model);
            config.setBaseUrl(baseUrl);
            LLMConfigContext.set(config);

            log.info("[LLMConfigInterceptor] ✅ 检测到自定义LLM配置 - Provider: {}, Model: {}, BaseURL: {}, 原始BaseURL: {}",
                     provider, model, baseUrl != null ? baseUrl : "null", baseUrl);
        } else {
            log.info("[LLMConfigInterceptor] ⚠️ 未检测到完整的自定义LLM配置，将使用默认配置");
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LLMConfigContext.clear();
    }
}
