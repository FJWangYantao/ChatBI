package com.chatbi.service;

import com.chatbi.dto.CorrectionResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 逻辑验证服务
 * 实现语法检查、逻辑检查、性能检查、业务规则检查
 */
@Service
public class SQLValidationService {

    private static final Logger logger = LoggerFactory.getLogger(SQLValidationService.class);

    private List<SyntaxRule> syntaxRules = new ArrayList<>();
    private List<TimeRule> timeRules = new ArrayList<>();
    private List<BusinessRule> businessRules = new ArrayList<>();
    private List<PerformanceRule> performanceRules = new ArrayList<>();

    @PostConstruct
    public void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource("sql-correction-rules.json");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    parseRulesFromJson(json);
                }
            }
        } catch (Exception e) {
            logger.warn("加载 SQL 纠错规则失败，使用默认规则: {}", e.getMessage());
        }
    }

    private void parseRulesFromJson(String json) {
        // 简单解析 JSON 规则（避免引入额外 JSON 库，Spring 已有 Jackson）
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            if (root.has("syntaxRules")) {
                for (var node : root.get("syntaxRules")) {
                    syntaxRules.add(new SyntaxRule(
                            node.path("pattern").asText(),
                            node.path("issue").asText(),
                            node.path("suggestion").asText()
                    ));
                }
            }
            if (root.has("timeRules")) {
                for (var node : root.get("timeRules")) {
                    timeRules.add(new TimeRule(
                            node.path("pattern").asText(),
                            node.path("issue").asText(),
                            node.path("correction").asText()
                    ));
                }
            }
            if (root.has("businessRules")) {
                for (var node : root.get("businessRules")) {
                    businessRules.add(new BusinessRule(
                            node.path("pattern").asText(),
                            node.path("validation").asText(),
                            node.path("correction").asText()
                    ));
                }
            }
            if (root.has("performanceRules")) {
                for (var node : root.get("performanceRules")) {
                    performanceRules.add(new PerformanceRule(
                            node.path("pattern").asText(),
                            node.path("issue").asText(),
                            node.path("suggestion").asText()
                    ));
                }
            }
        } catch (Exception e) {
            logger.warn("解析 SQL 纠错规则 JSON 失败: {}", e.getMessage());
        }
    }

    /**
     * 验证 SQL 语法
     */
    public CorrectionResult.ValidationError validateSyntax(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new CorrectionResult.ValidationError("SYNTAX", "SQL 为空", "");
        }
        String cleanSql = cleanSqlForParse(sql);
        try {
            Statement stmt = CCJSqlParserUtil.parse(cleanSql);
            if (stmt == null) {
                return new CorrectionResult.ValidationError("SYNTAX", "无法解析 SQL", "");
            }
            return null;
        } catch (Exception e) {
            return new CorrectionResult.ValidationError("SYNTAX", "SQL 语法错误: " + e.getMessage(), "");
        }
    }

    /**
     * 执行完整验证
     */
    public List<CorrectionResult.ValidationError> validate(String sql) {
        List<CorrectionResult.ValidationError> errors = new ArrayList<>();

        // 1. 语法检查
        CorrectionResult.ValidationError syntaxError = validateSyntax(sql);
        if (syntaxError != null) {
            errors.add(syntaxError);
            logger.info("语法检查完成，发现语法错误: {}", syntaxError.getMessage());
            return errors; // 语法错误时不再继续
        }

        String normalizedSql = sql.replaceAll("\\s+", " ");

        // 2. 逻辑规则检查
        for (SyntaxRule rule : syntaxRules) {
            try {
                Pattern p = Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(normalizedSql);
                if (m.find()) {
                    logger.info("发现语法规则匹配: {}", rule.pattern);
                    errors.add(new CorrectionResult.ValidationError("LOGIC", rule.issue, rule.suggestion));
                }
            } catch (Exception e) {
                logger.debug("规则匹配异常: {}", rule.pattern);
            }
        }

        // 3. 时间规则检查
        for (TimeRule rule : timeRules) {
            try {
                Pattern p = Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(normalizedSql);
                if (m.find()) {
                    errors.add(new CorrectionResult.ValidationError("TIME", rule.issue, rule.correction));
                }
            } catch (Exception e) {
                logger.debug("时间规则匹配异常: {}", rule.pattern);
            }
        }

        // 4. 业务规则检查
        for (BusinessRule rule : businessRules) {
            try {
                Pattern p = Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(normalizedSql);
                if (m.find()) {
                    errors.add(new CorrectionResult.ValidationError("BUSINESS", rule.validation, rule.correction));
                }
            } catch (Exception e) {
                logger.debug("业务规则匹配异常: {}", rule.pattern);
            }
        }

        // 5. 性能规则检查（仅警告，不阻止执行）
        for (PerformanceRule rule : performanceRules) {
            try {
                Pattern p = Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(normalizedSql);
                if (m.find()) {
                    errors.add(new CorrectionResult.ValidationError("PERFORMANCE", rule.issue, rule.suggestion));
                }
            } catch (Exception e) {
                logger.debug("性能规则匹配异常: {}", rule.pattern);
            }
        }

        return errors;
    }

    /**
     * 检查是否为 SELECT 语句（安全过滤）
     */
    public boolean isSelectStatement(String sql) {
        String clean = cleanSqlForParse(sql).trim().toUpperCase();
        return clean.startsWith("SELECT");
    }

    private String cleanSqlForParse(String sql) {
        if (sql == null) return "";
        return sql.trim()
                .replaceAll("^```sql\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    private record SyntaxRule(String pattern, String issue, String suggestion) {}
    private record TimeRule(String pattern, String issue, String correction) {}
    private record BusinessRule(String pattern, String validation, String correction) {}
    private record PerformanceRule(String pattern, String issue, String suggestion) {}
}
