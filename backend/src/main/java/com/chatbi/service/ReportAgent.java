package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.ChatResponse;
import com.chatbi.dto.MessageDTO;
import com.chatbi.dto.MessageTag;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    /** 表格数据最大行数，防止 token 溢出 */
    private static final int MAX_TABLE_ROWS = 50;
    /** 传给 LLM 的数据上下文最大字符数 */
    private static final int MAX_DATA_CONTEXT_CHARS = 8000;

    public ReportAgent(DynamicChatClientFactory chatClientFactory,
                       ModelOptionsProvider modelOptions,
                       ConversationService conversationService,
                       ObjectMapper objectMapper) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成分析报告（旧接口，保留兼容）
     */
    public String generateReport(String conversationId) {
        ChatResponse response = generateInsightReport(conversationId);
        return response.getReply();
    }

    /**
     * 生成带 AI Insight 的结构化分析报告
     */
    public ChatResponse generateInsightReport(String conversationId) {
        log.info("[ReportAgent] 生成 Insight 报告, conversationId={}", conversationId);

        List<MessageDTO> history = conversationService.getMessages(conversationId);

        if (history == null || history.isEmpty()) {
            return new ChatResponse("当前对话没有足够的内容生成报告。", null);
        }

        // 1. 提取对话中的结构化数据
        ReportDataContext dataContext = extractDataContext(history);
        logDataContext(dataContext);

        // 2. 若无结构化数据，降级为纯文本摘要
        if (!dataContext.hasStructuredData()) {
            log.info("[ReportAgent] 无结构化数据，降级为纯文本报告");
            String textReport = generateTextReport(history);
            return new ChatResponse(textReport, null);
        }

        // 3. 生成 AI Insight
        try {
            String insightJson = callLLMForInsight(dataContext);
            Map<String, Object> insight = parseInsightJson(insightJson);

            if (insight == null) {
                log.warn("[ReportAgent] Insight JSON 解析失败，降级为纯文本报告");
                String textReport = generateTextReport(history);
                return new ChatResponse(textReport, null);
            }

            // 4. 构建结构化 tags + 报告正文
            List<MessageTag> tags = buildInsightTags(insight);
            String reportText = buildReportText(insight);

            return new ChatResponse(reportText, tags);

        } catch (Exception e) {
            log.error("[ReportAgent] Insight 生成异常，降级为纯文本报告", e);
            String textReport = generateTextReport(history);
            return new ChatResponse(textReport, null);
        }
    }

    // ─── 数据提取 ────────────────────────────────────────────────────────

    /**
     * 从对话历史的 tags 中提取结构化数据
     */
    private ReportDataContext extractDataContext(List<MessageDTO> history) {
        List<String> userQueries = new ArrayList<>();
        List<String> sqlStatements = new ArrayList<>();
        List<Object> tableDataList = new ArrayList<>();
        List<String> analysisTexts = new ArrayList<>();

        for (MessageDTO msg : history) {
            // 收集用户提问
            if ("user".equals(msg.getRole())) {
                userQueries.add(msg.getContent());
            }
            // 收集助手回复文本
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                analysisTexts.add(msg.getContent());
            }
            // 从 tags 提取结构化数据
            if (msg.getTags() != null) {
                for (MessageTag tag : msg.getTags()) {
                    if (tag.getType() == null) continue;
                    switch (tag.getType()) {
                        case "sql" -> sqlStatements.add(String.valueOf(tag.getContent()));
                        case "table" -> tableDataList.add(tag.getContent());
                        case "analysis_result" -> {
                            try {
                                analysisTexts.add(objectMapper.writeValueAsString(tag.getContent()));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }

        return new ReportDataContext(userQueries, sqlStatements, tableDataList, analysisTexts);
    }

    // 补充日志用于调试
    private void logDataContext(ReportDataContext ctx) {
        log.info("[ReportAgent] 数据提取结果: userQueries={}, sql={}, tables={}, analysisTexts={}",
                ctx.userQueries.size(), ctx.sqlStatements.size(),
                ctx.tableDataList.size(), ctx.analysisTexts.size());
    }

    // ─── LLM 调用 ────────────────────────────────────────────────────────

    /**
     * 调用 LLM 生成 Insight JSON
     */
    private String callLLMForInsight(ReportDataContext ctx) {
        String queriesStr = String.join("\n", ctx.userQueries);

        String sqlStr = ctx.sqlStatements.stream()
                .distinct()
                .collect(Collectors.joining("\n---\n"));

        // 表格数据序列化（截取行数限制）
        String dataStr = serializeTableData(ctx.tableDataList);

        // 已有分析文本（截取长度）
        String analysisStr = ctx.analysisTexts.stream()
                .limit(5)
                .collect(Collectors.joining("\n---\n"));

        // 总长度控制
        if (dataStr.length() > MAX_DATA_CONTEXT_CHARS) {
            dataStr = dataStr.substring(0, MAX_DATA_CONTEXT_CHARS) + "\n... (数据已截断)";
        }

        String prompt = String.format("""
            你是一名资深数据分析师。请基于以下对话上下文和实际查询数据，生成深度洞察分析。

            ## 用户提问历史
            %s

            ## 已执行的 SQL 查询
            %s

            ## 查询结果数据
            %s

            ## 已有的分析结论
            %s

            请严格按以下 JSON 格式返回（不要包含 markdown 代码块标记）：
            {
              "executive_summary": "核心发现（2-3句话概括）",
              "key_metrics": [
                {"label": "指标名称", "value": "指标值", "trend": "up/down/stable", "change": "变化幅度如+15%%"}
              ],
              "insights": [
                {"title": "洞察标题", "description": "详细分析描述", "category": "趋势/异常/对比/关联"}
              ],
              "recommendations": [
                {"action": "具体建议行动", "priority": "high/medium/low", "expected_impact": "预期效果"}
              ]
            }

            要求：
            1. key_metrics 提取 3-6 个核心指标，基于实际查询数据
            2. insights 提供 2-4 条深度洞察，发现数据背后的规律和异常
            3. recommendations 给出 2-3 条可执行的行动建议
            4. 所有内容基于实际数据，不要编造数字
            """, queriesStr, sqlStr, dataStr, analysisStr);

        ChatClient chatClient = chatClientFactory.createChatClient("report");
        // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 基于当前分析结果直接生成 AI Insight（供分析流水线调用）
     *
     * @param question       用户原始问题
     * @param analysisGoal   分析目标（来自 AgentPlan）
     * @param analysisOutput 沙箱执行的 stdout 输出
     * @return 包含 insight tags 的 ChatResponse（reply 为报告正文，tags 为结构化洞察）
     */
    public ChatResponse generateInsightFromAnalysis(String question, String analysisGoal, String analysisOutput) {
        log.info("[ReportAgent] 基于分析结果生成 Insight, question={}", question);

        if (analysisOutput == null || analysisOutput.isBlank()) {
            return new ChatResponse("分析已完成，但未产生足够数据用于生成洞察报告。", null);
        }

        try {
            // 截取防溢出
            String dataPreview = analysisOutput.length() > MAX_DATA_CONTEXT_CHARS
                    ? analysisOutput.substring(0, MAX_DATA_CONTEXT_CHARS) + "\n... (数据已截断)"
                    : analysisOutput;

            String prompt = String.format("""
                你是一名资深数据分析师。请基于以下分析目标和执行结果，生成深度洞察报告。

                ## 用户问题
                %s

                ## 分析目标
                %s

                ## 分析执行结果
                %s

                请严格按以下 JSON 格式返回（不要包含 markdown 代码块标记）：
                {
                  "executive_summary": "核心发现（2-3句话概括）",
                  "key_metrics": [
                    {"label": "指标名称", "value": "指标值", "trend": "up/down/stable", "change": "变化幅度如+15%%"}
                  ],
                  "insights": [
                    {"title": "洞察标题", "description": "详细分析描述", "category": "趋势/异常/对比/关联"}
                  ],
                  "recommendations": [
                    {"action": "具体建议行动", "priority": "high/medium/low", "expected_impact": "预期效果"}
                  ]
                }

                要求：
                1. key_metrics 提取 3-6 个核心指标，基于实际分析结果中的数字
                2. insights 提供 2-4 条深度洞察，发现数据背后的规律和异常
                3. recommendations 给出 2-3 条可执行的行动建议
                4. 所有内容基于实际数据，不要编造数字
                """, question, analysisGoal, dataPreview);

            ChatClient chatClient = chatClientFactory.createChatClient("report");
            // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
            String insightJson = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            Map<String, Object> insight = parseInsightJson(insightJson);
            if (insight == null) {
                log.warn("[ReportAgent] Insight JSON 解析失败");
                return new ChatResponse(null, null);
            }

            List<MessageTag> tags = buildInsightTags(insight);
            String reportText = buildReportText(insight);
            return new ChatResponse(reportText, tags);

        } catch (Exception e) {
            log.error("[ReportAgent] 分析 Insight 生成异常", e);
            return new ChatResponse(null, null);
        }
    }

    /**
     * 纯文本报告（降级方案）
     */
    private String generateTextReport(List<MessageDTO> history) {
        String transcript = history.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
            你是一个专业的数据分析师。请根据以下的对话记录，生成一份结构化的分析报告。

            对话记录：
            %s

            报告要求：
            1. 使用 Markdown 格式。
            2. 包含标题、背景、主要发现、数据结论、建议行动。
            3. 语言简练，重点突出。
            4. 忽略闲聊内容，只关注数据分析部分。
            """, transcript);

        ChatClient chatClient = chatClientFactory.createChatClient("report");
        // 注意：不再调用 .options()，使用 createChatClient 中设置的 defaultOptions（前端配置）
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    // ─── JSON 解析 ────────────────────────────────────────────────────────

    /**
     * 解析 LLM 返回的 Insight JSON
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInsightJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 去除可能的 markdown 代码块标记
        String cleaned = raw.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.strip();

        try {
            return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[ReportAgent] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ─── 构建响应 ────────────────────────────────────────────────────────

    /**
     * 将 Insight 数据转换为 MessageTag 列表
     */
    @SuppressWarnings("unchecked")
    private List<MessageTag> buildInsightTags(Map<String, Object> insight) {
        List<MessageTag> tags = new ArrayList<>();

        // 1. 关键指标卡片
        List<Map<String, Object>> keyMetrics = (List<Map<String, Object>>) insight.get("key_metrics");
        if (keyMetrics != null && !keyMetrics.isEmpty()) {
            List<Map<String, String>> statsItems = keyMetrics.stream()
                    .map(m -> {
                        String label = String.valueOf(m.getOrDefault("label", ""));
                        String value = String.valueOf(m.getOrDefault("value", ""));
                        String trend = String.valueOf(m.getOrDefault("trend", ""));
                        String change = String.valueOf(m.getOrDefault("change", ""));
                        // 在 value 中附加趋势标记
                        String displayValue = value;
                        if (!change.isEmpty() && !"null".equals(change)) {
                            String arrow = switch (trend) {
                                case "up" -> " ↑";
                                case "down" -> " ↓";
                                default -> " →";
                            };
                            displayValue = value + arrow + " " + change;
                        }
                        return Map.of("label", label, "value", displayValue);
                    })
                    .collect(Collectors.toList());

            Map<String, Object> metricsContent = Map.of(
                    "sections", List.of(Map.of(
                            "type", "stats",
                            "title", "关键指标",
                            "items", statsItems
                    ))
            );
            tags.add(new MessageTag("analysis_result", metricsContent, "关键指标概览", null));
        }

        // 2. 深度洞察
        List<Map<String, Object>> insights = (List<Map<String, Object>>) insight.get("insights");
        if (insights != null && !insights.isEmpty()) {
            StringBuilder insightMd = new StringBuilder();
            for (Map<String, Object> item : insights) {
                String category = String.valueOf(item.getOrDefault("category", ""));
                String title = String.valueOf(item.getOrDefault("title", ""));
                String description = String.valueOf(item.getOrDefault("description", ""));
                insightMd.append("### 【").append(category).append("】").append(title).append("\n\n");
                insightMd.append(description).append("\n\n");
            }

            Map<String, Object> insightContent = Map.of(
                    "sections", List.of(Map.of(
                            "type", "text",
                            "title", "深度洞察",
                            "content", insightMd.toString()
                    ))
            );
            tags.add(new MessageTag("analysis_result", insightContent, "深度洞察分析", null));
        }

        // 3. 行动建议
        List<Map<String, Object>> recommendations = (List<Map<String, Object>>) insight.get("recommendations");
        if (recommendations != null && !recommendations.isEmpty()) {
            List<Map<String, String>> recItems = recommendations.stream()
                    .map(r -> {
                        String priority = String.valueOf(r.getOrDefault("priority", "medium"));
                        String priorityLabel = switch (priority) {
                            case "high" -> "高优先级";
                            case "low" -> "低优先级";
                            default -> "中优先级";
                        };
                        String action = String.valueOf(r.getOrDefault("action", ""));
                        String impact = String.valueOf(r.getOrDefault("expected_impact", ""));
                        String displayValue = action;
                        if (!impact.isEmpty() && !"null".equals(impact)) {
                            displayValue = action + "（预期: " + impact + "）";
                        }
                        return Map.of("label", priorityLabel, "value", displayValue);
                    })
                    .collect(Collectors.toList());

            Map<String, Object> recContent = Map.of(
                    "sections", List.of(Map.of(
                            "type", "stats",
                            "title", "行动建议",
                            "items", recItems
                    ))
            );
            tags.add(new MessageTag("analysis_result", recContent, "行动建议", null));
        }

        return tags;
    }

    /**
     * 构建报告 Markdown 正文
     */
    private String buildReportText(Map<String, Object> insight) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 数据分析报告\n\n");

        String summary = String.valueOf(insight.getOrDefault("executive_summary", ""));
        if (!summary.isEmpty() && !"null".equals(summary)) {
            sb.append("### 核心发现\n\n");
            sb.append(summary).append("\n\n");
        }

        sb.append("> 以上关键指标、深度洞察和行动建议基于本次对话中的实际查询数据生成。\n");

        return sb.toString();
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────

    /**
     * 序列化表格数据（带行数限制）
     */
    @SuppressWarnings("unchecked")
    private String serializeTableData(List<Object> tableDataList) {
        if (tableDataList.isEmpty()) return "（无查询结果数据）";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tableDataList.size(); i++) {
            Object tableData = tableDataList.get(i);
            sb.append("### 查询结果 ").append(i + 1).append("\n");
            try {
                // 表格数据可能是 Map 或 List 等多种格式
                if (tableData instanceof Map) {
                    Map<String, Object> table = (Map<String, Object>) tableData;
                    Object rows = table.get("rows");
                    if (rows instanceof List && ((List<?>) rows).size() > MAX_TABLE_ROWS) {
                        List<?> allRows = (List<?>) rows;
                        Map<String, Object> truncated = new HashMap<>(table);
                        truncated.put("rows", allRows.subList(0, MAX_TABLE_ROWS));
                        truncated.put("_note", "数据已截取前" + MAX_TABLE_ROWS + "行，总计" + allRows.size() + "行");
                        sb.append(objectMapper.writeValueAsString(truncated));
                    } else {
                        sb.append(objectMapper.writeValueAsString(tableData));
                    }
                } else {
                    sb.append(objectMapper.writeValueAsString(tableData));
                }
            } catch (Exception e) {
                sb.append(String.valueOf(tableData));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    // ─── 内部数据类 ──────────────────────────────────────────────────────

    /**
     * 报告数据上下文
     */
    private record ReportDataContext(
            List<String> userQueries,
            List<String> sqlStatements,
            List<Object> tableDataList,
            List<String> analysisTexts
    ) {
        boolean hasStructuredData() {
            // sql/table tag 或 analysis_result tag（序列化后加入 analysisTexts）都算有数据
            return !sqlStatements.isEmpty() || !tableDataList.isEmpty() || !analysisTexts.isEmpty();
        }
    }
}
