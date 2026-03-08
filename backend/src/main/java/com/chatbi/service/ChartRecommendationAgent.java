package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表推荐 Agent：根据数据结构和用户问题，推荐最合适的图表类型
 */
@Slf4j
@Service
public class ChartRecommendationAgent {

    private final ModelOptionsProvider modelOptions;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String apiBaseUrl;

    public ChartRecommendationAgent(ModelOptionsProvider modelOptions) {
        this.modelOptions = modelOptions;
        this.objectMapper = new ObjectMapper();
        this.httpClient = buildHttpClient();
    }

    private static HttpClient buildHttpClient() {
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))))
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
        }
        return HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * 推荐图表类型
     *
     * @param userQuestion 用户问题
     * @param jsonData     Python 输出的 JSON 数据
     * @return 图表推荐结果 { chartType, xField, yField, title }
     */
    public Map<String, Object> recommend(String userQuestion, String jsonData) {
        try {
            String prompt = buildRecommendationPrompt(userQuestion, jsonData);
            String llmResponse = callLLM(prompt);
            return parseRecommendation(llmResponse);
        } catch (Exception e) {
            log.error("图表推荐失败", e);
            return Map.of(
                "chartType", "table",
                "title", "数据结果"
            );
        }
    }

    private String buildRecommendationPrompt(String userQuestion, String jsonData) {
        return String.format("""
            你是一个数据可视化专家。根据用户问题和数据结构，推荐最合适的图表类型。

            **用户问题**: %s

            **数据（JSON 格式）**:
            %s

            **可选图表类型**:
            - bar: 柱状图（适合分类对比）
            - line: 折线图（适合趋势变化）
            - pie: 饼图（适合占比分析）
            - area: 面积图（适合累积趋势）
            - scatter: 散点图（适合相关性分析）
            - table: 表格（适合详细数据展示）

            **要求**:
            1. 分析数据结构（字段名、数据类型、记录数）
            2. 结合用户问题意图，推荐最合适的图表类型
            3. 指定 x 轴字段（xField）和 y 轴字段（yField）
            4. 生成简洁的图表标题
            5. 只输出 JSON 格式，用 ```json ``` 包裹

            **输出格式**:
            ```json
            {
              "chartType": "bar",
              "xField": "产品",
              "yField": "销售额",
              "title": "各产品销售额对比"
            }
            ```

            **规则**:
            - 如果数据超过 20 条记录，优先推荐 table
            - 如果只有 1-2 个字段，优先推荐 bar 或 pie
            - 如果有时间字段，优先推荐 line 或 area
            - 如果有多个数值字段，可以推荐 scatter
            """,
                userQuestion,
                jsonData.length() > 1000 ? jsonData.substring(0, 1000) + "..." : jsonData
        );
    }

    private String callLLM(String prompt) throws Exception {
        // 获取模型配置
        String model = modelOptions.getOptions("chart-recommendation").getModel();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
        });
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 500);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(java.time.Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM 调用失败: " + response.statusCode() + " " + response.body());
        }

        Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
        Map<String, Object> firstChoice = ((java.util.List<Map<String, Object>>) responseMap.get("choices")).get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        return (String) message.get("content");
    }

    private Map<String, Object> parseRecommendation(String llmResponse) {
        try {
            // 提取 JSON 代码块
            String json = extractJsonBlock(llmResponse);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("解析图表推荐失败: {}", llmResponse, e);
            return Map.of(
                "chartType", "table",
                "title", "数据结果"
            );
        }
    }

    private String extractJsonBlock(String content) {
        // 尝试提取 ```json ... ```
        int start = content.indexOf("```json");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 尝试提取 ``` ... ```
        start = content.indexOf("```");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 尝试提取 { ... }
        start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1).trim();
        }
        // 没有找到，直接返回
        return content.trim();
    }
}
