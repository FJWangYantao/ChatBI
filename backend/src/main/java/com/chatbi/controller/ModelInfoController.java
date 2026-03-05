package com.chatbi.controller;

import com.chatbi.dto.ModelInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ModelInfoController {

    @Value("${spring.ai.openai.base-url}")
    private String defaultBaseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String defaultModel;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;

    @GetMapping("/model-info")
    public ModelInfoResponse getModelInfo(
            @RequestHeader(value = "X-LLM-Provider", required = false) String provider,
            @RequestHeader(value = "X-LLM-Model", required = false) String model,
            @RequestHeader(value = "X-LLM-Base-Url", required = false) String baseUrl) {

        // 优先使用请求头中的配置（前端配置），否则使用后端默认配置
        String actualProvider = provider != null ? provider :
                (defaultBaseUrl.contains("openrouter") ? "openrouter" : "deepseek");
        String actualModel = model != null ? model : defaultModel;
        String actualBaseUrl = baseUrl != null ? baseUrl : defaultBaseUrl;

        return new ModelInfoResponse(actualProvider, actualModel, actualBaseUrl, temperature);
    }
}
