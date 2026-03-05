package com.chatbi.service;

import com.chatbi.config.LLMConfigProvider;
import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.SubTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单个子任务执行器：调用 LLM 生成 Python 代码，然后调用 executeCodeFunction 执行。
 * 不做 function calling 循环，只做一次性代码生成 + 执行。
 */
@Slf4j
@Service
public class PythonCodeAgent {

    private final ModelOptionsProvider modelOptions;
    private final FunctionCallback executeCodeFunction;
    private final LLMConfigProvider configProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PythonCodeAgent(ModelOptionsProvider modelOptions,
                     LLMConfigProvider configProvider,
                     @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction) {
        this.modelOptions = modelOptions;
        this.configProvider = configProvider;
        this.executeCodeFunction = executeCodeFunction;
        this.objectMapper = new ObjectMapper();
        this.httpClient = buildHttpClient();
    }

    private static HttpClient buildHttpClient() {
        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))))
                    .build();
        }
        return HttpClient.newHttpClient();
    }

    /**
     * 执行单个子任务：LLM 生成代码 → executeCodeFunction 执行
     *
     * @param taskDescription 子任务描述
     * @param dataRefId       query_database 返回的 data_ref_id
     * @param columns         数据列名
     * @param dataPreview     数据预览（前几行）
     * @param holder          SSE 上下文持有者（跨线程共享）
     * @param taskIndex       子任务索引（用于前端进度展示）
     * @param title           子任务标题（用于前端进度展示）
     */
    public SubTaskResult execute(String taskDescription, String dataRefId,
                                  String[] columns, String dataPreview,
                                  SseEmitterContext.Holder holder,
                                  int taskIndex, String title) {
        return execute(taskDescription, dataRefId, columns, dataPreview, holder, taskIndex, title, null);
    }

    public SubTaskResult execute(String taskDescription, String dataRefId,
                                  String[] columns, String dataPreview,
                                  SseEmitterContext.Holder holder,
                                  int taskIndex, String title,
                                  java.util.concurrent.atomic.AtomicBoolean cancelled) {
        // 设置当前线程的 ThreadLocal（工具函数内部仍通过 ThreadLocal 读取）
        SseEmitterContext.setHolder(holder);
        try {
            log.info("[CodeAgent] 开始执行子任务: {}", taskDescription);
            long start = System.currentTimeMillis();

            // 1. 发送 generating 状态
            sendSubtaskProgress(holder, taskIndex, title, "generating", null);

            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务已被取消(生成前): {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消");
            }

            // 2. 调用 LLM 生成代码
            String code = generateCode(taskDescription, columns, dataPreview);
            if (code == null || code.isEmpty()) {
                long duration = System.currentTimeMillis() - start;
                sendSubtaskProgress(holder, taskIndex, title, "failed", duration);
                return SubTaskResult.failed(taskDescription, "LLM 未生成有效代码");
            }
            log.info("[CodeAgent] 代码生成完成, 长度={}, 耗时={}ms",
                    code.length(), System.currentTimeMillis() - start);

            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务已被取消(执行前): {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消");
            }

            // 3. 发送 executing 状态
            sendSubtaskProgress(holder, taskIndex, title, "executing", null);

            // 4. 调用 executeCodeFunction 执行
            String args = objectMapper.writeValueAsString(
                    Map.of("code", code, "data_ref_id", dataRefId));
            String result = executeCodeFunction.call(args);

            // 如果执行期间被取消，跳过结果（避免重复 SSE 事件）
            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务执行完成但已被取消，跳过结果: {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消(执行后)");
            }

            // 瘦身结果：去掉 images base64（已通过 SSE 发送），截断超长 stdout
            String slimResult = slimExecuteResult(result);

            long duration = System.currentTimeMillis() - start;
            log.info("[CodeAgent] 子任务执行完成: {}, 总耗时={}ms", taskDescription, duration);
            sendSubtaskProgress(holder, taskIndex, title, "completed", duration);
            return SubTaskResult.success(taskDescription, slimResult, null);
        } catch (Exception e) {
            log.error("[CodeAgent] 子任务执行失败: {}, error={}", taskDescription, e.getMessage(), e);
            sendSubtaskProgress(holder, taskIndex, title, "failed", null);
            return SubTaskResult.failed(taskDescription, e.getMessage());
        } finally {
            SseEmitterContext.clear();
        }
    }

    /**
     * 流式生成 Python 代码（供 PlanningAgent 的 generate_code 工具调用）
     *
     * @param taskDescription 任务描述
     * @param columns         数据列名
     * @param dataPreview     数据预览（前几行）
     * @param onCodeDelta     流式回调（每个 token）
     * @return 完整的 Python 代码
     */
    public String generateCodeStreaming(String taskDescription,
                                       String[] columns,
                                       String dataPreview,
                                       java.util.function.Consumer<String> onCodeDelta) {
        log.info("[PythonCodeAgent] 开始流式生成代码，任务: {}", taskDescription);

        String model = modelOptions.getOptions("code-agent").getModel();
        String prompt = buildCodeGenPrompt(taskDescription, columns, dataPreview);

        try {
            var body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("stream", true);

            var messages = objectMapper.createArrayNode();
            messages.add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", prompt));
            body.set("messages", messages);

            String url = configProvider.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configProvider.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            StringBuilder fullCode = new StringBuilder();
            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("[PythonCodeAgent] LLM API error {}: {}",
                        response.statusCode(), new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        if (data.isEmpty()) continue;

                        var json = objectMapper.readTree(data);
                        var delta = json.path("choices").path(0).path("delta");
                        String content = delta.path("content").asText("");

                        if (!content.isEmpty()) {
                            fullCode.append(content);
                            if (onCodeDelta != null) {
                                try {
                                    onCodeDelta.accept(content);
                                } catch (Exception e) {
                                    log.warn("[PythonCodeAgent] 流式回调失败: {}", e.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            String code = extractCodeBlock(fullCode.toString());
            log.info("[PythonCodeAgent] 代码生成完成，长度: {} 字符", code != null ? code.length() : 0);
            return code;

        } catch (Exception e) {
            log.error("[PythonCodeAgent] 代码生成失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 瘦身 execute_code 返回结果：去掉 images base64，截断超长 stdout
     * 这些数据已通过 SSE 实时发送到前端，不需要回传给 LLM
     */
    private String slimExecuteResult(String result) {
        try {
            var root = objectMapper.readTree(result);
            if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) return result;

            // 去掉 images base64
            if (obj.has("images")) {
                var images = obj.get("images");
                if (images.isArray() && !images.isEmpty()) {
                    obj.put("images", "[已发送到前端，共" + images.size() + "张图]");
                }
            }
            // 截断超长 stdout
            if (obj.has("stdout")) {
                String stdout = obj.get("stdout").asText();
                if (stdout.length() > 1000) {
                    obj.put("stdout", stdout.substring(0, 1000) + "...[已截断]");
                }
            }
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return result;
        }
    }

    /**
     * 发送子任务细粒度进度事件
     */
    private void sendSubtaskProgress(SseEmitterContext.Holder holder, int taskIndex,
                                      String title, String status, Long duration) {
        if (holder == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("taskIndex", taskIndex);
            event.put("title", title);
            event.put("status", status);
            event.put("duration", duration);
            holder.safeSend(SseEmitter.event()
                    .name("subtask_progress")
                    .data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("[CodeAgent] 发送 subtask_progress 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 调用 LLM 同步生成 Python 代码（不做流式，子任务场景追求速度）
     */
    private String generateCode(String taskDescription, String[] columns, String dataPreview) {
        String model = modelOptions.getOptions("code-agent").getModel();
        String prompt = buildCodeGenPrompt(taskDescription, columns, dataPreview);

        try {
            var body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("stream", false);

            var messages = objectMapper.createArrayNode();
            messages.add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", prompt));
            body.set("messages", messages);

            String url = configProvider.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configProvider.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("[CodeAgent] LLM API error {}: {}", response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }

            var root = objectMapper.readTree(response.body());
            String content = root.at("/choices/0/message/content").asText("");
            return extractCodeBlock(content);
        } catch (Exception e) {
            log.error("[CodeAgent] 代码生成失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 LLM 回复中提取 Python 代码块
     */
    private String extractCodeBlock(String content) {
        if (content == null || content.isEmpty()) return null;
        // 尝试提取 ```python ... ``` 代码块
        int start = content.indexOf("```python");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 尝试提取 ``` ... ```
        start = content.indexOf("```");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 没有代码块标记，直接返回
        return content.trim();
    }

    private String buildCodeGenPrompt(String taskDescription, String[] columns, String dataPreview) {
        return String.format("""
            你是一个 Python 数据分析代码生成器。根据以下任务描述，生成可直接执行的 Python 代码。

            **任务**: %s

            **数据列名**: %s

            **数据预览（前几行）**:
            %s

            **要求**:
            - 数据已自动加载为 df (pandas DataFrame)，直接使用即可
            - 仅使用允许的库：pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64
            - 绘图设置中文字体: plt.rcParams['font.sans-serif'] = ['SimHei']
            - 保存图表: plt.savefig('output.png', dpi=100, bbox_inches='tight')
            - 打印关键统计结果到 stdout
            - 表格输出使用 print(df.to_string(index=False))
            - 每个输出块之前用 === 标题 === 格式打印标题
            - 只输出 Python 代码，用 ```python ``` 包裹
            """,
                taskDescription,
                String.join(", ", columns),
                dataPreview != null ? dataPreview : "(无预览)");
    }
}
