package com.chatbi.config;

import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.MessageTag;
import com.chatbi.dto.StreamingTagEvent;
import com.chatbi.service.ChatStreamService;
import com.chatbi.service.FormattingAgent;
import com.chatbi.service.MCPSandboxService;
import com.chatbi.service.Text2SQLAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Slf4j
@Configuration
@EnableScheduling
public class SandboxToolsConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 数据条目包装类，包含数据和创建时间
     */
    public static class DataEntry {
        public final String data;
        public final long createdAt;

        public DataEntry(String data) {
            this.data = data;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * 服务端数据引用存储。query_database 将完整 JSON 存入此 Map，
     * 返回短 data_ref_id 给 LLM；execute_code 通过 data_ref_id 取回数据。
     * 避免 LLM 在输出中重复生成巨大的 JSON 字符串导致 token 溢出。
     */
    public static final ConcurrentHashMap<String, DataEntry> DATA_STORE = new ConcurrentHashMap<>();

    /**
     * 定时清理过期数据（每10分钟扫描，删除30分钟前的条目）
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600_000)
    public void cleanExpiredData() {
        long now = System.currentTimeMillis();
        long expireMs = 30 * 60 * 1000L; // 30分钟
        int removed = 0;
        var it = DATA_STORE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().createdAt > expireMs) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[DATA_STORE] 清理过期数据: 删除 {} 条，剩余 {} 条", removed, DATA_STORE.size());
        }
    }

    /**
     * 分页获取存储的数据
     */
    public static Map<String, Object> getPagedData(String refId, int offset, int limit) {
        DataEntry entry = DATA_STORE.get(refId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (entry == null) {
            result.put("success", false);
            result.put("error", "数据引用不存在或已过期");
            return result;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> allRows = mapper.readValue(entry.data,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            int totalRows = allRows.size();
            int safeOffset = Math.max(0, Math.min(offset, totalRows));
            int safeLimit = Math.max(1, Math.min(limit, 500));
            int end = Math.min(safeOffset + safeLimit, totalRows);
            List<Map<String, Object>> pageRows = allRows.subList(safeOffset, end);

            result.put("success", true);
            result.put("rows", pageRows);
            result.put("totalRows", totalRows);
            result.put("offset", safeOffset);
            result.put("limit", safeLimit);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "数据解析失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 安全地将 Map 中的值转为 String。
     * LLM 可能传入 LinkedHashMap（嵌套 JSON 对象）而非 String，直接强转会 ClassCastException。
     */
    private static String toStr(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return false;
    }

    @Bean("executeCodeFunction")
    public FunctionCallback executeCodeFunction(
            MCPSandboxService sandboxService,
            ApplicationContext applicationContext) {

        AtomicInteger executionCounter = new AtomicInteger(0);

        return FunctionCallback.builder()
                .description("在安全沙盒中执行 Python 数据分析代码。数据通过 data_ref_id 参数传入（由 query_database 返回），"
                        + "会自动加载为 pandas DataFrame（变量名 df）。支持 matplotlib 绘图，"
                        + "图表以 base64 图片返回。仅允许导入: pandas, numpy, matplotlib, "
                        + "seaborn, sklearn, scipy, json, re, math, datetime, collections, "
                        + "itertools, functools, io, base64。")
                .function("execute_code", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    String code = toStr(params.get("code"));
                    String dataJson = toStr(params.getOrDefault("data_json", null));
                    String dataRefId = toStr(params.getOrDefault("data_ref_id", null));
                    int timeout = params.containsKey("timeout")
                            ? ((Number) params.get("timeout")).intValue()
                            : 30;

                    // 优先使用 data_ref_id 从服务端取数据，避免 LLM 传递巨大 JSON
                    if ((dataJson == null || dataJson.isEmpty()) && dataRefId != null && !dataRefId.isEmpty()) {
                        DataEntry entry = DATA_STORE.get(dataRefId);
                        if (entry == null) {
                            log.warn("[SandboxTool] data_ref_id={} not found in DATA_STORE", dataRefId);
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("success", false);
                            err.put("error_hint", "data_ref_id 无效或已过期，请重新调用 query_database 获取数据");
                            return err;
                        }
                        dataJson = entry.data;
                        log.info("[SandboxTool] Resolved data_ref_id={}, data length={}", dataRefId, dataJson.length());
                    }

                    // 生成执行 ID
                    String executionId = "exec_" + executionCounter.incrementAndGet();
                    log.info("[SandboxTool] execute_code called, executionId={}, code length={}",
                            executionId, code != null ? code.length() : 0);

                    // 获取当前请求的 emitter
                    SseEmitter emitter = SseEmitterContext.getEmitter();

                    // 发送"执行中"事件
                    if (emitter != null) {
                        try {
                            ChatStreamService chatStreamService = applicationContext.getBean(ChatStreamService.class);
                            chatStreamService.emitCodeExecution(
                                    emitter, executionId, "executing", code, null, null, null, null);
                        } catch (Exception e) {
                            log.warn("[CodeExecution] 发送执行中事件失败: {}", e.getMessage());
                        }
                    }

                    // 执行代码
                    long startTime = System.currentTimeMillis();
                    Map<String, Object> result = sandboxService.executeCode(code, dataJson, timeout);
                    long executionTime = System.currentTimeMillis() - startTime;

                    // 发送"完成"事件 + image tags
                    if (emitter != null) {
                        try {
                            boolean success = toBool(result.getOrDefault("success", false));
                            String stdout = toStr(result.get("stdout"));
                            String stderr = toStr(result.get("stderr"));

                            ChatStreamService chatStreamService = applicationContext.getBean(ChatStreamService.class);
                            chatStreamService.emitCodeExecution(
                                    emitter, executionId,
                                    success ? "completed" : "failed",
                                    code, stdout, stderr, success, executionTime);

                            // 发送图片 tag
                            Object imagesObj = result.get("images");
                            if (success && imagesObj instanceof List) {
                                for (Object img : (List<?>) imagesObj) {
                                    String base64Img = img.toString();
                                    Map<String, Object> imageTag = new LinkedHashMap<>();
                                    imageTag.put("type", "image");
                                    imageTag.put("content", "data:image/png;base64," + base64Img);
                                    imageTag.put("title", "分析图表");
                                    imageTag.put("metadata", Map.of("source", "sandbox"));
                                    emitter.send(SseEmitter.event()
                                            .name("tag")
                                            .data(MAPPER.writeValueAsString(imageTag)));
                                    // 收集 tag 用于持久化
                                    SseEmitterContext.collectTag(new MessageTag(
                                            "image",
                                            "data:image/png;base64," + base64Img,
                                            "分析图表",
                                            Map.of("source", "sandbox")));
                                }
                            }

                            // 发送 analysis_result tag（使用 LLM 排版 agent 流式输出）
                            if (success && stdout != null && !stdout.isEmpty()) {
                                FormattingAgent formattingAgent = applicationContext.getBean(FormattingAgent.class);
                                java.util.function.Consumer<StreamingTagEvent> tagCallback = SseEmitterContext.getTagStreamCallback();

                                if (tagCallback != null) {
                                    // 流式模式：tag_start/delta/end 由 FormattingAgent 内部通过 callback 发送
                                    // emitTagEnd 内部自动调用 SseEmitterContext.collectTag() 完成持久化
                                    formattingAgent.formatAnalysisOutputStreaming(stdout, tagCallback);
                                } else {
                                    // 无流式回调时（兜底）：保留旧逻辑
                                    Map<String, Object> analysisOutput = formattingAgent.formatAnalysisOutput(stdout);
                                    Map<String, Object> analysisTag = new LinkedHashMap<>();
                                    analysisTag.put("type", "analysis_result");
                                    analysisTag.put("content", analysisOutput);
                                    analysisTag.put("title", "分析详情");
                                    emitter.send(SseEmitter.event()
                                            .name("tag")
                                            .data(MAPPER.writeValueAsString(analysisTag)));
                                    SseEmitterContext.collectTag(new MessageTag(
                                            "analysis_result",
                                            analysisOutput,
                                            "分析详情",
                                            null));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[CodeExecution] 发送完成事件失败: {}", e.getMessage());
                        }
                    }

                    // 优化返回格式，提供更清晰的错误提示供 LLM 理解
                    boolean success = toBool(result.getOrDefault("success", false));
                    if (!success) {
                        String stderr = toStr(result.get("stderr"));
                        String errorHint = buildErrorHint(stderr);
                        result.put("error_hint", errorHint);
                    }

                    return result;
                })
                .inputType(Map.class)
                .build();
    }

    @Bean("validateCodeFunction")
    public FunctionCallback validateCodeFunction(MCPSandboxService sandboxService) {
        return FunctionCallback.builder()
                .description("预检 Python 代码的安全性，不实际执行。返回代码是否通过安全检查以及具体的错误列表。"
                        + "在执行代码前可以先调用此工具检查。")
                .function("validate_code", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    String code = toStr(params.get("code"));
                    return sandboxService.validateCode(code);
                })
                .inputType(Map.class)
                .build();
    }

    @Bean("sandboxInfoFunction")
    public FunctionCallback sandboxInfoFunction(MCPSandboxService sandboxService) {
        return FunctionCallback.builder()
                .description("查询沙盒环境信息，包括可用的 Python 模块列表、内置函数白名单、超时限制等。"
                        + "在生成代码前可以调用此工具了解沙盒的能力边界。")
                .function("sandbox_info", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    return sandboxService.getSandboxInfo();
                })
                .inputType(Map.class)
                .build();
    }

    @Bean("queryDatabaseFunction")
    public FunctionCallback queryDatabaseFunction(ApplicationContext applicationContext) {
        ObjectMapper jsonMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        return FunctionCallback.builder()
                .description("查询数据库获取分析所需的数据。传入自然语言描述你需要什么数据，"
                        + "系统会自动生成 SQL 并执行查询。返回 JSON 格式的查询结果，"
                        + "包含 data_preview（前50行预览）、columns（列名）、row_count（总行数）、"
                        + "data_ref_id（数据引用ID，传给 execute_code 的 data_ref_id 参数即可，无需传递完整数据）。")
                .function("query_database", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    String dataDescription = toStr(params.get("data_description"));

                    log.info("[SandboxTool] query_database called, description={}", dataDescription);

                    // 延迟获取 Text2SQLAgent，避免循环依赖
                    Text2SQLAgent text2SQLAgent = applicationContext.getBean(Text2SQLAgent.class);

                    // 从 ThreadLocal 获取流式回调
                    java.util.function.Consumer<StreamingTagEvent> tagCallback = SseEmitterContext.getTagStreamCallback();
                    String tagId = "sql-" + UUID.randomUUID().toString().substring(0, 8);

                    // 发送 tag_start
                    if (tagCallback != null) {
                        tagCallback.accept(StreamingTagEvent.start(tagId, "sql", "SQL 查询"));
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    try {
                        // 调用流式版本，每个 SQL token 实时转发
                        List<Map<String, Object>> data = text2SQLAgent.fetchDataWithStreaming(
                                dataDescription,
                                delta -> {
                                    if (tagCallback != null) {
                                        tagCallback.accept(StreamingTagEvent.delta(tagId, delta));
                                    }
                                }
                        );

                        // 发送 tag_end（附带完整 SQL 用于持久化）
                        String finalSQL = text2SQLAgent.getLastGeneratedSQL();
                        if (tagCallback != null && finalSQL != null) {
                            tagCallback.accept(StreamingTagEvent.end(tagId, "sql", "SQL 查询", finalSQL));
                        }

                        if (data == null || data.isEmpty()) {
                            result.put("success", false);
                            result.put("error", "未查询到数据，可能是查询条件不匹配或数据库中无相关数据");
                            result.put("row_count", 0);
                            return result;
                        }

                        result.put("success", true);
                        result.put("row_count", data.size());
                        result.put("columns", data.get(0).keySet().toArray(new String[0]));

                        // 给 LLM 看的预览（最多 50 行）
                        int previewRows = Math.min(50, data.size());
                        result.put("data_preview", data.subList(0, previewRows));
                        if (data.size() > previewRows) {
                            result.put("preview_note",
                                    "仅展示前 " + previewRows + " 行，完整数据共 " + data.size() + " 行，已存入 data_json_full");
                        }

                        // 完整数据 JSON 存入服务端，返回引用 ID
                        String fullJson = jsonMapper.writeValueAsString(data);
                        // 大小保护：超过 5MB 则截断
                        if (fullJson.length() > 5 * 1024 * 1024) {
                            int reducedRows = data.size() / 2;
                            fullJson = jsonMapper.writeValueAsString(data.subList(0, reducedRows));
                            result.put("data_truncated", true);
                            result.put("truncated_rows", reducedRows);
                        }
                        String refId = "data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        DATA_STORE.put(refId, new DataEntry(fullJson));
                        result.put("data_ref_id", refId);
                        log.info("[SandboxTool] Stored data with ref_id={}, size={} bytes", refId, fullJson.length());

                    } catch (Exception e) {
                        log.error("[SandboxTool] query_database failed: {}", e.getMessage(), e);
                        result.put("success", false);
                        result.put("error", "数据库查询失败: " + e.getMessage());
                    }
                    return result;
                })
                .inputType(Map.class)
                .build();
    }

    /**
     * 根据错误信息构建更清晰的错误提示，帮助 LLM 理解如何修正代码
     */
    private static String buildErrorHint(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return "代码执行失败，但未返回具体错误信息。";
        }

        // 安全验证失败
        if (stderr.contains("Security Validation Failed")) {
            if (stderr.contains("forbidden")) {
                return "安全验证失败：代码中使用了被禁止的函数或模块。请检查错误信息，移除不安全的代码（如 exit、eval、exec、open 等），只使用允许的库（pandas, numpy, matplotlib, seaborn 等）。";
            }
            return "安全验证失败：" + stderr;
        }

        // 语法错误
        if (stderr.contains("SyntaxError")) {
            return "Python 语法错误：请检查代码的语法，确保缩进、括号、引号等正确。错误详情：" + stderr;
        }

        // 导入错误
        if (stderr.contains("ModuleNotFoundError") || stderr.contains("ImportError")) {
            return "模块导入错误：尝试导入了不可用的模块。沙盒仅支持：pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64。错误详情：" + stderr;
        }

        // 运行时错误
        if (stderr.contains("NameError")) {
            return "变量未定义错误：代码中使用了未定义的变量。请检查变量名是否正确。错误详情：" + stderr;
        }

        if (stderr.contains("KeyError")) {
            return "键错误：尝试访问不存在的字典键或 DataFrame 列。请检查数据结构。错误详情：" + stderr;
        }

        if (stderr.contains("TypeError")) {
            return "类型错误：操作使用了不兼容的数据类型。请检查数据类型转换。错误详情：" + stderr;
        }

        if (stderr.contains("ValueError")) {
            return "值错误：函数接收了不合法的参数值。请检查参数范围和格式。错误详情：" + stderr;
        }

        // 超时
        if (stderr.contains("timeout") || stderr.contains("TimeoutError")) {
            return "执行超时：代码运行时间超过限制（默认 30 秒）。请优化代码性能或减少数据量。";
        }

        // 通用错误
        return "代码执行失败。错误信息：" + stderr;
    }
}
