package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.Entity;
import com.chatbi.dto.EntityRelation;
import com.chatbi.dto.NERResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NER (命名实体识别) 服务
 *
 * 三级降级策略:
 *   1. 优先调用本地 BERT+CRF NER 模型服务 (Python FastAPI, 延迟 20-50ms)
 *   2. 降级到 LLM NER (OpenRouter API, 延迟 1-3s)
 *   3. 最终降级到正则匹配
 *
 * 实体抽取后, 通过 MCP 服务验证实体
 */
@Service
public class NERService {

    private static final Logger logger = LoggerFactory.getLogger(NERService.class);

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final RestTemplate restTemplate;
    private final MCPKnowledgeService mcpKnowledgeService;

    // ============ 配置项 ============

    @Value("${ner-service.enabled:true}")
    private boolean enabled;

    /** NER 模型服务 base URL */
    @Value("${ner-model-service.base-url:http://localhost:8002}")
    private String nerModelBaseUrl;

    /** NER 模型服务端点 */
    @Value("${ner-model-service.endpoint:/ner/predict}")
    private String nerModelEndpoint;

    /** NER 模型服务超时 (毫秒) */
    @Value("${ner-model-service.timeout:1000}")
    private int nerModelTimeout;

    /** 是否启用 NER 模型服务 */
    @Value("${ner-model-service.enabled:true}")
    private boolean nerModelEnabled;

    /** 模型不可用时是否降级到 LLM */
    @Value("${ner-model-service.fallback-to-llm:true}")
    private boolean fallbackToLlm;

    /** 是否启用实体链接 */
    @Value("${ner-model-service.entity-linking:true}")
    private boolean entityLinkingEnabled;

    /** 是否启用实体消歧 (使用 MCP) */
    @Value("${entity-disambiguation.enabled:true}")
    private boolean entityDisambiguationEnabled;

    private final ModelPerformanceMonitor performanceMonitor;

    // ============ LLM NER Prompt ============

    private static final String NER_PROMPT_TEMPLATE = """
            You are a Named Entity Recognition (NER) assistant specialized for analyzing database query related natural language questions.
            
            Please extract entities of the following types from the user's question:
            
            Entity types:
            - TABLE: Database table name, e.g., orders table, users, products, orders
            - COLUMN: Field name, e.g., sales amount, name, product ID
            - VALUE: Numeric value or string, e.g., 1000, iPhone, active
            - TIME_RANGE: Time range, e.g., last year, this month, 2024 Q1, recent 7 days
            - AGGREGATION: Aggregate function, e.g., sum, average, count, max value
            - OPERATOR: Comparison operator, e.g., greater than, less than, equal, contains, between
            - JOIN_CONDITION: Join condition, e.g., product table id, order user_id
            
            Output format requirements:
            1. Use strict JSON format output
            2. Include original_text, entities list, relations list
            3. Entity format: {"text": "entity text", "type": "entity type", "start_pos": start position, "end_pos": end position}
            4. Relation format: {"source_text": "source entity", "target_text": "target entity", "relation_type": "relation type"}
            5. Relation types: table_column, column_value, column_operator, etc.
            6. If no entities found, entities and relations are empty arrays
            7. Only output JSON, no other text explanations
            
            User question: %s
            """;

    // ============ 构造函数 ============

    @Autowired
    public NERService(ChatClient.Builder chatClientBuilder,
                      ModelOptionsProvider modelOptions,
                      MCPKnowledgeService mcpKnowledgeService,
                      ModelPerformanceMonitor performanceMonitor) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.restTemplate = new RestTemplate();
        this.mcpKnowledgeService = mcpKnowledgeService;
        this.performanceMonitor = performanceMonitor;
        logger.info("NER Service initialized (model service + LLM fallback + MCP validation)");
    }

    // ============ 核心接口 ============

    /**
     * 提取实体 (主入口)
     * 
     * 执行顺序:
     *   1. 本地 NER 模型服务
     *   2. LLM NER (降级)
     *   3. 正则匹配 (最终降级)
     *   4. 实体链接 (映射到 Schema 字段)
     */
    public NERResponse extractEntities(String text) {
        if (!enabled) {
            logger.debug("NER service is disabled");
            return createEmptyResponse(text);
        }

        long startTime = System.currentTimeMillis();
        String traceId = MDC.get("traceId");
        logger.debug("Extracting entities from: {}", text);

        NERResponse response = null;
        String source = null;

        // === 策略1: 本地 NER 模型服务 ===
        if (nerModelEnabled) {
            response = callNERModelService(text);
            if (response != null) {
                source = "ner_model";
            }
        }

        // === 策略2: LLM NER (降级) ===
        if (response == null && fallbackToLlm) {
            response = callLLMNER(text);
            if (response != null) {
                source = "llm_ner";
            }
        }

        // === 策略3: 正则匹配 (最终降级) ===
        if (response == null) {
            response = createFallbackResponse(text);
            source = "regex";
        }

        // === 实体验证 (MCP) ===
        if (entityLinkingEnabled && response.getEntities() != null && !response.getEntities().isEmpty()) {
            try {
                if (entityDisambiguationEnabled && mcpKnowledgeService != null) {
                    // 使用 MCP 验证实体
                    List<Map<String, String>> entityList = new ArrayList<>();
                    for (Entity entity : response.getEntities()) {
                        Map<String, String> entityMap = new HashMap<>();
                        entityMap.put("text", entity.getText());
                        entityMap.put("type", entity.getType());
                        entityList.add(entityMap);
                    }

                    Map<String, Object> validationResult = mcpKnowledgeService.validateEntities(entityList);
                    if (Boolean.TRUE.equals(validationResult.get("success"))) {
                        logger.debug("MCP 实体验证成功");
                    }

                    // 对低置信度实体做消歧
                    for (Entity ent : response.getEntities()) {
                        if (ent.getConfidence() < 0.7) {
                            try {
                                List<String> possibleTypes = List.of("PRODUCT", "CUSTOMER", "BRAND", "SERIES");
                                Map<String, Object> disambResult = mcpKnowledgeService.disambiguateEntity(
                                    ent.getText(), text, possibleTypes);

                                if (Boolean.TRUE.equals(disambResult.get("success"))) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> result = (Map<String, Object>) disambResult.get("result");
                                    if (result != null) {
                                        String resolvedType = (String) result.get("resolved_type");
                                        if (resolvedType != null) {
                                            ent.setType(resolvedType);
                                            logger.debug("实体消歧: {} -> 类型={}", ent.getText(), resolvedType);
                                        }
                                        Number newConfidence = (Number) result.get("confidence");
                                        if (newConfidence != null) {
                                            ent.setConfidence(newConfidence.doubleValue());
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                logger.warn("实体消歧失败: {} - {}", ent.getText(), ex.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Entity validation/disambiguation failed: {}", e.getMessage());
            }
        }

        // 结构化日志: 实体识别结果
        long duration = System.currentTimeMillis() - startTime;
        String entitiesStr = formatEntitiesForLog(response.getEntities());
        logger.info("[STRUCT] event=entity_recognition source={} entity_count={} entities=[{}] duration={}ms traceId={}",
                source, response.getEntities() != null ? response.getEntities().size() : 0,
                entitiesStr, duration, traceId != null ? traceId : "-");

        if (performanceMonitor != null) {
            performanceMonitor.recordNERCall(response != null && response.getEntities() != null);
        }

        return response;
    }

    /**
     * 将实体列表格式化为紧凑的日志字符串: TYPE:text,TYPE:text
     */
    private String formatEntitiesForLog(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",");
            Entity e = entities.get(i);
            sb.append(e.getType()).append(":").append(e.getText());
            if (e.getNormalizedValue() != null && !e.getNormalizedValue().isEmpty()) {
                sb.append("->").append(e.getNormalizedValue());
            }
        }
        return sb.toString();
    }

    // ============ 策略1: 本地 NER 模型服务 ============

    /**
     * 调用 Python NER 模型服务 (BERT+CRF)
     * 
     * @return NERResponse 或 null (失败时)
     */
    private NERResponse callNERModelService(String text) {
        try {
            String url = nerModelBaseUrl + nerModelEndpoint;
            logger.debug("Calling NER model service: {}", url);

            // 构建请求
            Map<String, String> request = new HashMap<>();
            request.put("text", text);

            // 调用服务
            ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, request, Map.class);

            if (responseEntity.getStatusCode() == HttpStatus.OK && responseEntity.getBody() != null) {
                Map<String, Object> body = responseEntity.getBody();
                return parseModelServiceResponse(body, text);
            }

            logger.warn("NER model service returned non-OK status: {}", responseEntity.getStatusCode());
            return null;

        } catch (ResourceAccessException e) {
            logger.debug("NER model service unavailable: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warn("NER model service call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 NER 模型服务的响应
     */
    @SuppressWarnings("unchecked")
    private NERResponse parseModelServiceResponse(Map<String, Object> body, String originalText) {
        NERResponse response = new NERResponse();
        response.setOriginalText(originalText);

        List<Map<String, Object>> entitiesList = (List<Map<String, Object>>) body.get("entities");
        if (entitiesList != null) {
            for (Map<String, Object> entityMap : entitiesList) {
                Entity entity = new Entity();
                entity.setText((String) entityMap.get("text"));
                entity.setType((String) entityMap.get("type"));

                // 处理数值类型转换
                Object startPos = entityMap.get("start_pos");
                Object endPos = entityMap.get("end_pos");
                entity.setStartPos(startPos instanceof Number ? ((Number) startPos).intValue() : 0);
                entity.setEndPos(endPos instanceof Number ? ((Number) endPos).intValue() : 0);

                Object confidence = entityMap.get("confidence");
                entity.setConfidence(confidence instanceof Number ? ((Number) confidence).doubleValue() : 1.0);

                response.getEntities().add(entity);
            }
        }

        logger.debug("Parsed NER model response: {} entities", response.getEntities().size());
        return response;
    }

    // ============ 策略2: LLM NER ============

    /**
     * 调用 LLM 进行 NER (降级方案)
     * 
     * @return NERResponse 或 null (失败时)
     */
    private NERResponse callLLMNER(String text) {
        try {
            String prompt = String.format(NER_PROMPT_TEMPLATE, text);

            // 使用 createChatClient 中设置的 defaultOptions（前端配置）
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            logger.debug("LLM NER raw response: {}", content);
            return parseNERResponse(content, text);

        } catch (Exception e) {
            logger.error("LLM NER extraction failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private NERResponse parseNERResponse(String jsonResponse, String originalText) {
        try {
            jsonResponse = jsonResponse.trim();
            if (jsonResponse.startsWith("```json")) {
                jsonResponse = jsonResponse.substring(7);
            }
            if (jsonResponse.startsWith("```")) {
                jsonResponse = jsonResponse.substring(3);
            }
            if (jsonResponse.endsWith("```")) {
                jsonResponse = jsonResponse.substring(0, jsonResponse.length() - 3);
            }
            jsonResponse = jsonResponse.trim();

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            NERResponse response = mapper.readValue(jsonResponse, NERResponse.class);
            response.setOriginalText(originalText);

            correctEntityPositions(response, originalText);

            logger.debug("Parsed LLM NER result: {} entities, {} relations",
                    response.getEntities().size(), response.getRelations().size());

            return response;

        } catch (Exception e) {
            logger.warn("LLM NER JSON parsing failed: {}", e.getMessage());
            return null;
        }
    }

    private void correctEntityPositions(NERResponse response, String originalText) {
        for (Entity entity : response.getEntities()) {
            String entityText = entity.getText();
            int pos = originalText.indexOf(entityText);
            if (pos >= 0) {
                entity.setStartPos(pos);
                entity.setEndPos(pos + entityText.length());
            }
        }
    }

    // ============ 策略3: 正则回退 ============

    private NERResponse createFallbackResponse(String text) {
        logger.debug("Using regex based fallback NER");

        NERResponse response = new NERResponse();
        response.setOriginalText(text);

        extractTimeRangeEntities(text, response.getEntities());
        extractValueEntities(text, response.getEntities());
        extractAggregationEntities(text, response.getEntities());

        return response;
    }

    private void extractTimeRangeEntities(String text, List<Entity> entities) {
        Pattern[] timePatterns = {
                Pattern.compile("(?i)last year|this year"),
                Pattern.compile("(?i)last month|this month"),
                Pattern.compile("(?i)last quarter|this quarter|Q[1-4]"),
                Pattern.compile("(?i)recent \\d+ days"),
                Pattern.compile("(?i)\\d{4} year"),
                Pattern.compile("(?i)\\d{4} year \\d{1,2} month"),
                // 中文时间模式
                Pattern.compile("上个月|本月|上个季度|今年|去年|本周|上周|本季度"),
                Pattern.compile("最近\\d+天"),
                Pattern.compile("\\d{4}年(\\d{1,2}月)?"),
                Pattern.compile("第[一二三四]季度"),
                Pattern.compile("上半年|下半年"),
        };

        for (Pattern pattern : timePatterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String matched = matcher.group();
                entities.add(new Entity(matched, "TIME_RANGE",
                        matcher.start(), matcher.end()));
            }
        }
    }

    private void extractValueEntities(String text, List<Entity> entities) {
        Pattern valuePattern = Pattern.compile("\\d+(\\.\\d+)?(%|分)?");
        Matcher matcher = valuePattern.matcher(text);
        while (matcher.find()) {
            String matched = matcher.group();
            entities.add(new Entity(matched, "VALUE",
                    matcher.start(), matcher.end()));
        }
    }

    private void extractAggregationEntities(String text, List<Entity> entities) {
        Pattern[] aggPatterns = {
                Pattern.compile("(?i)\\b(sum|total)\\b"),
                Pattern.compile("(?i)\\b(average|avg)\\b"),
                Pattern.compile("(?i)\\b(count)\\b"),
                Pattern.compile("(?i)\\b(max)\\b"),
                Pattern.compile("(?i)\\b(min)\\b"),
                // 中文聚合模式
                Pattern.compile("总计|总和|总额|合计|汇总"),
                Pattern.compile("平均值?|均值"),
                Pattern.compile("最大值?|最高"),
                Pattern.compile("最小值?|最低"),
                Pattern.compile("总数|个数"),
        };

        for (Pattern pattern : aggPatterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String matched = matcher.group();
                entities.add(new Entity(matched, "AGGREGATION",
                        matcher.start(), matcher.end()));
            }
        }
    }

    // ============ 工具方法 ============

    private NERResponse createEmptyResponse(String text) {
        NERResponse response = new NERResponse();
        response.setOriginalText(text);
        return response;
    }

    /**
     * 检查 NER 服务健康状态
     */
    public boolean isHealthy() {
        if (!enabled) return false;

        // 优先检查模型服务
        if (nerModelEnabled) {
            try {
                String url = nerModelBaseUrl + "/health";
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("NER model service health check failed: {}", e.getMessage());
            }
        }

        // LLM 兜底
        return chatClient != null;
    }

    /**
     * 刷新缓存（MCP 服务是无状态的，此方法保留用于兼容性）
     */
    public void refreshEntityLinkingCache() {
        // MCP 服务是无状态的，不需要刷新
        logger.info("Cache refresh requested - MCP service is stateless, no action needed");
    }
}
