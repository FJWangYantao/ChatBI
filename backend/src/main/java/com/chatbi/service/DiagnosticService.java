package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.ChatResponse;
import com.chatbi.dto.MessageTag;
import com.chatbi.dto.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DiagnosticService {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final ReadSchemaStructureService schemaService;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;
    private final ChartTypeService chartTypeService;

    public DiagnosticService(
            ChatClient.Builder chatClientBuilder,
            ModelOptionsProvider modelOptions,
            ReadSchemaStructureService schemaService,
            DynamicJdbcTemplateProvider jdbcTemplateProvider,
            ChartTypeService chartTypeService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.schemaService = schemaService;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.chartTypeService = chartTypeService;
    }

    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getJdbcTemplate();
    }

    /**
     * 执行归因分析
     */
    public ChatResponse analyzeRootCause(String question) {
        log.info("开始归因分析: {}", question);
        
        // 1. 获取 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        // 2. 生成分析 SQL (包含总体对比和维度下钻)
        String analysisPrompt = String.format("""
            你是一个高级数据分析师。用户提出了一个关于数据变化原因的问题（归因分析）。
            
            用户问题：%s
            
            数据库结构：
            %s
            
            你的任务是生成一组 SQL 查询来分析数据变化的原因。
            
            请遵循以下步骤：
            1. **理解意图**：识别用户关注的指标（如 GMV、销售额）、时间范围（如昨天 vs 前天，本周 vs 上周）和变化方向。
            2. **总体验证**：生成第1条 SQL，计算两个时间段的该指标总量，以验证用户的说法。
            3. **维度下钻**：识别 3-5 个最可能解释变化的维度（如地区、产品分类、渠道、用户类型等，通常是低基数的分类列）。
            4. **生成下钻 SQL**：对于每个维度，生成一条 SQL，按该维度分组，计算两个时间段的指标值，并按“变化量的绝对值”降序排列。
            
            SQL 要求：
            - 使用 MySQL 语法。
            - 确保处理日期格式，正确筛选两个时间段的数据。
            - 每一条 SQL 必须包含维度列（如果是总体验证则不需要）、当前时间段数值、对比时间段数值。
            - 列名请使用中文别名，例如 "维度", "当前值", "对比值", "变化量"。
            - 仅返回 SQL 语句，使用 "###SQL_SEPARATOR###" 分隔每条 SQL。
            - 不要返回任何解释性文字。
            """, question, schemaInfo);

        String sqlsResponse = chatClient.prompt()
                .options(modelOptions.getOptions("diagnostic"))
                .user(analysisPrompt)
                .call()
                .content();

        // 3. 执行 SQL 并收集结果
        String[] sqls = sqlsResponse.split("###SQL_SEPARATOR###");
        List<MessageTag> tags = new ArrayList<>();
        StringBuilder dataSummary = new StringBuilder();
        
        int queryIndex = 0;
        for (String sql : sqls) {
            if (sql.trim().isEmpty()) continue;
            
            String cleanSql = sql.trim()
                    .replaceAll("^```sql\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();
            
            try {
                List<Map<String, Object>> rows = getJdbcTemplate().queryForList(cleanSql);
                
                // 记录数据用于后续 AI 总结
                dataSummary.append(String.format("\n--- 查询 %d 结果 ---\nSQL: %s\n结果 (前10行): %s\n", 
                        ++queryIndex, cleanSql, rows.subList(0, Math.min(rows.size(), 10))));

                // 构建前端展示的 Tag
                QueryResult queryResult = new QueryResult();
                if (!rows.isEmpty()) {
                    queryResult.setColumns(new ArrayList<>(rows.get(0).keySet()));
                } else {
                    queryResult.setColumns(new ArrayList<>());
                }
                queryResult.setRows(rows.subList(0, Math.min(rows.size(), 100))); // 限制前端展示行数
                queryResult.setTotalRows(rows.size());
                queryResult.setSuccess(true);

                // 添加表格 Tag
                tags.add(new MessageTag("sql", cleanSql, "分析查询 " + queryIndex, null));
                tags.add(new MessageTag("table", queryResult, "分析结果 " + queryIndex, null));
                
                // 尝试生成图表 (对于下钻分析，柱状图或瀑布图比较合适)
                // 这里简单复用 ChartTypeService，或者可以指定生成对比图
                if (rows.size() > 1) {
                     // 简单的启发式：如果有 "当前值" 和 "对比值"，适合做对比图
                     // 暂时复用通用逻辑
                     MessageTag chartTag = chartTypeService.createChartTag(queryResult, "COMPARISON_ANALYSIS");
                     if (chartTag != null) {
                         tags.add(chartTag);
                     }
                }

            } catch (Exception e) {
                log.error("归因分析 SQL 执行失败: {}", cleanSql, e);
                dataSummary.append(String.format("\n--- 查询 %d 失败 ---\nSQL: %s\n错误: %s\n", 
                        ++queryIndex, cleanSql, e.getMessage()));
            }
        }

        // 4. 生成归因报告
        // 转义 dataSummary 中的 % 字符，避免 String.format 解析错误
        String escapedDataSummary = dataSummary.toString().replace("%", "%%");
        String reportPrompt = String.format("""
            你是一个专业的数据分析师。根据以下数据分析结果，回答用户的问题："%s"
            
            分析过程数据：
            %s
            
            请生成一份简明扼要的归因分析报告。
            要求：
            1. **结论先行**：直接回答变化的主要原因是什么。
            2. **数据支撑**：引用数据来支持你的结论（例如"虽然整体下跌了15%%，但A地区下跌了40%%，贡献了绝大部分跌幅"）。
            3. **逻辑清晰**：先看总体变化是否属实，再看各个维度的贡献。
            4. **通俗易懂**：避免堆砌技术术语，用业务语言解释。
            5. 如果数据不支持用户的假设（例如用户说跌了，但数据通过显示涨了），请礼貌地指出。
            """, question, escapedDataSummary);

        String report = chatClient.prompt()
                .options(modelOptions.getOptions("diagnostic"))
                .user(reportPrompt)
                .call()
                .content();

        return new ChatResponse(report, tags);
    }
}
