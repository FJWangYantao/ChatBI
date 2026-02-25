package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.CorrectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class Text2SQLAgent {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final ReadSchemaStructureService schemaService;
    private final SQLCorrectionAgent sqlCorrectionAgent;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;

    public Text2SQLAgent(ChatClient.Builder chatClientBuilder,
                         ModelOptionsProvider modelOptions,
                         ReadSchemaStructureService schemaService,
                         SQLCorrectionAgent sqlCorrectionAgent,
                         DynamicJdbcTemplateProvider jdbcTemplateProvider) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.schemaService = schemaService;
        this.sqlCorrectionAgent = sqlCorrectionAgent;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    /**
     * 获取数据（用于分析）
     */
    public List<Map<String, Object>> fetchData(String dataQuery) {
        log.info("[Text2SQLAgent] Fetching data for query: {}", dataQuery);

        // 1. 获取 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        // 2. 生成 SQL
        String systemPrompt = String.format("""
            你是一个数据库专家。为了回答用户的分析问题，请生成一条 SQL 语句查询必要的数据。
            
            用户需求：%s
            数据库结构：
            %s
            
            要求：
            1. 优先查询明细行数据（不使用聚合函数），让 Python 做后续统计分析。
            2. 如果必须使用聚合函数（SUM/COUNT/AVG等），则 SELECT 中所有非聚合列都必须出现在 GROUP BY 中。
            3. 不要在同一 SELECT 中混用聚合列和非聚合明细列（如 customer_id、gender），除非它们都在 GROUP BY 里。
            4. 如果是时间序列分析，请确保包含日期字段。
            5. 只返回 SQL，不要解释，不要 markdown 代码块。
            """, dataQuery, schemaInfo);
        
        String sql = chatClient.prompt()
                .options(modelOptions.getOptions("text2sql"))
                .user(systemPrompt)
                .call()
                .content();
        
        if (sql == null || sql.trim().isEmpty()) {
            log.warn("[Text2SQLAgent] Failed to generate SQL");
            return Collections.emptyList();
        }

        // 3. 纠错
        CorrectionResult correctionResult = sqlCorrectionAgent.correctSQL(sql, dataQuery, null);
        String finalSQL = correctionResult.getCorrectedSQL();
        log.info("[Text2SQLAgent] Final SQL: {}", finalSQL);

        // 4. 执行
        try {
            // 清理 SQL
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
}
