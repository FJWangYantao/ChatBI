package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.dto.NERResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanningAgent {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final NERService nerService;
    private final ReadSchemaStructureService schemaService;
    private final FunctionCallback executeCodeFunction;
    private final FunctionCallback validateCodeFunction;
    private final FunctionCallback sandboxInfoFunction;
    private final FunctionCallback queryDatabaseFunction;

    public PlanningAgent(ChatClient.Builder chatClientBuilder,
                         ModelOptionsProvider modelOptions,
                         NERService nerService,
                         ReadSchemaStructureService schemaService,
                         @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction,
                         @Qualifier("validateCodeFunction") FunctionCallback validateCodeFunction,
                         @Qualifier("sandboxInfoFunction") FunctionCallback sandboxInfoFunction,
                         @Qualifier("queryDatabaseFunction") FunctionCallback queryDatabaseFunction) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.nerService = nerService;
        this.schemaService = schemaService;
        this.executeCodeFunction = executeCodeFunction;
        this.validateCodeFunction = validateCodeFunction;
        this.sandboxInfoFunction = sandboxInfoFunction;
        this.queryDatabaseFunction = queryDatabaseFunction;
    }

    /**
     * 使用 Function Calling 的增强规划
     * LLM 可以自主决定查询数据库、执行代码
     */
    public String planWithTools(String question) {
        log.info("[PlanningAgent] Planning with tools for: {}", question);

        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
        NERResponse nerResponse = nerService.extractEntities(question);
        String entitiesStr = nerResponse.getEntities().stream()
                .map(e -> e.getText() + "(" + e.getType() + ")")
                .collect(Collectors.joining(", "));

        String prompt = String.format("""
            你是一个数据分析专家，拥有以下工具：

            1. **query_database**: 查询数据库获取数据。传入自然语言描述需要什么数据。
               - 输入: {"data_description": "查询所有2024年的月度销售额"}
               - 返回: data_preview（预览）、data_ref_id（数据引用ID）、columns、row_count
            2. **execute_code**: 在安全沙盒中执行 Python 代码进行数据分析和可视化。
               - 输入: {"code": "...", "data_ref_id": "..."}
               - data_ref_id 使用 query_database 返回的 data_ref_id，系统会自动加载数据
            3. **validate_code**: 预检代码安全性（通常不需要调用）
            4. **sandbox_info**: 查询沙盒环境能力（通常不需要调用）

            **标准工作流程：**
            1. 先调用 query_database 获取数据
            2. 查看返回的 data_preview 理解数据结构和列名
            3. 编写 Python 分析代码，调用 execute_code 并传入 data_ref_id（不要传 data_json）
            4. 根据执行结果生成分析总结

            **重要规则：**
            - 必须先用 query_database 获取真实数据，绝对不要自己编造或模拟数据
            - execute_code 只需传 data_ref_id，不要传 data_json（数据由服务端自动注入）
            - 如果 query_database 返回 success=false，向用户说明无法获取数据，不要继续调用 execute_code
            - 如果 execute_code 失败（success=false），根据 error_hint 修正代码并重试（最多 2 次）
            - Python 代码中数据已自动加载为 df (pandas DataFrame)，不需要手动解析 JSON
            - 绘图使用 matplotlib，设置中文字体: plt.rcParams['font.sans-serif'] = ['SimHei']
            - 保存图表: plt.savefig('output.png', dpi=100, bbox_inches='tight')
            - 打印关键统计结果到 stdout

            **沙盒限制：**
            - 仅允许导入：pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64
            - 禁止：exit, eval, exec, compile, open, __import__ 等
            - 执行超时：30 秒

            **回复要求：**
            - 用中文回复
            - 在最终回复中总结分析发现，突出关键数字和趋势
            - 如果生成了图表，说明图表展示了什么

            用户问题：%s
            识别到的实体：%s
            数据库结构：
            %s
            """, question, entitiesStr, schemaInfo);

        try {
            String response = chatClient.prompt()
                    .options(modelOptions.getOptions("planning"))
                    .user(prompt)
                    .functions(queryDatabaseFunction, executeCodeFunction, validateCodeFunction, sandboxInfoFunction)
                    .call()
                    .content();

            log.info("[PlanningAgent] Tool-enabled planning complete, response length: {}",
                    response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            log.error("[PlanningAgent] Function calling failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
