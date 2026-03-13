package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.CorrectionResult;
import com.chatbi.dto.Entity;
import com.chatbi.factory.DynamicChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 纠错 Agent
 * 结合规则校验和 AI 智能纠错，在 SQL 生成后自动进行修正
 */
@Slf4j
@Service
public class SQLCorrectionAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;
    private final SQLValidationService sqlValidationService;

    @Value("${sql-correction.enabled:true}")
    private boolean enabled;

    @Value("${sql-correction.enable-rule-based-correction:true}")
    private boolean ruleBasedCorrectionEnabled;

    @Value("${sql-correction.enable-ai-correction:true}")
    private boolean aiCorrectionEnabled;

    @Value("${sql-correction.correction-confidence-threshold:0.8}")
    private double confidenceThreshold;

    public SQLCorrectionAgent(DynamicChatClientFactory chatClientFactory,
                              ModelOptionsProvider modelOptions,
                              SQLValidationService sqlValidationService) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
        this.sqlValidationService = sqlValidationService;
    }

    private static final String SQL_SEPARATOR = "###SQL_SEPARATOR###";

    /**
     * 对生成的 SQL 进行纠错
     *
     * @param originalSQL  原始 SQL
     * @param userQuery    用户问题
     * @param entities     识别的实体列表（可为 null）
     * @return 纠错结果
     */
    public CorrectionResult correctSQL(String originalSQL, String userQuery, List<Entity> entities) {
        if (!enabled || originalSQL == null || originalSQL.trim().isEmpty()) {
            return CorrectionResult.builder()
                    .originalSQL(originalSQL)
                    .correctedSQL(originalSQL)
                    .corrected(false)
                    .corrections(Collections.emptyList())
                    .validationErrors(Collections.emptyList())
                    .build();
        }

        // 支持多条 SQL，分别纠错后合并
        if (originalSQL.contains(SQL_SEPARATOR)) {
            String[] parts = originalSQL.split(Pattern.quote(SQL_SEPARATOR));
            List<String> correctedParts = new ArrayList<>();
            boolean anyCorrected = false;
            for (String part : parts) {
                if (part.trim().isEmpty()) continue;
                CorrectionResult r = correctSQL(part.trim(), userQuery, entities);
                correctedParts.add(r.getCorrectedSQL());
                if (r.isCorrected()) anyCorrected = true;
            }
            return CorrectionResult.builder()
                    .originalSQL(originalSQL)
                    .correctedSQL(String.join(SQL_SEPARATOR, correctedParts))
                    .corrected(anyCorrected)
                    .corrections(Collections.emptyList())
                    .validationErrors(Collections.emptyList())
                    .build();
        }

        String cleanSql = cleanSql(originalSQL);
        List<CorrectionResult.ValidationError> validationErrors = new ArrayList<>();
        List<CorrectionResult.CorrectionItem> corrections = new ArrayList<>();

        // 1. 安全过滤：仅允许 SELECT
        if (!sqlValidationService.isSelectStatement(cleanSql)) {
            log.warn("SQL 非 SELECT 语句，跳过纠错: {}", cleanSql.substring(0, Math.min(50, cleanSql.length())) + "...");
            return CorrectionResult.builder()
                    .originalSQL(originalSQL)
                    .correctedSQL(cleanSql)
                    .corrected(false)
                    .validationErrors(List.of(new CorrectionResult.ValidationError(
                            "SECURITY", "仅支持 SELECT 查询", "")))
                    .build();
        }

        // 2. 规则校验
        if (ruleBasedCorrectionEnabled) {
            validationErrors.addAll(sqlValidationService.validate(cleanSql));
        }

        String finalSQL = cleanSql;
        boolean corrected = false;

        // 3. 规则驱动的智能纠错（条件优先级、时间范围等）
        if (ruleBasedCorrectionEnabled) {
            var ruleCorrected = applyRuleBasedCorrections(cleanSql);
            if (ruleCorrected != null && !ruleCorrected.equals(cleanSql)) {
                finalSQL = ruleCorrected;
                corrected = true;
                corrections.add(new CorrectionResult.CorrectionItem(
                        "RULE_CORRECTION", "", "规则驱动修正", "自动修正常见逻辑错误"));
            }
        }

        // 4. 若存在语法错误，尝试 AI 纠错

        boolean hasSyntaxError = validationErrors.stream()
                .anyMatch(e -> "SYNTAX".equals(e.getType()));

        if (hasSyntaxError && aiCorrectionEnabled) {
            CorrectionResult aiResult = attemptAICorrection(finalSQL, userQuery, entities);
            if (aiResult != null && aiResult.isCorrected()) {
                finalSQL = aiResult.getCorrectedSQL();
                corrected = true;
                corrections.addAll(aiResult.getCorrections());
                validationErrors.removeIf(e -> "SYNTAX".equals(e.getType()));
            }
        }

        return CorrectionResult.builder()
                .originalSQL(originalSQL)
                .correctedSQL(finalSQL)
                .corrected(corrected)
                .corrections(corrections)
                .validationErrors(validationErrors)
                .build();
    }

    /**
     * 尝试使用 AI 进行纠错
     */
    private CorrectionResult attemptAICorrection(String sql, String userQuery, List<Entity> entities) {
        try {
            String entitiesStr = entities != null && !entities.isEmpty()
                    ? entities.stream()
                    .map(e -> String.format("'%s' (%s%s)", e.getText(), e.getType(),
                            e.getNormalizedValue() != null ? " -> " + e.getNormalizedValue() : ""))
                    .collect(Collectors.joining(", "))
                    : "无";

            String prompt = String.format("""
                    你是一个SQL纠错专家。请分析以下SQL语句并进行修正。

                    原始用户问题: %s
                    生成的SQL: %s
                    识别的实体: %s

                    请检查以下方面并提供修正建议：
                    1. 语法错误：括号匹配、关键字拼写、标点符号
                    2. 逻辑错误：WHERE条件优先级、JOIN关系、时间范围处理
                    3. 业务逻辑：字段映射正确性、实体引用准确性
                    4. 实体识别错误，如果实体识别和用户问题有出入，以用户问题为准

                    如果发现错误，请只返回修正后的完整SQL语句，不要有任何解释。
                    如果SQL正确无需修正，请原样返回SQL语句。
                    """, userQuery, sql, entitiesStr);

            ChatClient chatClient = chatClientFactory.createChatClient("sql-correction");
            // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && !response.trim().isEmpty()) {
                String corrected = cleanSql(response);
                if (!corrected.equalsIgnoreCase(sql)) {
                    return CorrectionResult.builder()
                            .originalSQL(sql)
                            .correctedSQL(corrected)
                            .corrected(true)
                            .corrections(List.of(new CorrectionResult.CorrectionItem(
                                    "AI_CORRECTION", "", "AI 自动修正", "语法或逻辑错误")))
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("AI 纠错失败: {}", e.getMessage());
        }
        return null;
    }

    private String cleanSql(String sql) {
        if (sql == null) return "";
        return sql.trim()
                .replaceAll("^```sql\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    /**
     * 规则驱动的智能纠错：条件优先级、时间范围、聚合补全等
     */
    private String applyRuleBasedCorrections(String sql) {
        String result = sql;
        // 跨年 Month BETWEEN 3 AND 1 修正为合理逻辑（需结合 Year）
        // 简单模式：Month BETWEEN 1 AND 12 且 start>end 时，可能是跨年，此处仅做提示，不自动改写（易误伤）
        // AND/OR 优先级：WHERE a AND b OR c 应加括号，但自动加括号较复杂，交由 AI 处理
        return result;
    }
}
