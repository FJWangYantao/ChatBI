package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.CorrectionResult;
import com.chatbi.factory.DynamicChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class Text2SQLAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;
    private final ReadSchemaStructureService schemaService;
    private final SQLCorrectionAgent sqlCorrectionAgent;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;
    private final MCPKnowledgeService mcpKnowledgeService;

    // 最近一次生成的最终 SQL（纠错后），供外部获取用于 tag_end
    private volatile String lastGeneratedSQL;

    public Text2SQLAgent(DynamicChatClientFactory chatClientFactory,
                         ModelOptionsProvider modelOptions,
                         ReadSchemaStructureService schemaService,
                         SQLCorrectionAgent sqlCorrectionAgent,
                         DynamicJdbcTemplateProvider jdbcTemplateProvider,
                         MCPKnowledgeService mcpKnowledgeService) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
        this.schemaService = schemaService;
        this.sqlCorrectionAgent = sqlCorrectionAgent;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.mcpKnowledgeService = mcpKnowledgeService;
    }

    /**
     * 获取数据（向后兼容，内部调用流式版本并传入空回调）
     */
    public List<Map<String, Object>> fetchData(String dataQuery) {
        return fetchDataWithStreaming(dataQuery, delta -> {});
    }

    /**
     * 流式获取数据：SQL 生成过程中每个 token 实时回调
     * @param dataQuery 自然语言查询描述
     * @param onSqlDelta 每收到一个 SQL token 时的回调
     * @return 查询结果列表；同时通过 lastGeneratedSQL 可获取最终 SQL
     */
    public List<Map<String, Object>> fetchDataWithStreaming(String dataQuery, Consumer<String> onSqlDelta) {
        log.info("[Text2SQLAgent] Fetching data (streaming) for query: {}", dataQuery);

        // 1. 获取 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        // 2. 构建 prompt
        String systemPrompt = buildSqlPrompt(dataQuery, schemaInfo);

        // 3. 动态创建 ChatClient 并流式生成 SQL
        ChatClient chatClient = chatClientFactory.createChatClient("text2sql");
        StringBuilder sqlBuilder = new StringBuilder();
        try {
            // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
            Flux<String> sqlFlux = chatClient.prompt()
                    .user(systemPrompt)
                    .stream()
                    .content();

            sqlFlux.doOnNext(token -> {
                sqlBuilder.append(token);
                onSqlDelta.accept(token);
            }).blockLast();
        } catch (Exception e) {
            log.error("[Text2SQLAgent] Streaming SQL generation failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }

        String sql = sqlBuilder.toString();
        if (sql.trim().isEmpty()) {
            log.warn("[Text2SQLAgent] Failed to generate SQL");
            return Collections.emptyList();
        }

        // 4. 纠错
        CorrectionResult correctionResult = sqlCorrectionAgent.correctSQL(sql, dataQuery, null);
        String finalSQL = correctionResult.getCorrectedSQL();
        this.lastGeneratedSQL = finalSQL;
        log.info("[Text2SQLAgent] Final SQL: {}", finalSQL);

        // 5. 执行
        try {
            String cleanSql = finalSQL.trim()
                    .replaceAll("^```sql\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();

            List<Map<String, Object>> data = jdbcTemplateProvider.getJdbcTemplate().queryForList(cleanSql);
            log.info("[Text2SQLAgent] Fetched {} rows", data.size());
            return data;

        } catch (Exception e) {
            log.error("[Text2SQLAgent] Execution failed: SQL={}, Error={}", finalSQL, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取最近一次生成的最终 SQL（纠错后）
     */
    public String getLastGeneratedSQL() {
        return lastGeneratedSQL;
    }

    private String buildSqlPrompt(String dataQuery, String schemaInfo) {
        return buildSqlPromptWithMCP(dataQuery, schemaInfo);
    }

    /**
     * 构建 SQL 生成 prompt（公开方法，供 ChatStreamService 调用）
     */
    public String buildSqlPromptWithMCP(String dataQuery, String schemaInfo) {
        // 尝试使用 MCP 增强查询上下文
        Map<String, Object> mcpContext = tryEnrichWithMCP(dataQuery);

        // 构建增强后的 prompt
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你是一个数据库专家。为了回答用户的分析问题，请生成一条 SQL 语句查询必要的数据。\n\n");

        // 用户需求
        promptBuilder.append("用户需求：").append(dataQuery).append("\n\n");

        // MCP 增强信息（如果可用）
        if (mcpContext != null && Boolean.TRUE.equals(mcpContext.get("mcp_available"))) {
            promptBuilder.append("【业务上下文增强】\n");

            // 识别到的术语
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> terms = (List<Map<String, Object>>) mcpContext.get("identified_terms");
            if (terms != null && !terms.isEmpty()) {
                promptBuilder.append("- 识别到的业务术语：\n");
                for (Map<String, Object> term : terms) {
                    promptBuilder.append("  * ").append(term.get("term"))
                        .append("：").append(term.get("definition")).append("\n");
                }
            }

            // 时间表达式
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeExprs = (List<Map<String, Object>>) mcpContext.get("time_expressions");
            if (timeExprs != null && !timeExprs.isEmpty()) {
                promptBuilder.append("- 时间表达式解析：\n");
                for (Map<String, Object> expr : timeExprs) {
                    promptBuilder.append("  * ").append(expr.get("original"))
                        .append(" → ").append(expr.get("parsed")).append("\n");
                }
            }

            // 列映射
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mappings = (List<Map<String, Object>>) mcpContext.get("column_mappings");
            if (mappings != null && !mappings.isEmpty()) {
                promptBuilder.append("- 数据库列映射（请优先使用精确值匹配）：\n");
                for (Map<String, Object> mapping : mappings) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> term = (Map<String, Object>) mapping.get("term");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> columnMappings = (List<Map<String, Object>>) mapping.get("mappings");

                    if (term != null && columnMappings != null && !columnMappings.isEmpty()) {
                        String termName = (String) term.get("term");
                        for (Map<String, Object> colMapping : columnMappings) {
                            String tableName = (String) colMapping.get("table_name");
                            String columnName = (String) colMapping.get("column_name");
                            String description = (String) colMapping.get("description");
                            @SuppressWarnings("unchecked")
                            List<String> sampleValues = (List<String>) colMapping.get("sample_values");

                            promptBuilder.append("  * ").append(termName)
                                .append(" → ").append(tableName).append(".").append(columnName);

                            if (description != null && !description.isEmpty()) {
                                promptBuilder.append(" (").append(description).append(")");
                            }

                            if (sampleValues != null && !sampleValues.isEmpty()) {
                                promptBuilder.append("\n    精确值示例: ");
                                promptBuilder.append(String.join(", ", sampleValues.stream()
                                    .map(v -> "'" + v + "'").toList()));
                                promptBuilder.append("\n    ⚠️ 请使用精确匹配（= 或 IN），避免使用 LIKE 模糊匹配");
                            }
                            promptBuilder.append("\n");
                        }
                    }
                }
            }

            promptBuilder.append("\n");
        }

        // 数据库结构
        promptBuilder.append("数据库结构：\n").append(schemaInfo).append("\n\n");

        // 要求
        promptBuilder.append("""
            要求：
            1. 优先查询明细行数据（不使用聚合函数），让 Python 做后续统计分析。
            2. 只选择必要的字段：
               - 过滤条件相关的字段（如 PRODUCT_CATEGORY, Year, Month）
               - 分析目标字段（如 Volume）
               - 分组维度字段（如需要按产品/时间分组）
               ⚠️ 不要使用 SELECT * 或选择大量无关字段
               ⚠️ 时间字段二选一：如果用户提到"财年/FY"，只选 FiscalYear/FiscalMonth/FiscalQuarter；否则只选 Year/Month/Quarter
               ⚠️ 禁止同时选择 Year/Month 和 FiscalYear/FiscalMonth，这是冗余的
            3. 过滤条件优先级（严格遵守）：
               - 如果 MCP 提供了精确值示例，【必须使用精确值示例中的值】，【禁止使用术语名称本身】
               - 示例1：MCP显示 "IPPro5 → PRODUCT_SERIES 精确值: 'IdeaPad Pro 5'"
                 ✅ 正确：WHERE PRODUCT_SERIES = 'IdeaPad Pro 5'
                 ❌ 错误：WHERE PRODUCT_SERIES = 'IPPro5'（IPPro5只是术语名，不是数据库中的实际值）
               - 示例2：MCP显示 "S3 → PRODUCT_SERIES 精确值: 'IdeaPad Slim 3'"
                 ✅ 正确：WHERE PRODUCT_SERIES = 'IdeaPad Slim 3'
                 ❌ 错误：WHERE PRODUCT_SERIES = 'S3'
               - 只有在 MCP 未提供精确值时，才可以使用 LIKE 模糊匹配
               - 【禁止】同时使用精确匹配和LIKE：WHERE (PRODUCT_SERIES = 'IdeaPad Slim 3' OR PRODUCT_NAME LIKE '%S3%')
            4. 时间范围解析规则：
               - "2024.2-25.2" 表示 FY24 M2 到 FY25 M2（跨财年），需要用 OR 连接：
                 WHERE (FiscalYear = 2024 AND FiscalMonth >= 2) OR (FiscalYear = 2025 AND FiscalMonth <= 2)
               - FiscalMonth 只有 1-12，不可能有 13 以上的值
               - "上个月/上个季度/上个财年" 等相对时间，需要计算具体的年月值，不要使用数据库函数
            5. 产品型号中的数字识别：
               - "S3 15" 中的 "15" 指屏幕尺寸，需要添加 PRODUCT_SCREENSIZE = '15' 或 PRODUCT_SCREENSIZE = '15.6'
               - "Yoga Pro 7" 中的 "7" 是产品系列名称的一部分，应该在 PRODUCT_NAME 或 PRODUCT_SERIES 中匹配
            6. 如果必须使用聚合函数（SUM/COUNT/AVG等），则 SELECT 中所有非聚合列都必须出现在 GROUP BY 中。
            7. 不要在同一 SELECT 中混用聚合列和非聚合明细列（如 customer_id、gender），除非它们都在 GROUP BY 里。
            8. 如果是时间序列分析，请确保包含日期字段。
            9. 只返回 SQL，不要解释，不要 markdown 代码块。
            """);

        return promptBuilder.toString();
    }

    /**
     * 尝试使用 MCP 增强查询上下文
     * @param dataQuery 用户查询
     * @return 增强后的上下文，如果 MCP 不可用则返回 null
     */
    private Map<String, Object> tryEnrichWithMCP(String dataQuery) {
        try {
            // 检查 MCP 服务健康状态
            if (!mcpKnowledgeService.isHealthy()) {
                log.warn("[Text2SQLAgent] MCP 服务不可用，跳过上下文增强");
                return null;
            }

            // 调用 MCP 增强上下文
            long startTime = System.currentTimeMillis();
            Map<String, Object> context = mcpKnowledgeService.enrichQueryContext(dataQuery);
            long duration = System.currentTimeMillis() - startTime;

            if (context != null && !context.isEmpty()) {
                context.put("mcp_available", true);
                log.info("[Text2SQLAgent] MCP 上下文增强成功，耗时 {}ms", duration);
                return context;
            } else {
                log.warn("[Text2SQLAgent] MCP 返回空上下文");
                return null;
            }

        } catch (Exception e) {
            log.error("[Text2SQLAgent] MCP 上下文增强失败：{}", e.getMessage());
            // 降级：返回 null，使用原始 prompt
            return null;
        }
    }
}
