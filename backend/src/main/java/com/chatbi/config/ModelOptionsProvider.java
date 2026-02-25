package com.chatbi.config;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型配置提供者：为各 Agent 提供可配置的 OpenAiChatOptions。
 * 支持全局默认 + 按 Agent 单独指定，未配置的 Agent 使用 default。
 */
@Component
public class ModelOptionsProvider {

    private static final String DEFAULT_MODEL = "deepseek/deepseek-v3.2";
    private static final double DEFAULT_TEMPERATURE = 0.1;

    private final Environment environment;

    @Value("${chatbi.model.default:" + DEFAULT_MODEL + "}")
    private String defaultModel;

    @Value("${chatbi.model.temperature:" + DEFAULT_TEMPERATURE + "}")
    private double defaultTemperature;

    private final Map<String, String> agentModels = new HashMap<>();

    public ModelOptionsProvider(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        String[] agents = {"clarification", "planning", "code", "forecast", "report",
                "suggestion", "sql-correction", "text2sql", "ner", "chat", "diagnostic"};
        for (String agent : agents) {
            String key = "chatbi.model.agents." + agent;
            String value = environment.getProperty(key, "").trim();
            if (value.isEmpty() && "sql-correction".equals(agent)) {
                value = environment.getProperty("sql-correction.model-name", "").trim();
            }
            if (value.isEmpty() && "ner".equals(agent)) {
                value = environment.getProperty("spring.ner-service.model-name", "").trim();
            }
            agentModels.put(agent, value);
        }
    }

    /**
     * 获取指定 Agent 的 OpenAiChatOptions。
     * 若该 Agent 有单独配置则使用，否则使用全局 default。
     */
    public OpenAiChatOptions getOptions(String agentName) {
        String model = agentModels.getOrDefault(agentName, "").trim();
        if (model.isEmpty()) {
            model = defaultModel;
        }
        return OpenAiChatOptions.builder()
                .withModel(model)
                .withTemperature(defaultTemperature)
                .build();
    }
}
