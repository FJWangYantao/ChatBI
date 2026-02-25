package com.chatbi.service.enhancement;

import com.chatbi.dto.Enhancement;
import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;
import com.chatbi.dto.NERResponse;
import com.chatbi.service.NERService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据查询增强器
 * 针对 DATA_QUERY 意图进行增强，包括：
 * 1. Schema 增强：补充数据库结构信息
 * 2. 时间范围增强：自动推断时间范围
 */
@Slf4j
@Component
public class DataQueryEnhancer implements PromptEnhancer {

    private final NERService nerService;

    @Value("${prompt-enhancement.strategies.data-query.schema-enhancement:true}")
    private boolean schemaEnhancementEnabled;

    @Value("${prompt-enhancement.strategies.data-query.time-range-enhancement:true}")
    private boolean timeRangeEnhancementEnabled;

    @Value("${ner-enhancer.enabled:true}")
    private boolean nerEnhancementEnabled;

    public DataQueryEnhancer(NERService nerService) {
        this.nerService = nerService;
    }

    // 时间关键词映射
    private static final List<TimeKeyword> TIME_KEYWORDS = Arrays.asList(
            new TimeKeyword("今天", "today", 0, 0),
            new TimeKeyword("昨天", "yesterday", 1, 1),
            new TimeKeyword("本周", "this_week", 0, 6),
            new TimeKeyword("上周", "last_week", 7, 13),
            new TimeKeyword("本月", "this_month", 0, 30),
            new TimeKeyword("上月", "last_month", 30, 60),
            new TimeKeyword("最近7天", "last_7_days", 0, 7),
            new TimeKeyword("最近30天", "last_30_days", 0, 30),
            new TimeKeyword("最近90天", "last_90_days", 0, 90)
    );

    @Override
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        List<Enhancement> enhancements = new ArrayList<>();
        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        // 1. Schema 增强
        if (schemaEnhancementEnabled && context.getSchemaInfo() != null) {
            String schemaEnhancement = buildSchemaEnhancement(context);
            if (schemaEnhancement != null && !schemaEnhancement.isEmpty()) {
                enhancements.add(new Enhancement("SCHEMA", "", schemaEnhancement, "补充数据库结构信息"));
                enhancedPrompt.append("\n\n").append(schemaEnhancement);
            }
        }

        // 2. 时间范围增强
        if (timeRangeEnhancementEnabled) {
            String timeEnhancement = detectAndEnhanceTimeRange(originalPrompt);
            if (timeEnhancement != null && !timeEnhancement.isEmpty()) {
                enhancements.add(new Enhancement("TIME", "", timeEnhancement, "明确时间范围"));
                enhancedPrompt.append("\n\n").append(timeEnhancement);
            }
        }

        // 3. 聚合方式增强（基于子类型）
        String aggregationEnhancement = detectAndEnhanceAggregation(originalPrompt, context.getSubtype());
        if (aggregationEnhancement != null && !aggregationEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("AGGREGATION", "", aggregationEnhancement, "明确聚合方式"));
            enhancedPrompt.append("\n\n").append(aggregationEnhancement);
        }

        // 4. NER 实体识别增强（缓存结果供后续使用）
        if (nerEnhancementEnabled) {
            try {
                NERResponse nerResponse = nerService.extractEntities(originalPrompt);
                if (nerResponse != null && nerResponse.getEntities() != null && !nerResponse.getEntities().isEmpty()) {
                    // 保存NER结果供后续使用，但不修改prompt文本（避免超长）
                    Enhancement nerEnhancement = new Enhancement(
                        "NER_ENTITIES",
                        "实体识别",
                        String.format("识别到 %d 个实体", nerResponse.getEntities().size()),
                        "缓存NER结果供Text2SQL使用"
                    );
                    nerEnhancement.setData(nerResponse); // 保存完整的NER结果
                    enhancements.add(nerEnhancement);
                    log.info("DataQueryEnhancer: 缓存了 {} 个NER实体供后续使用", nerResponse.getEntities().size());
                } else {
                    log.debug("DataQueryEnhancer: 未识别到NER实体");
                }
            } catch (Exception e) {
                log.warn("DataQueryEnhancer: NER增强失败: {}", e.getMessage());
            }
        }

        EnhancedPrompt result = new EnhancedPrompt();
        result.setEnhancedPrompt(enhancedPrompt.toString());
        result.setEnhancements(enhancements);
        result.setExplanation("数据查询增强完成，共应用 " + enhancements.size() + " 项增强");

        log.info("DataQueryEnhancer: 原始Prompt='{}', 增强项数={}", originalPrompt, enhancements.size());

        return result;
    }

    @Override
    public boolean supports(IntentType intentType) {
        return intentType == IntentType.DATA_QUERY;
    }

    @Override
    public int getPriority() {
        return 10; // 高优先级
    }

    /**
     * 构建 Schema 增强内容
     */
    private String buildSchemaEnhancement(EnhancementContext context) {
        String schemaInfo = context.getSchemaInfo();
        if (schemaInfo == null || schemaInfo.isEmpty()) {
            return "";
        }

        // 根据原始消息提取相关的表和字段（简化版，实际可以使用 AI 提取）
        String relevantSchema = extractRelevantSchema(schemaInfo, context.getOriginalMessage());

        return String.format("""
            【数据库结构参考】
            %s

            请使用上述表结构生成 SQL 查询，注意：
            - 表名和列名要严格按照上述结构使用
            - 利用表之间的外键关系进行正确的关联
            - WHERE 中 AND/OR 混用时必须用括号明确优先级
            - 跨年时间范围使用 (Year=X AND Month>=M) OR (Year=Y AND Month<=N)，不要写 Month BETWEEN 3 AND 1
            - 统计查询需使用聚合函数（COUNT/SUM/AVG/MAX/MIN）
            """, relevantSchema);
    }

    /**
     * 从完整 Schema 中提取相关部分
     * 简化实现：返回完整 Schema，实际可以根据关键词过滤
     */
    private String extractRelevantSchema(String fullSchema, String message) {
        // 简化版：直接返回完整 Schema
        // 实际实现可以使用 AI 或关键词匹配来提取相关表
        return fullSchema;
    }

    /**
     * 检测并增强时间范围
     */
    private String detectAndEnhanceTimeRange(String message) {
        // 检查是否已经包含明确的时间范围
        if (hasExplicitTimeRange(message)) {
            return null;
        }

        // 检测时间关键词
        for (TimeKeyword keyword : TIME_KEYWORDS) {
            if (message.contains(keyword.getKeyword())) {
                return buildTimeRangeEnhancement(keyword);
            }
        }

        // 如果没有时间关键词，添加默认时间范围提示
        return "【时间范围】如果没有明确指定时间范围，默认所有数据。";
    }

    /**
     * 检查是否已有明确的时间范围
     */
    private boolean hasExplicitTimeRange(String message) {
        // 检查日期格式：YYYY-MM-DD
        Pattern datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
        Matcher matcher = datePattern.matcher(message);
        if (matcher.find()) {
            return true;
        }

        // 检查其他时间表达
        String[] explicitTimePatterns = {
            "between", "从.*到", "期间", "范围内", "大于", "小于", "之前", "之后"
        };

        for (String pattern : explicitTimePatterns) {
            if (message.matches(".*" + pattern + ".*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 构建时间范围增强内容
     */
    private String buildTimeRangeEnhancement(TimeKeyword keyword) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(keyword.getDaysOffset());
        LocalDate endDate = today.minusDays(keyword.getDaysEndOffset());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        return String.format("""
            【时间范围】
            根据关键词"%s"，时间范围设定为：%s 至 %s
            请在 SQL 查询中使用此时间范围进行过滤。
            """,
            keyword.getKeyword(),
            startDate.format(formatter),
            endDate.format(formatter)
        );
    }

    /**
     * 检测并增强聚合方式
     */
    private String detectAndEnhanceAggregation(String message, String subtype) {
        // 如果子类型明确，使用子类型对应的聚合方式
        if (subtype != null && !subtype.isEmpty()) {
            return buildAggregationEnhancement(subtype);
        }

        // 根据消息内容推断聚合方式
        if (message.contains("总计") || message.contains("总和") || message.contains("合计")) {
            return buildAggregationEnhancement("AGGREGATION_SUM");
        }
        if (message.contains("数量") || message.contains("个数") || message.contains("多少")) {
            return buildAggregationEnhancement("AGGREGATION_COUNT");
        }
        if (message.contains("平均") || message.contains("均值")) {
            return buildAggregationEnhancement("AGGREGATION_AVG");
        }
        if (message.contains("最大") || message.contains("最小")) {
            return buildAggregationEnhancement("AGGREGATION_MAX_MIN");
        }

        return null;
    }

    /**
     * 构建聚合方式增强内容
     */
    private String buildAggregationEnhancement(String subtype) {
        String aggregationType = switch (subtype) {
            case "AGGREGATION_SUM" -> "SUM() 求和";
            case "AGGREGATION_COUNT" -> "COUNT() 计数";
            case "AGGREGATION_AVG" -> "AVG() 平均值";
            case "AGGREGATION_MAX_MIN" -> "MAX()/MIN() 最大最小值";
            default -> "适当的聚合函数";
        };

        return String.format("""
            【聚合方式】
            请使用 %s 进行数据聚合。
            """, aggregationType);
    }

    /**
     * 时间关键词内部类
     */
    private static class TimeKeyword {
        private final String keyword;
        private final String english;
        private final int daysOffset;
        private final int daysEndOffset;

        public TimeKeyword(String keyword, String english, int daysOffset, int daysEndOffset) {
            this.keyword = keyword;
            this.english = english;
            this.daysOffset = daysOffset;
            this.daysEndOffset = daysEndOffset;
        }

        public String getKeyword() {
            return keyword;
        }

        public int getDaysOffset() {
            return daysOffset;
        }

        public int getDaysEndOffset() {
            return daysEndOffset;
        }
    }
}
