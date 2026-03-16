package com.chatbi.factory;

import com.chatbi.context.LLMConfigContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 动态 ChatClient 工厂
 * 根据 ThreadLocal 中的配置或默认配置创建 ChatClient
 * 优化了 HTTP 客户端配置以支持更高并发
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
     * 创建 ChatClient，必须使用 ThreadLocal 中的前端配置
     */
    public ChatClient createChatClient(String agentName) {
        LLMConfigContext.LLMConfig customConfig = LLMConfigContext.get();

        if (customConfig == null) {
            throw new IllegalStateException("未检测到前端 LLM 配置，请先在设置中配置 LLM 供应商和 API Key");
        }

        // 使用前端传来的配置
        String apiKey = customConfig.getApiKey();
        String baseUrl = customConfig.getBaseUrl() != null ?
                  customConfig.getBaseUrl() : getDefaultBaseUrl(customConfig.getProvider());
        String model = customConfig.getModelName();

        // 创建 OpenAiApi（使用默认构造函数）
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);

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
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }
}
