package com.chatbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 知识库客户端服务
 *
 * 调用 MCP 知识库服务器，获取业务术语定义和上下文增强
 */
@Slf4j
@Service
public class MCPKnowledgeService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mcp.knowledge.server.url:http://localhost:8004}")
    private String mcpServerUrl;

    @Value("${mcp.knowledge.enabled:true}")
    private boolean mcpEnabled;

    public MCPKnowledgeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 为用户查询增强上下文
     *
     * @param userQuery 用户的原始查询
     * @return 增强后的上下文信息
     */
    public Map<String, Object> enrichQueryContext(String userQuery) {
        if (!mcpEnabled) {
            log.debug("MCP 知识库服务未启用");
            return createEmptyContext(userQuery);
        }

        try {
            String url = mcpServerUrl + "/tools/enrich_query_context";

            Map<String, String> request = new HashMap<>();
            request.put("query", userQuery);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            log.debug("调用 MCP 服务器增强上下文: {}", userQuery);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("success"))) {
                    Map<String, Object> result = (Map<String, Object>) body.get("result");
                    log.info("MCP 上下文增强成功，识别到 {} 个术语",
                            result.get("identified_terms") != null ?
                                    ((java.util.List<?>) result.get("identified_terms")).size() : 0);
                    return result;
                }
            }

            log.warn("MCP 服务器返回失败响应");
            return createEmptyContext(userQuery);

        } catch (Exception e) {
            log.error("调用 MCP 服务器失败: {}", e.getMessage());
            return createEmptyContext(userQuery);
        }
    }

    /**
     * 获取增强后的 Prompt
     *
     * @param userQuery 用户查询
     * @return 增强后的 Prompt 文本
     */
    public String getEnrichedPrompt(String userQuery) {
        Map<String, Object> context = enrichQueryContext(userQuery);
        String enrichedPrompt = (String) context.get("enriched_prompt");

        if (enrichedPrompt != null && !enrichedPrompt.isEmpty()) {
            return enrichedPrompt;
        }

        // 如果没有增强信息，返回原始查询
        return "用户问题：" + userQuery;
    }

    /**
     * 搜索业务术语
     *
     * @param keyword 搜索关键词
     * @param category 术语类别（可选）
     * @return 搜索结果
     */
    public Map<String, Object> searchTerms(String keyword, String category) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/search_terms";

            Map<String, String> request = new HashMap<>();
            request.put("keyword", keyword);
            if (category != null) {
                request.put("category", category);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "message", "搜索失败");

        } catch (Exception e) {
            log.error("搜索术语失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 获取术语的列映射
     *
     * @param term 术语名称
     * @return 列映射信息
     */
    public Map<String, Object> getColumnMapping(String term) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/get_column_mapping";

            Map<String, String> request = Map.of("term", term);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "message", "查询失败");

        } catch (Exception e) {
            log.error("获取列映射失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 解析时间表达式
     *
     * @param expression 时间表达式
     * @param referenceDate 参考日期（可选）
     * @return 解析结果
     */
    public Map<String, Object> parseTimeExpression(String expression, String referenceDate) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/parse_time_expression";

            Map<String, String> request = new HashMap<>();
            request.put("expression", expression);
            if (referenceDate != null) {
                request.put("reference_date", referenceDate);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "message", "解析失败");

        } catch (Exception e) {
            log.error("解析时间表达式失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 检查 MCP 服务器健康状态
     *
     * @return 是否健康
     */
    public boolean isHealthy() {
        if (!mcpEnabled) {
            return false;
        }

        try {
            String url = mcpServerUrl + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("MCP 服务器健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证实体列表
     *
     * @param entities 实体列表，每个实体包含 text 和 type
     * @return 验证结果
     */
    public Map<String, Object> validateEntities(java.util.List<Map<String, String>> entities) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/validate_entities";

            Map<String, Object> request = Map.of("entities", entities);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            log.debug("调用 MCP 服务器验证实体: {} 个", entities.size());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                log.info("实体验证完成，验证了 {} 个实体", entities.size());
                return body;
            }

            return Map.of("success", false, "message", "验证失败");

        } catch (Exception e) {
            log.error("验证实体失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 实体消歧
     *
     * @param entity 实体文本
     * @param context 上下文
     * @param possibleTypes 可能的类型列表
     * @return 消歧结果
     */
    public Map<String, Object> disambiguateEntity(String entity, String context, java.util.List<String> possibleTypes) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/disambiguate_entity";

            Map<String, Object> request = new HashMap<>();
            request.put("entity", entity);
            request.put("context", context);
            request.put("possible_types", possibleTypes);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(request, headers);

            log.debug("调用 MCP 服务器消歧实体: {}", entity);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, httpEntity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "message", "消歧失败");

        } catch (Exception e) {
            log.error("实体消歧失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 展开产品系列
     *
     * @param seriesName 产品系列名称
     * @return 展开结果，包含所有型号
     */
    public Map<String, Object> expandProductSeries(String seriesName) {
        if (!mcpEnabled) {
            return Map.of("success", false, "message", "MCP 服务未启用");
        }

        try {
            String url = mcpServerUrl + "/tools/expand_product_series";

            Map<String, String> request = Map.of("series_name", seriesName);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            log.debug("调用 MCP 服务器展开产品系列: {}", seriesName);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("success"))) {
                    log.info("产品系列 {} 展开成功", seriesName);
                    return body;
                }
            }

            return Map.of("success", false, "message", "展开失败");

        } catch (Exception e) {
            log.error("展开产品系列失败: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 创建空的上下文（当 MCP 服务不可用时）
     */
    private Map<String, Object> createEmptyContext(String userQuery) {
        Map<String, Object> context = new HashMap<>();
        context.put("original_query", userQuery);
        context.put("identified_terms", java.util.Collections.emptyList());
        context.put("time_expressions", java.util.Collections.emptyList());
        context.put("column_mappings", java.util.Collections.emptyList());
        context.put("enriched_prompt", "用户问题：" + userQuery);
        return context;
    }
}
