package com.chatbi.service.example;

import com.chatbi.service.MCPKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * MCP 集成示例
 *
 * 展示如何在 ChatService 中使用 MCP 知识库服务
 */
@Slf4j
@Service
public class MCPIntegrationExample {

    @Autowired
    private MCPKnowledgeService mcpKnowledgeService;

    /**
     * 示例 1：基本的上下文增强
     */
    public String example1_BasicEnrichment(String userQuery) {
        log.info("=== 示例 1：基本的上下文增强 ===");

        // 1. 获取增强后的 Prompt
        String enrichedPrompt = mcpKnowledgeService.getEnrichedPrompt(userQuery);

        log.info("原始查询: {}", userQuery);
        log.info("增强后的 Prompt:\n{}", enrichedPrompt);

        // 2. 将增强的 Prompt 传递给 AI
        String aiPrompt = enrichedPrompt + "\n\n请根据以上信息生成 SQL 查询。";

        return aiPrompt;
    }

    /**
     * 示例 2：详细的上下文分析
     */
    public void example2_DetailedContext(String userQuery) {
        log.info("=== 示例 2：详细的上下文分析 ===");

        // 获取完整的上下文信息
        Map<String, Object> context = mcpKnowledgeService.enrichQueryContext(userQuery);

        // 分析识别到的术语
        java.util.List<?> identifiedTerms = (java.util.List<?>) context.get("identified_terms");
        log.info("识别到 {} 个术语", identifiedTerms != null ? identifiedTerms.size() : 0);

        // 分析时间表达式
        java.util.List<?> timeExpressions = (java.util.List<?>) context.get("time_expressions");
        log.info("识别到 {} 个时间表达式", timeExpressions != null ? timeExpressions.size() : 0);

        // 分析列映射
        java.util.List<?> columnMappings = (java.util.List<?>) context.get("column_mappings");
        log.info("找到 {} 个列映射", columnMappings != null ? columnMappings.size() : 0);
    }

    /**
     * 示例 3：搜索特定术语
     */
    public void example3_SearchTerms(String keyword) {
        log.info("=== 示例 3：搜索特定术语 ===");

        Map<String, Object> result = mcpKnowledgeService.searchTerms(keyword, null);

        if (Boolean.TRUE.equals(result.get("success"))) {
            java.util.List<?> results = (java.util.List<?>) result.get("results");
            log.info("搜索 '{}' 找到 {} 个结果", keyword, results != null ? results.size() : 0);
        }
    }

    /**
     * 示例 4：获取列映射
     */
    public void example4_GetColumnMapping(String term) {
        log.info("=== 示例 4：获取列映射 ===");

        Map<String, Object> result = mcpKnowledgeService.getColumnMapping(term);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> mapping = (Map<String, Object>) result.get("result");
            log.info("术语 '{}' 的列映射: {}", term, mapping);
        }
    }

    /**
     * 示例 5：解析时间表达式
     */
    public void example5_ParseTimeExpression(String expression) {
        log.info("=== 示例 5：解析时间表达式 ===");

        Map<String, Object> result = mcpKnowledgeService.parseTimeExpression(expression, null);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> parsed = (Map<String, Object>) result.get("result");
            log.info("时间表达式 '{}' 解析结果: {}", expression, parsed);
        }
    }

    /**
     * 完整的集成示例：在 ChatService 中使用
     */
    public String integratedExample(String userQuery) {
        log.info("=== 完整集成示例 ===");

        // 步骤 1：检查 MCP 服务是否可用
        if (!mcpKnowledgeService.isHealthy()) {
            log.warn("MCP 服务不可用，使用原始查询");
            return userQuery;
        }

        // 步骤 2：获取增强的上下文
        Map<String, Object> context = mcpKnowledgeService.enrichQueryContext(userQuery);
        String enrichedPrompt = (String) context.get("enriched_prompt");

        // 步骤 3：构建完整的 AI Prompt
        StringBuilder aiPrompt = new StringBuilder();
        aiPrompt.append(enrichedPrompt);
        aiPrompt.append("\n\n");
        aiPrompt.append("请根据以上业务术语定义和数据库列映射信息，生成准确的 SQL 查询。");
        aiPrompt.append("\n");
        aiPrompt.append("要求：");
        aiPrompt.append("\n1. 使用正确的表名和列名");
        aiPrompt.append("\n2. 如果有时间范围，使用提供的 SQL 条件");
        aiPrompt.append("\n3. 确保 SQL 语法正确");

        log.info("构建的 AI Prompt:\n{}", aiPrompt.toString());

        // 步骤 4：将 Prompt 传递给 Spring AI（这里只是示例）
        // String sqlResult = springAI.generate(aiPrompt.toString());

        return aiPrompt.toString();
    }
}
