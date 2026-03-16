package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.config.SandboxToolsConfig;
import com.chatbi.dto.*;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * SQL 执行服务
 * 负责执行用户手动编辑的 SQL 语句，并生成 AI 总结和图表推荐
 */
@Slf4j
@Service
public class SqlExecutionService {

    private final DynamicChatClientFactory chatClientFactory;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;
    private final ChartTypeService chartTypeService;
    private final ModelPerformanceMonitor performanceMonitor;

    // ThreadLocal 用于存储当前请求的意图信息
    private final ThreadLocal<IntentRecognitionResponse> currentIntentInfo = new ThreadLocal<>();

    // ThreadLocal 用于存储当前请求的对话ID
    private final ThreadLocal<String> currentConversationId = new ThreadLocal<>();

    public SqlExecutionService(
            DynamicChatClientFactory chatClientFactory,
            ModelOptionsProvider modelOptions,
            DynamicJdbcTemplateProvider jdbcTemplateProvider,
            ChartTypeService chartTypeService,
            ModelPerformanceMonitor performanceMonitor
    ) {
        this.chatClientFactory = chatClientFactory;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.chartTypeService = chartTypeService;
        this.performanceMonitor = performanceMonitor;
    }

    /**
     * 执行用户提供的 SQL 语句
     */
    public ChatResponse executeSql(String sql) {
        return executeSQLAndBuildResponse("Manual Execution", sql);
    }

    /**
     * 获取 JdbcTemplate
     */
    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getJdbcTemplate();
    }

    /**
     * 执行 SQL 并构建标签化响应
     * 支持多条 SQL 语句（使用 ###SQL_SEPARATOR### 分隔）
     */
    private ChatResponse executeSQLAndBuildResponse(String question, String sql) {
        String conversationId = currentConversationId.get();
        String sqlPreview = sql != null && sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
        log.info("执行 SQL: conversationId={}, sqlPreview={}", conversationId, sqlPreview);

        // 获取当前意图信息
        IntentRecognitionResponse intentInfo = currentIntentInfo.get();
        String subtype = intentInfo != null ? intentInfo.getSubtype() : null;

        List<MessageTag> tags = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // 分割多条 SQL
        String[] sqls = sql.split("###SQL_SEPARATOR###");
        List<Map<String, Object>> allRowsForSummary = new ArrayList<>();
        boolean hasError = false;
        StringBuilder errorMsg = new StringBuilder();

        for (String singleSql : sqls) {
            if (singleSql.trim().isEmpty()) continue;

            try {
                // 清理 SQL 语句
                String cleanSql = singleSql.trim()
                        .replaceAll("^```sql\\s*", "")
                        .replaceAll("^```\\s*", "")
                        .replaceAll("\\s*```$", "")
                        .trim();

                // 执行查询
                List<Map<String, Object>> rows = getJdbcTemplate().queryForList(cleanSql);

                // 收集用于总结的数据（限制数量防止 Token 超限）
                if (allRowsForSummary.size() < 20) {
                    int needed = 20 - allRowsForSummary.size();
                    allRowsForSummary.addAll(rows.subList(0, Math.min(rows.size(), needed)));
                }

                // 构建查询结果
                QueryResult queryResult = new QueryResult();
                if (!rows.isEmpty()) {
                    queryResult.setColumns(new ArrayList<>(rows.get(0).keySet()));
                } else {
                    queryResult.setColumns(new ArrayList<>());
                }

                // 分页传输：rows > 50 时只推预览数据 + dataRefId
                int previewLimit = 50;
                if (rows.size() > previewLimit) {
                    queryResult.setRows(rows.subList(0, previewLimit));
                    // 全量数据序列化存入 DATA_STORE
                    try {
                        ObjectMapper jsonMapper = new ObjectMapper().registerModule(new JavaTimeModule());
                        String fullJson = jsonMapper.writeValueAsString(rows);
                        String refId = "data_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        SandboxToolsConfig.DATA_STORE.put(refId, new SandboxToolsConfig.DataEntry(fullJson));
                        queryResult.setDataRefId(refId);
                        log.info("大数据集分页: refId={}, totalRows={}, previewRows={}", refId, rows.size(), previewLimit);
                    } catch (Exception e) {
                        log.warn("序列化全量数据失败，降级为截断模式: {}", e.getMessage());
                        queryResult.setRows(rows.subList(0, Math.min(rows.size(), 500)));
                    }
                } else {
                    queryResult.setRows(rows);
                }

                queryResult.setTotalRows(rows.size());
                queryResult.setSuccess(true);
                queryResult.setExecutionTime(0L); // 多查询暂不统计单条耗时

                // 添加标签
                tags.add(new MessageTag("sql", cleanSql, "SQL 查询", null));
                tags.add(new MessageTag("table", queryResult, "查询结果 (" + rows.size() + " 行)", null));

                // 尝试生成图表
                MessageTag chartTag = chartTypeService.createChartTag(queryResult, subtype);
                if (chartTag != null) {
                    tags.add(chartTag);
                }

                performanceMonitor.recordSQLExecution(true);

            } catch (Exception e) {
                log.error("SQL 执行失败: {}", e.getMessage());
                hasError = true;
                errorMsg.append(e.getMessage()).append("; ");
                performanceMonitor.recordSQLExecution(false);

                tags.add(new MessageTag("sql", singleSql, "SQL 查询（执行失败）", null));

                QueryResult errorResult = new QueryResult();
                errorResult.setSuccess(false);
                errorResult.setError(e.getMessage());

                tags.add(new MessageTag("error", errorResult, "执行错误", null));
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        performanceMonitor.recordTiming("sql_execution_ms", totalTime);
        String convId = currentConversationId.get();
        int rowCount = tags.stream()
                .filter(t -> "table".equals(t.getType()))
                .map(MessageTag::getContent)
                .filter(d -> d instanceof QueryResult)
                .map(d -> ((QueryResult) d).getTotalRows() != null ? ((QueryResult) d).getTotalRows() : 0)
                .reduce(0, Integer::sum);
        log.info("[STRUCT] event=sql_execution conversationId={} duration={}ms sql_count={} row_count={} success={}",
                convId, totalTime, sqls.length, rowCount, !hasError);
        log.info("SQL 执行完成: conversationId={}, 耗时={}ms", convId, totalTime);

        // 生成总结
        String summary;
        if (hasError && tags.stream().noneMatch(t -> "table".equals(t.getType()))) {
            // 如果全部失败
            summary = "SQL 执行失败：" + errorMsg.toString();
        } else {
            // 如果部分成功或全部成功
            summary = generateResultSummary(question, allRowsForSummary);
            if (hasError) {
                summary += "\n\n(注意：部分查询执行失败)";
            }
        }

        return new ChatResponse(summary, tags);
    }

    /**
     * 根据查询结果生成自然语言总结
     */
    private String generateResultSummary(String question, List<Map<String, Object>> rows) {
        // 1. 处理空数据情况
        if (rows.isEmpty()) {
            return "未查询到相关数据。";
        }

        // 2. 准备数据预览 (避免 Token 超限，仅取前 5 条)
        int previewCount = Math.min(rows.size(), 5);
        List<Map<String, Object>> previewData = rows.subList(0, previewCount);

        // 3. 构造 Prompt
        String prompt = String.format("""
            用户问题：%s
            总行数：%d
            数据预览（前 %d 行）：%s

            请根据上述数据，用一句话简要回答用户问题或总结数据结果。
            要求：
            1. 必须基于提供的数据回答，严禁编造。
            2. 语言自然、简洁（100字以内）。
            3. 如果是统计类数据（如总销售额），直接回答数值。
            4. 如果是列表类数据（如前十名），列举前 1-3 个关键项并概括。
            5. 不要提及 "SQL"、"查询"、"数据库" 等技术术语。
            """,
                question,
                rows.size(),
                previewCount,
                previewData.toString()
        );

        // 4. 调用 AI (增加异常处理，失败则降级)
        try {
            // 动态创建 ChatClient 使用前端配置
            ChatClient chatClient = chatClientFactory.createChatClient("chat");
            // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("生成总结失败", e);
            return "查询成功，结果如下："; // 降级回复
        }
    }
}
