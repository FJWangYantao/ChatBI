package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.context.SseEmitterContext;
import com.chatbi.dto.SubTaskResult;
import com.chatbi.factory.DynamicChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个子任务执行器：调用 LLM 生成 Python 代码，然后调用 executeCodeFunction 执行。
 * 不做 function calling 循环，只做一次性代码生成 + 执行。
 */
@Slf4j
@Service
public class CodeAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;
    private final FunctionCallback executeCodeFunction;
    private final ObjectMapper objectMapper;

    public CodeAgent(DynamicChatClientFactory chatClientFactory,
                     ModelOptionsProvider modelOptions,
                     @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
        this.executeCodeFunction = executeCodeFunction;
        this.objectMapper = new ObjectMapper();
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
        String prompt = buildCodeGenPrompt(taskDescription, columns, dataPreview);

        try {
            ChatClient chatClient = chatClientFactory.createChatClient("code-agent");
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return extractCodeBlock(response);
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
            你是一个 Python 数据分析代码生成器。根据任务描述，生成有洞察力的数据分析代码。

            **任务**: %s

            **数据列名**: %s

            **数据预览（前几行）**:
            %s

            **核心要求**:
            1. **深度分析，而非简单统计**
               - 禁止只输出行数、列数等基础信息
               - 必须提供有业务价值的洞察（占比、排名、趋势、异常等）

            2. **根据数据类型选择分析方法**:
               - 分类/分布数据 → 计算各类别的数量、占比、排名、Top N
               - 数值数据 → 计算总计、平均值、最大最小值、标准差
               - 时间序列 → 计算增长率、环比、同比、趋势
               - 对比数据 → 计算差异、比率、相关性

            3. **多层次输出结构**（按顺序输出）:

               a) 关键指标（用于卡片展示）
               print("=== 关键指标 ===")
               print(json.dumps({"总计": 100, "平均值": 25.5, "最大值": 50}, ensure_ascii=False))

               b) 详细数据（用于表格展示，必须包含百分比/排名等衍生指标）
               print("=== 详细数据 ===")
               # 添加百分比列
               df_result['占比'] = (df_result['数量'] / df_result['数量'].sum() * 100).round(2).astype(str) + '%%'
               print(df_result.to_json(orient='records', force_ascii=False))

               c) 分析洞察（用自然语言描述发现）
               print("=== 分析洞察 ===")
               insights = "发现1：美国占比最高达40%%；发现2：前两名合计占80%%"
               print(json.dumps({"content": insights}, ensure_ascii=False))

            4. **技术规范**:
               - 仅使用允许的库：pandas, numpy, json, datetime, collections, itertools, functools, re, math
               - 不要生成图表绘制代码
               - 数据已加载为 df (pandas DataFrame)
               - 只输出 Python 代码，用 ```python ``` 包裹

            **完整示例**（国家分布分析）:
            ```python
            import json

            # 1. 关键指标
            total = len(df)
            unique_countries = df['country'].nunique()
            top_country = df['country'].value_counts().index[0]
            top_count = df['country'].value_counts().values[0]

            print("=== 关键指标 ===")
            print(json.dumps({
                "总记录数": total,
                "覆盖国家数": unique_countries,
                "最多国家": f"{top_country} ({top_count}条)"
            }, ensure_ascii=False))

            # 2. 详细数据（带占比和排名）
            country_stats = df['country'].value_counts().reset_index()
            country_stats.columns = ['国家', '数量']
            country_stats['占比'] = (country_stats['数量'] / total * 100).round(2).astype(str) + '%%'
            country_stats['排名'] = range(1, len(country_stats) + 1)

            print("=== 详细数据 ===")
            print(country_stats.to_json(orient='records', force_ascii=False))

            # 3. 分析洞察
            top2_pct = (country_stats['数量'].head(2).sum() / total * 100).round(1)
            print("=== 分析洞察 ===")
            insights = f"{top_country}占比最高（{country_stats.iloc[0]['占比']}），前2名合计占{top2_pct}%%"
            print(json.dumps({"content": insights}, ensure_ascii=False))
            ```
            """,
                taskDescription,
                String.join(", ", columns),
                dataPreview != null ? dataPreview : "(无预览)");
    }
}
