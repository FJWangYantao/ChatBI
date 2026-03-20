package com.chatbi.service;

import com.chatbi.context.LLMConfigContext;
import com.chatbi.dto.MessageTag;
import com.chatbi.dto.QueryResult;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class DiagnosticService {

    private final DynamicChatClientFactory chatClientFactory;
    private final ReadSchemaStructureService schemaService;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;
    private final ChartTypeService chartTypeService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = buildHttpClient();

    private static HttpClient buildHttpClient() {
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))))
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public DiagnosticService(
            DynamicChatClientFactory chatClientFactory,
            ReadSchemaStructureService schemaService,
            DynamicJdbcTemplateProvider jdbcTemplateProvider,
            ChartTypeService chartTypeService
    ) {
        this.chatClientFactory = chatClientFactory;
        this.schemaService = schemaService;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.chartTypeService = chartTypeService;
    }

    /**
     * Phase 1 结果：SQL 执行后的 tags + 数据摘要
     */
    public record AnalysisQueryResult(List<MessageTag> tags, String dataSummary) {}

    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getJdbcTemplate();
    }

    /**
     * Phase 1: 生成分析 SQL 并执行查询
     */
    public AnalysisQueryResult generateAndExecuteQueries(String question) {
        log.info("[DiagnosticService] Phase1 开始: {}", question);

        // 1. 获取 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        // 2. 生成分析 SQL
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
            4. **生成下钻 SQL**：对于每个维度，生成一条 SQL，按该维度分组，计算两个时间段的指标值，并按"变化量的绝对值"降序排列。

            SQL 要求：
            - 使用 MySQL 语法。
            - 确保处理日期格式，正确筛选两个时间段的数据。
            - 每一条 SQL 必须包含维度列（如果是总体验证则不需要）、当前时间段数值、对比时间段数值。
            - 列名请使用中文别名，例如 "维度", "当前值", "对比值", "变化量"。
            - 仅返回 SQL 语句，使用 "###SQL_SEPARATOR###" 分隔每条 SQL。
            - 不要返回任何解释性文字。
            """, question, schemaInfo);

        ChatClient chatClient = chatClientFactory.createChatClient("diagnostic");

        log.info("[DiagnosticService] 开始LLM调用（生成分析SQL），prompt长度: {}", analysisPrompt.length());
        long llmStart = System.currentTimeMillis();
        String sqlsResponse = chatClient.prompt()
                .user(analysisPrompt)
                .call()
                .content();
        log.info("[DiagnosticService] LLM调用完成（生成分析SQL），耗时: {}ms", System.currentTimeMillis() - llmStart);

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

                dataSummary.append(String.format("\n--- 查询 %d 结果 ---\nSQL: %s\n结果 (前10行): %s\n",
                        ++queryIndex, cleanSql, rows.subList(0, Math.min(rows.size(), 10))));

                QueryResult queryResult = new QueryResult();
                if (!rows.isEmpty()) {
                    queryResult.setColumns(new ArrayList<>(rows.get(0).keySet()));
                } else {
                    queryResult.setColumns(new ArrayList<>());
                }
                queryResult.setRows(rows.subList(0, Math.min(rows.size(), 100)));
                queryResult.setTotalRows(rows.size());
                queryResult.setSuccess(true);

                tags.add(new MessageTag("sql", cleanSql, "分析查询 " + queryIndex, null));
                tags.add(new MessageTag("table", queryResult, "分析结果 " + queryIndex, null));

                if (rows.size() > 1) {
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

        log.info("[DiagnosticService] Phase1 完成，生成 {} 个tags", tags.size());
        return new AnalysisQueryResult(tags, dataSummary.toString());
    }

    /**
     * Phase 2: 流式生成归因报告（原始 HTTP SSE 流，逐 token 推送）
     */
    public String generateReportStreaming(String question, String dataSummary, Consumer<String> onTextDelta) {
        log.info("[DiagnosticService] Phase2 开始（流式生成归因报告）");

        String escapedDataSummary = dataSummary.replace("%", "%%");
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

        long reportStart = System.currentTimeMillis();
        StringBuilder fullText = new StringBuilder();

        try {
            // 从 ThreadLocal 获取前端 LLM 配置
            LLMConfigContext.LLMConfig config = LLMConfigContext.get();
            if (config == null) {
                throw new IllegalStateException("未检测到前端 LLM 配置");
            }

            String apiKey = config.getApiKey();
            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : getDefaultBaseUrl(config.getProvider());
            String model = config.getModelName();

            // 构建原始 HTTP 请求体（stream: true）
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.1,
                    "stream", true,
                    "messages", List.of(Map.of("role", "user", "content", reportPrompt))
            ));

            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            log.info("[DiagnosticService] 开始原始HTTP流式调用, model={}, url={}, promptLength={}", model, url, reportPrompt.length());

            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            log.info("[DiagnosticService] HTTP响应状态: {}", response.statusCode());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[DiagnosticService] API 返回错误: status={}, body={}", response.statusCode(), errBody.substring(0, Math.min(errBody.length(), 500)));
                throw new RuntimeException("API returned " + response.statusCode());
            }

            // 逐行解析 SSE 流
            int tokenCount = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]") || data.isEmpty()) continue;

                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta == null) continue;

                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        String text = contentNode.asText();
                        if (!text.isEmpty()) {
                            fullText.append(text);
                            tokenCount++;
                            if (tokenCount == 1) {
                                log.info("[DiagnosticService] 收到第一个token，延迟: {}ms", System.currentTimeMillis() - reportStart);
                            }
                            try { onTextDelta.accept(text); }
                            catch (Exception e) { log.warn("[DiagnosticService] 流式推送失败: {}", e.getMessage()); }
                        }
                    }
                }
            }

            log.info("[DiagnosticService] 原始HTTP流式调用完成，tokenCount={}, 耗时: {}ms, 文本长度: {}",
                    tokenCount, System.currentTimeMillis() - reportStart, fullText.length());

        } catch (Exception e) {
            log.warn("[DiagnosticService] 原始HTTP流式失败，降级为同步调用: {}", e.getMessage());
            try {
                ChatClient chatClient = chatClientFactory.createChatClient("diagnostic");
                String report = chatClient.prompt()
                        .user(reportPrompt)
                        .call()
                        .content();
                fullText.append(report);
                try { onTextDelta.accept(report); }
                catch (Exception ex) { log.debug("[DiagnosticService] 降级推送失败: {}", ex.getMessage()); }
            } catch (Exception fallbackEx) {
                log.error("[DiagnosticService] 同步降级也失败: {}", fallbackEx.getMessage());
            }
        }

        return fullText.toString();
    }

    private String getDefaultBaseUrl(String provider) {
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }
}
