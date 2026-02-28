package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.StreamingTagEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ChatStreamService chatStreamService;
    private final ModelOptionsProvider modelOptions;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String apiBaseUrl;

    public FormattingAgent(ChatStreamService chatStreamService, ModelOptionsProvider modelOptions) {
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

        OpenAiChatOptions opts = modelOptions.getOptions("formatting");
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", opts.getModel());
        requestBody.put("temperature", opts.getTemperature());
        requestBody.put("max_tokens", 4000);
        requestBody.put("stream", true);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));

        String json = objectMapper.writeValueAsString(requestBody);
        String url = apiBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            response.body().close();
            throw new RuntimeException("LLM 流式请求失败, status=" + response.statusCode());
        }

        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                // 客户端已断开，提前终止 LLM 流以释放资源
                if (SseEmitterContext.isDisconnected()) {
                    log.info("[FormattingAgent] 客户端已断开，提前终止流式输出（已接收 {} 字符）",
                            accumulator.length());
                    break;
                }
                String line = it.next();
                if (!line.startsWith("data: ") || line.equals("data: [DONE]")) continue;
                try {
                    String payload = line.substring(6);
                    JsonNode chunk = objectMapper.readTree(payload);
                    String delta = chunk.at("/choices/0/delta/content").asText("");
                    if (!delta.isEmpty()) {
                        accumulator.append(delta);
                        tagCallback.accept(StreamingTagEvent.delta(tagId, delta));
                    }
                } catch (Exception e) {
                    log.trace("[FormattingAgent] 解析 SSE 行失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 同步调用 LLM 生成 Markdown（流式失败时的降级方案）
     */
    private String callLLMMarkdown(String stdout) throws Exception {
        String systemPrompt = buildMarkdownSystemPrompt();
        String userMessage = "请将以下 Python 输出格式化为 Markdown：\n\n" + stdout;

        OpenAiChatOptions opts = modelOptions.getOptions("formatting");
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", opts.getModel());
        requestBody.put("temperature", opts.getTemperature());
        requestBody.put("max_tokens", 4000);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));

        String json = objectMapper.writeValueAsString(requestBody);
        String url = apiBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM 请求失败, status=" + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.at("/choices/0/message/content").asText("");
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

        OpenAiChatOptions opts = modelOptions.getOptions("formatting");
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", opts.getModel());
        requestBody.put("temperature", opts.getTemperature());
        requestBody.put("max_tokens", 4000);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        requestBody.put("response_format", Map.of("type", "json_object"));

        String json = objectMapper.writeValueAsString(requestBody);
        String url = apiBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM 请求失败, status=" + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.at("/choices/0/message/content").asText("");
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
                你是一个数据分析结果排版助手。你的任务是将 Python print 输出转换为结构化 JSON。

                输出必须是严格的 JSON 格式，包含一个 sections 数组。每个 section 有以下类型：

                1. **stats** - 关键指标/统计数据（适合用卡片展示）
                   ```json
                   {"type": "stats", "title": "标题", "items": [{"label": "指标名", "value": "指标值"}]}
                   ```

                2. **table** - 表格数据
                   ```json
                   {"type": "table", "title": "标题", "columns": ["列1", "列2"], "rows": [["值1", "值2"]]}
                   ```

                3. **markdown** - 富文本内容（分析结论、说明文字等，支持 Markdown 语法）
                   ```json
                   {"type": "markdown", "title": "标题", "content": "Markdown 文本"}
                   ```

                排版规则：
                - 识别出数据中的关键指标（如总数、平均值、增长率等），放入 stats section
                - 表格数据放入 table section
                - 分析结论、说明性文字放入 markdown section
                - title 字段可选，如果内容有明确的分组标题则填写
                - 合理分组，不要把所有内容塞进一个 section
                - 数值保留原始精度，不要四舍五入
                - 如果输出很简短（只有1-2行文本），直接用一个 markdown section 即可

                直接输出 JSON，不要有任何额外文字。
                严格只输出 JSON 对象，禁止任何前缀、后缀或 markdown 代码块包裹。
                """;
    }
}
