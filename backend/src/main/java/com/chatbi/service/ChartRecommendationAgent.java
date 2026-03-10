package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表推荐 Agent：根据数据结构和用户问题，推荐最合适的图表类型
 */
@Slf4j
@Service
public class ChartRecommendationAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;
    private final ObjectMapper objectMapper;

    public ChartRecommendationAgent(DynamicChatClientFactory chatClientFactory,
                                    ModelOptionsProvider modelOptions) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
        this.objectMapper = new ObjectMapper();
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
            // 先解析 JSON 数据，检查记录数
            int recordCount = countRecords(jsonData);
            log.info("[ChartRecommendation] JSON 数据记录数: {}", recordCount);

            String prompt = buildRecommendationPrompt(userQuestion, jsonData);
            String llmResponse = callLLM(prompt);
            Map<String, Object> recommendation = parseRecommendation(llmResponse);

            // 强制规则：如果数据少于 50 条且 LLM 推荐了 table，覆盖为 bar
            if (recordCount > 0 && recordCount < 50 && "table".equals(recommendation.get("chartType"))) {
                log.warn("[ChartRecommendation] 数据只有 {} 条，但 LLM 推荐了 table，强制改为 bar", recordCount);
                recommendation.put("chartType", "bar");

                // 如果没有 xField/yField，尝试从 JSON 中推断
                if (recommendation.get("xField") == null || recommendation.get("yField") == null) {
                    Map<String, String> fields = inferFieldsFromJson(jsonData);
                    if (fields.get("xField") != null) recommendation.put("xField", fields.get("xField"));
                    if (fields.get("yField") != null) recommendation.put("yField", fields.get("yField"));
                }
            }

            return recommendation;
        } catch (Exception e) {
            log.error("图表推荐失败", e);
            return Map.of(
                "chartType", "table",
                "title", "数据结果"
            );
        }
    }

    /**
     * 统计 JSON 数组中的记录数
     */
    private int countRecords(String jsonData) {
        try {
            var array = objectMapper.readTree(jsonData);
            if (array.isArray()) {
                return array.size();
            }
        } catch (Exception e) {
            log.debug("解析 JSON 记录数失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 从 JSON 数据中推断字段名
     */
    private Map<String, String> inferFieldsFromJson(String jsonData) {
        try {
            var array = objectMapper.readTree(jsonData);
            if (array.isArray() && array.size() > 0) {
                var firstRecord = array.get(0);
                var fields = firstRecord.fieldNames();
                String xField = null;
                String yField = null;

                // 找第一个字符串字段作为 xField，第一个数字字段作为 yField
                while (fields.hasNext()) {
                    String field = fields.next();
                    var value = firstRecord.get(field);
                    if (xField == null && value.isTextual()) {
                        xField = field;
                    }
                    if (yField == null && value.isNumber()) {
                        yField = field;
                    }
                    if (xField != null && yField != null) break;
                }

                return Map.of(
                    "xField", xField != null ? xField : "",
                    "yField", yField != null ? yField : ""
                );
            }
        } catch (Exception e) {
            log.debug("推断字段名失败: {}", e.getMessage());
        }
        return Map.of("xField", "", "yField", "");
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
            - table: 表格（仅当数据超过 100 条或无法可视化时使用）

            **要求**:
            1. 分析数据结构（字段名、数据类型、记录数）
            2. 结合用户问题意图，推荐最合适的图表类型
            3. 指定 x 轴字段（xField）和 y 轴字段（yField）- 必须是数据中实际存在的字段名
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

            **重要规则**:
            - **强制要求**：优先推荐可视化图表（bar/line/pie/area/scatter），让数据更直观
            - **禁止**：不要轻易推荐 table，除非数据超过 100 条记录或完全无法可视化
            - 如果数据有分类字段和数值字段，**必须**推荐 bar 或 pie
            - 如果数据包含百分比或占比信息，**优先**推荐 pie
            - 如果数据只有 2-20 条记录，**绝对不要**推荐 table
            - 如果有时间字段，优先推荐 line 或 area
            - 如果有多个数值字段，可以推荐 scatter
            - 对于对比类问题，优先推荐 bar
            - 对于趋势类问题，优先推荐 line
            - xField 和 yField 必须是 JSON 数据中实际存在的字段名（区分大小写）
            """,
                userQuestion,
                jsonData.length() > 1000 ? jsonData.substring(0, 1000) + "..." : jsonData
        );
    }

    private String callLLM(String prompt) throws Exception {
        log.info("[ChartRecommendation] 发送给 LLM 的完整 prompt:\n{}", prompt);
        ChatClient chatClient = chatClientFactory.createChatClient("chart-recommendation");
        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        log.info("[ChartRecommendation] LLM 原始响应:\n{}", response);
        return response;
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
