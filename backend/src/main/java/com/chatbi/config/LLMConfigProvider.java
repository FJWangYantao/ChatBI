package com.chatbi.config;

import com.chatbi.context.LLMConfigContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LLMConfigProvider {

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String defaultBaseUrl;

    public String getApiKey() {
        LLMConfigContext.LLMConfig config = LLMConfigContext.get();
        if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            return config.getApiKey();
        }
        if (defaultApiKey == null || defaultApiKey.isEmpty()) {
            throw new LLMConfigMissingException("未配置 LLM API Key，请在前端配置模型信息");
        }
        return defaultApiKey;
    }

    public String getBaseUrl() {
        LLMConfigContext.LLMConfig config = LLMConfigContext.get();
        if (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
            return config.getBaseUrl();
        }
        if (defaultBaseUrl == null || defaultBaseUrl.isEmpty()) {
            throw new LLMConfigMissingException("未配置 LLM Base URL，请在前端配置模型信息");
        }
        return defaultBaseUrl;
    }

    public static class LLMConfigMissingException extends RuntimeException {
        public LLMConfigMissingException(String message) {
            super(message);
        }
    }
}
