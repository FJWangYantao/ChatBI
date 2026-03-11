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

    /**
     * 执行单个子任务（支持多数据集）：LLM 生成代码 → executeCodeFunction 执行
     *
     * @param taskDescription 子任务描述
     * @param dataRefs        多个数据引用 Map，key 为变量名（如 "df", "df_step1"），value 为 data_ref_id
     * @param holder          SSE 上下文持有者（跨线程共享）
     * @param taskIndex       子任务索引（用于前端进度展示）
     * @param title           子任务标题（用于前端进度展示）
     */
    public SubTaskResult executeWithMultipleDatasets(String taskDescription,
                                                      Map<String, String> dataRefs,
                                                      SseEmitterContext.Holder holder,
                                                      int taskIndex, String title) {
        return executeWithMultipleDatasets(taskDescription, dataRefs, holder, taskIndex, title, null);
    }

    public SubTaskResult executeWithMultipleDatasets(String taskDescription,
                                                      Map<String, String> dataRefs,
                                                      SseEmitterContext.Holder holder,
                                                      int taskIndex, String title,
                                                      java.util.concurrent.atomic.AtomicBoolean cancelled) {
        // 设置当前线程的 ThreadLocal
        SseEmitterContext.setHolder(holder);
        try {
            log.info("[CodeAgent] 开始执行多数据集子任务: {}", taskDescription);
            log.info("[CodeAgent] 数据引用: {}", dataRefs);
            long start = System.currentTimeMillis();

            // 1. 发送 generating 状态
            sendSubtaskProgress(holder, taskIndex, title, "generating", null);

            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务已被取消(生成前): {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消");
            }

            // 2. 调用 LLM 生成代码（传入多数据集信息）
            String code = generateCodeForMultipleDatasets(taskDescription, dataRefs);
            if (code == null || code.isEmpty()) {
                long duration = System.currentTimeMillis() - start;
                sendSubtaskProgress(holder, taskIndex, title, "failed", duration);
                return SubTaskResult.failed(taskDescription, "代码生成失败");
            }

            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务已被取消(执行前): {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消");
            }

            // 3. 发送 executing 状态
            sendSubtaskProgress(holder, taskIndex, title, "executing", null);

            // 4. 调用 executeCodeFunction 执行代码（传入多数据集）
            Map<String, Object> executeArgs = new LinkedHashMap<>();
            executeArgs.put("code", code);
            executeArgs.put("data_refs", dataRefs);  // 传入多个数据引用

            String argsJson = objectMapper.writeValueAsString(executeArgs);
            String executeResult = executeCodeFunction.call(argsJson);

            if (cancelled != null && cancelled.get()) {
                log.info("[CodeAgent] 子任务已被取消(执行后): {}", taskDescription);
                return SubTaskResult.failed(taskDescription, "已取消");
            }

            // 5. 解析执行结果
            Map<String, Object> resultMap = objectMapper.readValue(executeResult, Map.class);
            boolean success = Boolean.TRUE.equals(resultMap.get("success"));
            String output = (String) resultMap.get("output");
            String error = (String) resultMap.get("error");

            long duration = System.currentTimeMillis() - start;

            if (success) {
                sendSubtaskProgress(holder, taskIndex, title, "completed", duration);
                log.info("[CodeAgent] 子任务执行成功: {}, 耗时: {}ms", taskDescription, duration);
                String executedCode = (String) resultMap.get("code");
                return SubTaskResult.success(taskDescription, output, executedCode);
            } else {
                sendSubtaskProgress(holder, taskIndex, title, "failed", duration);
                log.error("[CodeAgent] 子任务执行失败: {}, 错误: {}", taskDescription, error);
                return SubTaskResult.failed(taskDescription, error != null ? error : "执行失败");
            }

        } catch (Exception e) {
            log.error("[CodeAgent] 子任务执行异常: {}", taskDescription, e);
            sendSubtaskProgress(holder, taskIndex, title, "failed", null);
            return SubTaskResult.failed(taskDescription, "执行异常: " + e.getMessage());
        } finally {
            SseEmitterContext.clear();
        }
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
     * 为多数据集场景生成 Python 代码
     */
    private String generateCodeForMultipleDatasets(String taskDescription, Map<String, String> dataRefs) {
        String prompt = buildCodeGenPromptForMultipleDatasets(taskDescription, dataRefs);

        try {
            ChatClient chatClient = chatClientFactory.createChatClient("code-agent");
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return extractCodeBlock(response);
        } catch (Exception e) {
            log.error("[CodeAgent] 多数据集代码生成失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 LLM 回复中提取 Python 代码块
     */
    private String extractCodeBlock(String content) {
        if (content == null || content.isEmpty()) return null;

        // 记录 LLM 原始响应（用于诊断）
        log.info("[CodeAgent] LLM 原始响应长度: {}", content.length());
        if (content.length() < 500) {
            log.info("[CodeAgent] LLM 完整响应: {}", content);
        }

        // 尝试提取 ```python ... ``` 代码块
        int start = content.indexOf("```python");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                String code = content.substring(start, end).trim();
                log.info("[CodeAgent] 提取到 Python 代码，长度: {}", code.length());
                log.info("[CodeAgent] 代码内容:\n{}", code);
                return code;
            }
        }
        // 尝试提取 ``` ... ```
        start = content.indexOf("```");
        if (start >= 0) {
            start = content.indexOf("\n", start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                String code = content.substring(start, end).trim();
                log.info("[CodeAgent] 提取到代码块，长度: {}", code.length());
                log.info("[CodeAgent] 代码内容:\n{}", code);
                return code;
            }
        }
        // 没有代码块标记，直接返回
        log.warn("[CodeAgent] 未找到代码块标记，返回原始内容");
        return content.trim();
    }

    private String buildCodeGenPrompt(String taskDescription, String[] columns, String dataPreview) {
        return String.format("""
            你是一个 Python 数据分析代码生成器。根据任务描述，生成有洞察力的数据分析代码。

            **关键要求：代码必须输出 JSON 数组格式的数据，用于自动生成图表！**

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

            3. **输出格式（必须严格遵守）**:

               **必须输出 JSON 数组**，格式如下：
               ```python
               import json

               # 进行数据分析
               result_df = ...  # 你的分析代码

               # 必须输出 JSON 数组（用于自动生成图表）
               print(result_df.to_json(orient='records', force_ascii=False))
               ```

            4. **技术规范**:
               - 仅使用允许的库：pandas, numpy, json, datetime, collections, itertools, functools, re, math
               - 不要生成图表绘制代码
               - 数据已加载为 df (pandas DataFrame)
               - 只输出 Python 代码，用 ```python ``` 包裹
               - **最后一行必须是 print(result_df.to_json(orient='records', force_ascii=False))**

            **示例**（销售趋势分析）:
            ```python
            import pandas as pd

            # 按月份统计销售额
            df['月份'] = pd.to_datetime(df['orderDate']).dt.to_period('M').astype(str)
            monthly_sales = df.groupby('月份')['销售额'].sum().reset_index()
            monthly_sales.columns = ['月份', '销售额']

            # 必须输出 JSON 数组
            print(monthly_sales.to_json(orient='records', force_ascii=False))
            ```
            """,
                taskDescription,
                String.join(", ", columns),
                dataPreview != null ? dataPreview : "(无预览)");
    }

    private String buildCodeGenPromptForMultipleDatasets(String taskDescription, Map<String, String> dataRefs) {
        StringBuilder dataInfo = new StringBuilder();
        dataInfo.append("**可用的数据集**:\n");
        for (String varName : dataRefs.keySet()) {
            dataInfo.append(String.format("- %s: 已加载的 pandas DataFrame\n", varName));
        }

        return String.format("""
            你是一个 Python 数据分析代码生成器。根据任务描述，生成有洞察力的数据分析代码。

            **关键要求：代码必须输出 JSON 数组格式的数据，用于自动生成图表！**

            **任务**: %s

            %s

            **核心要求**:
            1. **多数据集关联分析**
               - 你可以使用多个 DataFrame 进行关联分析
               - 使用 pandas 的 merge、join、concat 等方法进行数据关联
               - 必须提供有业务价值的洞察（占比、排名、趋势、异常等）

            2. **根据数据类型选择分析方法**:
               - 分类/分布数据 → 计算各类别的数量、占比、排名、Top N
               - 数值数据 → 计算总计、平均值、最大最小值、标准差
               - 时间序列 → 计算增长率、环比、同比、趋势
               - 对比数据 → 计算差异、比率、相关性

            3. **输出格式（必须严格遵守）**:

               **必须输出 JSON 数组**，格式如下：
               ```python
               import pandas as pd
               import json

               # 进行数据分析（可以使用多个 DataFrame）
               result_df = ...  # 你的分析代码

               # 必须输出 JSON 数组（用于自动生成图表）
               print(result_df.to_json(orient='records', force_ascii=False))
               ```

            4. **技术规范**:
               - 仅使用允许的库：pandas, numpy, json, datetime, collections, itertools, functools, re, math
               - 不要生成图表绘制代码
               - 所有数据集已经加载为对应的变量名（如 df, df_step1, df_step2）
               - 只输出 Python 代码，用 ```python ``` 包裹
               - **最后一行必须是 print(result_df.to_json(orient='records', force_ascii=False))**

            **示例**（多表关联分析）:
            ```python
            import pandas as pd

            # 关联两个数据集
            merged_df = pd.merge(df_step1, df_step2, on='EmployeeID', how='inner')

            # 进行综合分析
            result = merged_df.groupby('员工姓名').agg({
                '销售额': 'sum',
                '异常订单数': 'sum'
            }).reset_index()

            # 必须输出 JSON 数组
            print(result.to_json(orient='records', force_ascii=False))
            ```
            """,
                taskDescription,
                dataInfo.toString());
    }
}
