package com.chatbi.service.enhancement;

import com.chatbi.dto.*;
import com.chatbi.service.NERService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * NER 实体识别增强器
 * 在用户问题中添加实体识别结果，提高 SQL 生成的准确性
 */
@Slf4j
@Component
public class NEREnhancer implements PromptEnhancer {

    private final NERService nerService;

    @Value("${ner-enhancer.enabled:true}")
    private boolean enabled;

    @Value("${ner-enhancer.priority:100}")
    private int priority;

    public NEREnhancer(NERService nerService) {
        this.nerService = nerService;
    }

    @Override
    public String getName() {
        return "NER实体识别增强器";
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean supports(IntentType intentType) {
        return enabled && intentType == IntentType.DATA_QUERY;
    }

    @Override
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        if (!supports(context.getIntentType())) {
            return new EnhancedPrompt(originalPrompt, List.of(), "不支持的意图类型");
        }

        List<Enhancement> enhancements = new ArrayList<>();
        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        try {
            // 1. 提取实体
            NERResponse nerResponse = nerService.extractEntities(originalPrompt);
            
            if (nerResponse != null && nerResponse.getEntities() != null && !nerResponse.getEntities().isEmpty()) {
                // 2. 按类型分组实体
                Map<String, List<Entity>> entitiesByType = nerResponse.getEntities().stream()
                        .collect(Collectors.groupingBy(Entity::getType));

                // 3. 构建实体信息
                StringBuilder entityInfo = new StringBuilder("\n\n【实体识别结果】\n");
                
                // 表名实体
                if (entitiesByType.containsKey("TABLE")) {
                    entityInfo.append("表名: ");
                    entitiesByType.get("TABLE").forEach(e -> {
                        entityInfo.append("'").append(e.getText()).append("'");
                        if (e.getNormalizedValue() != null) {
                            entityInfo.append("(→").append(e.getNormalizedValue()).append(")");
                        }
                        entityInfo.append(", ");
                    });
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 列名实体
                if (entitiesByType.containsKey("COLUMN")) {
                    entityInfo.append("列名: ");
                    entitiesByType.get("COLUMN").forEach(e -> {
                        entityInfo.append("'").append(e.getText()).append("'");
                        if (e.getNormalizedValue() != null) {
                            entityInfo.append("(→").append(e.getNormalizedValue()).append(")");
                        }
                        entityInfo.append(", ");
                    });
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 值/条件实体
                if (entitiesByType.containsKey("VALUE")) {
                    entityInfo.append("值/条件: ");
                    entitiesByType.get("VALUE").forEach(e -> 
                        entityInfo.append("'").append(e.getText()).append("', "));
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 时间范围实体
                if (entitiesByType.containsKey("TIME_RANGE")) {
                    entityInfo.append("时间范围: ");
                    entitiesByType.get("TIME_RANGE").forEach(e -> 
                        entityInfo.append("'").append(e.getText()).append("', "));
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 聚合函数实体
                if (entitiesByType.containsKey("AGGREGATION")) {
                    entityInfo.append("聚合函数: ");
                    entitiesByType.get("AGGREGATION").forEach(e -> 
                        entityInfo.append("'").append(e.getText()).append("', "));
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 组织实体
                if (entitiesByType.containsKey("ORG")) {
                    entityInfo.append("组织/公司: ");
                    entitiesByType.get("ORG").forEach(e -> 
                        entityInfo.append("'").append(e.getText()).append("', "));
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 地理位置实体
                if (entitiesByType.containsKey("LOC")) {
                    entityInfo.append("地理位置: ");
                    entitiesByType.get("LOC").forEach(e -> 
                        entityInfo.append("'").append(e.getText()).append("', "));
                    entityInfo.setLength(entityInfo.length() - 2);
                    entityInfo.append("\n");
                }

                // 添加到增强的prompt中
                enhancedPrompt.append(entityInfo.toString());

                // 记录增强信息，同时保存NER结果对象供后续使用
                Enhancement nerEnhancement = new Enhancement(
                    "NER_ENTITIES",
                    "实体识别",
                    String.format("识别到 %d 个实体，包含 %d 种类型", 
                        nerResponse.getEntities().size(), 
                        entitiesByType.size()),
                    null
                );
                nerEnhancement.setData(nerResponse); // 保存完整的NER结果
                enhancements.add(nerEnhancement);

                log.info("NER增强完成: 识别到 {} 个实体", nerResponse.getEntities().size());
            } else {
                enhancements.add(new Enhancement(
                    "NER_ENTITIES",
                    "实体识别",
                    "未识别到实体",
                    null
                ));
                log.debug("NER增强: 未识别到实体");
            }

        } catch (Exception e) {
            log.warn("NER增强失败: {}", e.getMessage());
            enhancements.add(new Enhancement(
                "NER_ENTITIES",
                "实体识别",
                "增强失败: " + e.getMessage(),
                null
            ));
        }

        return new EnhancedPrompt(enhancedPrompt.toString(), enhancements, "NER实体识别增强完成");
    }
}