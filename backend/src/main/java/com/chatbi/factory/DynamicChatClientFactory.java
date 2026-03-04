package com.chatbi.factory;

import com.chatbi.context.LLMConfigContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 动态 ChatClient 工厂
 * 根据 ThreadLocal 中的配置或默认配置创建 ChatClient
 */
@Slf4j
@Component
public class DynamicChatClientFactory {

    @Value("${spring.ai.openai.api-key}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.base-url}")
    private String defaultBaseUrl;

    @Value("${chatbi.model.default}")
    private String defaultModel;

    @Value("${chatbi.model.temperature}")
    private double defaultTemperature;

    @Value("${http.proxyHost:#{null}}")
    private String proxyHost;

    @Value("${http.proxyPort:0}")
    private int proxyPort;

    /**
     * 创建 ChatClient，优先使用 ThreadLocal 中的配置
     */
    public ChatClient createChatClient(String agentName) {
        LLMConfigContext.LLMConfig customConfig = LLMConfigContext.get();

        String apiKey;
        String baseUrl;
        String model;

        if (customConfig != null) {
            // 使用前端传来的配置
            apiKey = customConfig.getApiKey();
            baseUrl = customConfig.getBaseUrl() != null ?
                      customConfig.getBaseUrl() : getDefaultBaseUrl(customConfig.getProvider());
            model = customConfig.getModelName();

            log.info("[DynamicChatClientFactory] 使用自定义配置 - Provider: {}, Model: {}, BaseURL: {}",
                     customConfig.getProvider(), model, baseUrl);
        } else {
            // 使用默认配置
            apiKey = defaultApiKey;
            baseUrl = defaultBaseUrl;
            model = defaultModel;

            log.info("[DynamicChatClientFactory] 使用默认配置 - Model: {}, BaseURL: {}", model, baseUrl);
        }

        // 创建 OpenAiApi（暂时不使用代理，因为构造函数签名问题）
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);

        if (proxyHost != null && proxyPort > 0) {
            log.warn("[DynamicChatClientFactory] 代理配置已忽略 - 当前 Spring AI 版本不支持代理配置");
        }

        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .withTemperature(defaultTemperature)
                .build();

        return ChatClient.builder(chatModel)
                .defaultOptions(options)
                .build();
    }

    private String getDefaultBaseUrl(String provider) {
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api";  // 移除 /v1，Spring AI 会自动添加
            default -> defaultBaseUrl;
        };
    }
}
