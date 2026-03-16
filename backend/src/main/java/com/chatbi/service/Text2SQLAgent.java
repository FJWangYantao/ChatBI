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
            // 使用 createChatClient 中设置的 defaultOptions（前端配置）
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
            // 规则清理 SQL（去除 markdown 代码块标记）
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

            log.info("进入MCP增强");
            promptBuilder.append("【业务上下文增强】\n");

            // 识别到的术语
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> terms = (List<Map<String, Object>>) mcpContext.get("identified_terms");
            // 组织术语信息，产生单条规范日志
            StringBuilder termsLog = new StringBuilder();
            termsLog.append("[MCP术语识别]\n");

            if (terms != null && !terms.isEmpty()) {
                termsLog.append("  总数: ").append(terms.size()).append("\n");
                for (int i = 0; i < terms.size(); i++) {
                    Map<String, Object> term = terms.get(i);
                    termsLog.append("  ").append(i + 1).append(". ")
                        .append(term.get("term"))
                        .append(" (").append(term.get("category")).append(")")
                        .append("\n     定义: ").append(term.get("definition")).append("\n");
                }
            } else {
                termsLog.append("  总数: 0\n");
            }

            log.info(termsLog.toString());
            if (terms != null && !terms.isEmpty()) {
                promptBuilder.append("- 识别到的业务术语：\n");
                for (Map<String, Object> term : terms) {
                    promptBuilder.append("  * ").append(term.get("term"))
                        .append("：").append(term.get("definition")).append("\n");
                }

                for (Map<String, Object> term : terms) {
                    String category = (String) term.get("category");
                    if ("product_series".equals(category) || "product_family".equals(category)) {
                        String termName = (String) term.get("term");
                        try {
                            Map<String, Object> expandResult = mcpKnowledgeService.expandProductSeries(termName);
                            if (Boolean.TRUE.equals(expandResult.get("success"))) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> result = (Map<String, Object>) expandResult.get("result");
                                @SuppressWarnings("unchecked")
                                List<String> models = (List<String>) result.get("models");
                                if (models != null && !models.isEmpty()) {
                                    String modelValues = String.join(", ", models.stream()
                                        .map(m -> "'" + m + "'").toList());
                                    promptBuilder.append("  * ").append(termName)
                                        .append(" 系列包含以下型号：").append(modelValues).append("\n")
                                        .append("    ⚠️ 查询该系列时请使用 IN (").append(modelValues).append(")\n");
                                    log.info("产品系列 {} 展开为 {} 个型号", termName, models.size());
                                }
                            }
                        } catch (Exception e) {
                            log.warn("展开产品系列 {} 失败: {}", termName, e.getMessage());
                        }
                    }
                }
            }

            // 时间表达式
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timeExprs = (List<Map<String, Object>>) mcpContext.get("time_expressions");

            // 日志
            StringBuilder timeExprsLog = new StringBuilder();
            
            timeExprsLog.append("[MCP时间表达式解析]\n");
            if (timeExprs != null && !timeExprs.isEmpty()) {
                timeExprsLog.append("  总数: ").append(timeExprs.size()).append("\n");
                for (int i = 0; i < timeExprs.size(); i++) {
                    Map<String, Object> expr = timeExprs.get(i);
                    timeExprsLog.append("  ").append(i + 1).append(". ")
                        .append(expr.get("expression"))      
                        .append(" → ").append(expr.get("description"))
                        .append(" (").append(expr.get("type")).append(")")
                        .append("\n");
                }
                log.info(timeExprsLog.toString());

                //加入时间表达式到prompt
                promptBuilder.append("- 时间表达式解析：\n");
                int exprIndex = 1;
                for (Map<String, Object> expr : timeExprs) {
                    promptBuilder.append("  ").append(exprIndex++).append(". ")
                    .append(expr.get("expression"))
                    .append(" → ").append(expr.get("description"))
                    .append(" (").append(expr.get("type")).append(")")
                    .append("\n  SQL条件: ").append(expr.get("sql_condition"))
                    .append("\n");
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
                            log.info("列映射详情: {} → {}.{}, 样本值数量={}", 
                            termName, tableName, columnName, 
                            sampleValues != null ? sampleValues.size() : 0);
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
            log.info("列映射完成");
        }

        // 数据库结构
        promptBuilder.append("数据库结构：\n").append(schemaInfo).append("\n\n");

        // 要求
        promptBuilder.append("""
            要求：
            1. 先判断用户要“明细”还是“统计结果”：
               - 如果用户问“多少/总量/总出货量/一共出了多少/合计/总计/平均/最大/最小/占比”等统计问题，必须直接在 SQL 中使用聚合函数，不要返回明细行。
               - 如果用户问“明细/list/有哪些/分别是什么记录”等明细问题，才返回明细行。
               - 对出货相关问题，默认指标是 Volume；“出货量多少/出了多少货”通常表示 SUM(Volume)，不是 COUNT(*)。
            2. 只选择必要的字段：
               - 过滤条件相关的字段（如 PRODUCT_CATEGORY, Year, Month）
               - 分析目标字段（如 Volume 或 SUM(Volume)）
               - 分组维度字段（如明确要求按产品/时间/GEO分组时）
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
               - 自然年月范围要按用户字面时间精确过滤。例如“2023年3月到2024年4月”表示 2023-03-01 至 2024-04-30：
                 WHERE ((Year = 2023 AND Month >= 3) OR (Year = 2024 AND Month <= 4))
               - 如果跨越两年以上，必须包含中间完整年份：
                 WHERE ((Year = 2023 AND Month >= 3) OR (Year > 2023 AND Year < 2025) OR (Year = 2025 AND Month <= 4))
               - "2024.2-25.2" 表示 FY24 M2 到 FY25 M2（跨财年），需要用 OR 连接：
                 WHERE (FiscalYear = 2024 AND FiscalMonth >= 2) OR (FiscalYear = 2025 AND FiscalMonth <= 2)
               - FiscalMonth 只有 1-12，不可能有 13 以上的值
               - "上个月/上个季度/上个财年" 等相对时间，需要计算具体的年月值，不要使用数据库函数
            5. 产品型号中的数字识别：
               - "S3 15" 中的 "15" 指屏幕尺寸，需要添加 PRODUCT_SCREENSIZE = '15' 或 PRODUCT_SCREENSIZE = '15.6'
               - "Yoga Pro 7" 中的 "7" 是产品系列名称的一部分，应该在 PRODUCT_NAME 或 PRODUCT_SERIES 中匹配
            6. 统计查询的聚合规则：
               - “出货量多少/出了多少货/总出货量” -> 使用 SUM(Volume)
               - “多少条记录/多少个产品/多少个型号” -> 使用 COUNT()
               - “每月/每季度/各GEO分别多少” -> 先按对应维度 GROUP BY，再对 Volume 做 SUM()
               - 如果用户没有要求“分别/按月/by month/by geo”，就返回一个总值，不要附带 Year、Month 等明细维度列
            7. 如果使用聚合函数（SUM/COUNT/AVG等），则 SELECT 中所有非聚合列都必须出现在 GROUP BY 中。
            8. 不要在同一 SELECT 中混用聚合列和非聚合明细列（如 customer_id、gender），除非它们都在 GROUP BY 里。
            9. 如果是时间序列分析，请确保包含日期字段。
            10. 只返回 SQL，不要解释，不要 markdown 代码块。
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
