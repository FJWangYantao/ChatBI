package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.StreamingTagEvent;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * 后置排版 Agent：将 Python stdout 原始输出通过 LLM 转换为 Markdown，
 * 流式发送给前端实时渲染。
 */
@Slf4j
@Service
public class FormattingAgent {

    private static final int MAX_STDOUT_LENGTH = 8000;
    private static final int TRUNCATE_KEEP = 3500;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DynamicChatClientFactory chatClientFactory;
    private final ChatStreamService chatStreamService;
    private final ModelOptionsProvider modelOptions;

    public FormattingAgent(DynamicChatClientFactory chatClientFactory,
                          ChatStreamService chatStreamService,
                          ModelOptionsProvider modelOptions) {
        this.chatClientFactory = chatClientFactory;
        this.chatStreamService = chatStreamService;
        this.modelOptions = modelOptions;
    }

    /**
     * 非流式版本：将 Python stdout 输出转换为结构化 JSON。
     * 仅在无流式回调时使用（兜底）。
     */
    public Map<String, Object> formatAnalysisOutput(String stdout) {
        if (stdout == null || stdout.trim().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sections", List.of());
            return result;
        }

        try {
            String input = preprocessStdout(stdout);
            long start = System.currentTimeMillis();
            String llmResponse = callLLMJson(input);
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> parsed = parseJsonResponse(llmResponse);
            if (parsed != null) {
                log.info("[FormattingAgent] LLM 排版成功，耗时 {}ms，sections 数: {}",
                        elapsed, ((List<?>) parsed.getOrDefault("sections", List.of())).size());
                return parsed;
            }
        } catch (Exception e) {
            log.warn("[FormattingAgent] LLM 排版失败，降级到规则解析: {}", e.getMessage());
        }

        // fallback
        return chatStreamService.parseAnalysisOutput(stdout);
    }

    /**
     * 流式版本（结构化 JSON 输出）：通过 tagCallback 发送 tag_start/end 事件，
     * 前端使用 AnalysisResultRenderer 渲染结构化内容。
     */
    public void formatAnalysisOutputStructured(String stdout, Consumer<StreamingTagEvent> tagCallback) {
        String tagId = "analysis-" + UUID.randomUUID().toString().substring(0, 8);

        if (stdout == null || stdout.trim().isEmpty()) {
            return;
        }

        // 发送 tag_start
        tagCallback.accept(StreamingTagEvent.start(tagId, "analysis_result", "分析详情"));

        // 生成结构化 JSON
        Map<String, Object> analysisOutput = formatAnalysisOutput(stdout);

        // 发送 tag_end（携带结构化 JSON 内容）
        tagCallback.accept(StreamingTagEvent.end(tagId, "analysis_result", "分析详情", analysisOutput));
    }

    /**
     * 流式版本（Markdown 输出）：通过 tagCallback 发送 tag_start/delta/end 事件，
     * 前端实时渲染 Markdown 内容。
     * 三层降级：流式LLM → 同步LLM → 原始文本
     */
    public void formatAnalysisOutputStreaming(String stdout, Consumer<StreamingTagEvent> tagCallback) {
        String tagId = "analysis-" + UUID.randomUUID().toString().substring(0, 8);

        if (stdout == null || stdout.trim().isEmpty()) {
            return;
        }

        // 发送 tag_start
        tagCallback.accept(StreamingTagEvent.start(tagId, "analysis_result", "分析详情"));

        String input = preprocessStdout(stdout);
        StringBuilder fullMarkdown = new StringBuilder();
        boolean success = false;

        // 第一层：流式 LLM（Markdown）
        try {
            long start = System.currentTimeMillis();
            callLLMStreamingMarkdown(input, tagId, tagCallback, fullMarkdown);
            long elapsed = System.currentTimeMillis() - start;
            if (fullMarkdown.length() > 0) {
                success = true;
                log.info("[FormattingAgent] Markdown 流式排版成功，耗时 {}ms，长度: {}",
                        elapsed, fullMarkdown.length());
            }
        } catch (Exception e) {
            if (fullMarkdown.length() > 0) {
                // 部分内容已通过 delta 发送到前端，使用已有内容
                success = true;
                log.warn("[FormattingAgent] 流式部分成功（已接收 {} 字符），使用已有内容: {}",
                        fullMarkdown.length(), e.getMessage());
            } else {
                log.warn("[FormattingAgent] Markdown 流式完全失败，尝试同步降级: {}", e.getMessage());
            }
        }

        // 第二层：同步 LLM（Markdown）
        if (!success) {
            try {
                long start = System.currentTimeMillis();
                String markdown = callLLMMarkdown(input);
                long elapsed = System.currentTimeMillis() - start;
                if (markdown != null && !markdown.isEmpty()) {
                    fullMarkdown.append(markdown);
                    tagCallback.accept(StreamingTagEvent.delta(tagId, markdown));
                    success = true;
                    log.info("[FormattingAgent] Markdown 同步降级成功，耗时 {}ms", elapsed);
                }
            } catch (Exception e2) {
                log.warn("[FormattingAgent] Markdown 同步也失败，使用原始输出: {}", e2.getMessage());
            }
        }

        // 第三层：原始文本兜底
        if (!success) {
            String fallback = "```\n" + stdout + "\n```";
            fullMarkdown.append(fallback);
            tagCallback.accept(StreamingTagEvent.delta(tagId, fallback));
            log.info("[FormattingAgent] 使用原始文本兜底");
        }

        // 发送 tag_end（携带完整 Markdown 内容用于持久化）
        tagCallback.accept(StreamingTagEvent.end(tagId, "analysis_result", "分析详情", fullMarkdown.toString()));
    }

    /**
     * 流式调用 LLM 生成 Markdown，逐 token 通过 tagCallback 发送 tag_delta。
     */
    private void callLLMStreamingMarkdown(String stdout, String tagId,
                                          Consumer<StreamingTagEvent> tagCallback,
                                          StringBuilder accumulator) throws Exception {
        String systemPrompt = buildMarkdownSystemPrompt();
        String userMessage = "请将以下 Python 输出格式化为 Markdown：\n\n" + stdout;

        ChatClient chatClient = chatClientFactory.createChatClient("formatting");

        chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(delta -> {
                    if (!SseEmitterContext.isDisconnected()) {
                        accumulator.append(delta);
                        tagCallback.accept(StreamingTagEvent.delta(tagId, delta));
                    }
                })
                .blockLast();
    }

    /**
     * 同步调用 LLM 生成 Markdown（流式失败时的降级方案）
     */
    private String callLLMMarkdown(String stdout) throws Exception {
        String systemPrompt = buildMarkdownSystemPrompt();
        String userMessage = "请将以下 Python 输出格式化为 Markdown：\n\n" + stdout;

        ChatClient chatClient = chatClientFactory.createChatClient("formatting");
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    // ─── 内部工具方法 ─────────────────────────────────────────

    private String preprocessStdout(String stdout) {
        if (stdout.length() <= MAX_STDOUT_LENGTH) {
            return stdout;
        }
        int omitted = stdout.length() - TRUNCATE_KEEP * 2;
        return stdout.substring(0, TRUNCATE_KEEP)
                + "\n[... 省略 " + omitted + " 字符 ...]\n"
                + stdout.substring(stdout.length() - TRUNCATE_KEEP);
    }

    /**
     * 同步调用 LLM 生成 JSON（非流式兜底路径使用）
     */
    private String callLLMJson(String stdout) throws Exception {
        String systemPrompt = buildJsonSystemPrompt();
        String userMessage = "请将以下 Python 输出转换为结构化 JSON：\n\n" + stdout;

        ChatClient chatClient = chatClientFactory.createChatClient("formatting");
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return null;

        try {
            Map<String, Object> result = objectMapper.readValue(llmResponse, Map.class);
            if (result.containsKey("sections")) return result;
        } catch (Exception ignored) {}

        int start = llmResponse.indexOf('{');
        int end = llmResponse.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                String sub = llmResponse.substring(start, end + 1);
                Map<String, Object> result = objectMapper.readValue(sub, Map.class);
                if (result.containsKey("sections")) return result;
            } catch (Exception ignored) {}
        }

        return null;
    }

    // ─── Prompt 模板 ─────────────────────────────────────────

    private String buildMarkdownSystemPrompt() {
        return """
                你是一个数据分析结果排版助手。你的任务是将 Python print 输出转换为格式优美的 Markdown。

                排版规则：
                - 用 ### 标题划分不同的数据模块
                - 关键指标用 **粗体** 标注值，以列表形式展示，例如：
                  - **总销售额**: 1,234,567.89
                  - **订单数量**: 5,678
                - 表格数据转换为 Markdown 表格：
                  | 列1 | 列2 | 列3 |
                  |-----|-----|-----|
                  | 值1 | 值2 | 值3 |
                - 分析结论和说明文字直接以段落形式输出
                - 保持简洁，不要添加原始数据中没有的信息
                - 数值保留原始精度，不要四舍五入
                - 如果输出很简短（只有1-2行文本），直接输出即可，不需要强加标题
                - 如果表格中出现 "..." 省略号（pandas 截断），保留 "..." 标记
                - 直接输出 Markdown 格式文本，不要用代码块包裹整个输出

                示例输入：
                ```
                ======= 销售概览 =======
                总销售额: 1,234,567.89
                订单数量: 5,678
                平均客单价: 217.48

                ======= 月度趋势 =======
                月份    销售额    环比增长
                1月     98765    -
                2月     112345   13.7%
                3月     134567   19.8%

                总结：销售额呈稳步上升趋势，3月增速最快。
                ```

                示例输出：
                ### 销售概览

                - **总销售额**: 1,234,567.89
                - **订单数量**: 5,678
                - **平均客单价**: 217.48

                ### 月度趋势

                | 月份 | 销售额 | 环比增长 |
                |------|--------|----------|
                | 1月  | 98765  | -        |
                | 2月  | 112345 | 13.7%    |
                | 3月  | 134567 | 19.8%    |

                **总结：** 销售额呈稳步上升趋势，3月增速最快。
                """;
    }

    private String buildJsonSystemPrompt() {
        return """
                你是一个智能数据分析结果排版助手。你的任务是将 Python 输出转换为结构化、有洞察力的展示格式。

                **输出格式**：严格的 JSON，包含 sections 数组。每个 section 类型：

                1. **stats** - 关键指标卡片
                   ```json
                   {"type": "stats", "title": "关键指标", "items": [{"label": "总记录数", "value": "100"}]}
                   ```

                2. **table** - 表格数据
                   ```json
                   {"type": "table", "title": "详细数据", "columns": ["列1", "列2"], "rows": [["值1", "值2"]]}
                   ```

                3. **markdown** - 富文本分析（支持 Markdown）
                   ```json
                   {"type": "markdown", "title": "分析洞察", "content": "**发现**：销售额增长30%"}
                   ```

                **智能排版规则**：

                1. **识别数据结构**
                   - 检测 === 标题 === 分隔符，按标题分组
                   - "关键指标" → stats section
                   - "详细数据/详细分布/统计结果" → table section
                   - "分析洞察/结论/发现" → markdown section

                2. **过滤低价值信息**
                   - 跳过单纯的行数统计（如 "TOTAL_ROWS: 5"）
                   - 跳过 "Data Loaded: X rows" 等日志信息
                   - 跳过没有业务含义的技术输出

                3. **智能提取关键指标**
                   - 从 JSON 对象中提取 key-value 对作为 stats items
                   - 识别总计、平均值、最大最小值、占比等关键数值
                   - 优化标签名称（如 "总记录数" 而非 "total"）

                4. **优化表格展示**
                   - 保留所有列（包括占比、排名等衍生列）
                   - 数值列右对齐，文本列左对齐
                   - 保持原始精度，不要修改数值

                5. **增强可读性**
                   - title 使用中文业务术语
                   - 为 markdown section 添加格式（加粗、列表等）
                   - 合理分组，每个 section 聚焦一个主题

                **示例输入**：
                ```
                === 关键指标 ===
                {"总记录数": 5, "覆盖国家数": 3, "最多国家": "美国 (2条)"}

                === 详细数据 ===
                [{"国家":"美国","数量":2,"占比":"40%","排名":1},{"国家":"加拿大","数量":2,"占比":"40%","排名":2}]

                === 分析洞察 ===
                {"content": "美国占比最高（40%），前2名合计占80%"}
                ```

                **示例输出**：
                ```json
                {
                  "sections": [
                    {
                      "type": "stats",
                      "title": "关键指标",
                      "items": [
                        {"label": "总记录数", "value": "5"},
                        {"label": "覆盖国家数", "value": "3"},
                        {"label": "最多国家", "value": "美国 (2条)"}
                      ]
                    },
                    {
                      "type": "table",
                      "title": "详细数据",
                      "columns": ["国家", "数量", "占比", "排名"],
                      "rows": [["美国", "2", "40%", "1"], ["加拿大", "2", "40%", "2"]]
                    },
                    {
                      "type": "markdown",
                      "title": "分析洞察",
                      "content": "**关键发现**：美国占比最高（40%），前2名合计占80%"
                    }
                  ]
                }
                ```

                **重要**：
                - 直接输出 JSON，不要有任何前缀、后缀或代码块包裹
                - 如果输入是低价值信息（如单纯行数），返回空 sections：{"sections": []}
                - 保持数据完整性，不要丢失信息
                """;
    }
}
