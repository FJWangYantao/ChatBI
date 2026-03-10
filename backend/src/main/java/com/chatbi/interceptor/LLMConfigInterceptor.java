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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String provider = request.getHeader("X-LLM-Provider");
        String apiKey = request.getHeader("X-LLM-API-Key");
        String model = request.getHeader("X-LLM-Model");
        String baseUrl = request.getHeader("X-LLM-Base-URL");

        // 只对聊天接口强制要求前端配置
        String requestPath = request.getRequestURI();
        boolean isChatEndpoint = requestPath.contains("/chat/stream") ||
                                 requestPath.contains("/chat/message") ||
                                 requestPath.contains("/chat/execute-sql");

        if (isChatEndpoint) {
            if (provider == null || apiKey == null || model == null) {
                log.error("[LLMConfigInterceptor] ❌ 聊天接口必须提供完整的LLM配置 (Provider, API Key, Model)");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请先在设置中配置 LLM 供应商和 API Key\"}");
                return false;
            }

            LLMConfigContext.LLMConfig config = new LLMConfigContext.LLMConfig();
            config.setProvider(provider);
            config.setApiKey(apiKey);
            config.setModelName(model);
            config.setBaseUrl(baseUrl);
            LLMConfigContext.set(config);

            log.info("[LLMConfigInterceptor] ✅ 使用前端配置 - Provider: {}, Model: {}, BaseURL: {}",
                     provider, model, baseUrl);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LLMConfigContext.clear();
    }
}
