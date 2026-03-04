package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.config.SandboxToolsConfig;
import com.chatbi.dto.*;
import com.chatbi.service.enhancement.PromptEnhancementManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;
    private final ReadSchemaStructureService schemaService;
    private final DynamicJdbcTemplateProvider jdbcTemplateProvider;
    private final ConversationService conversationService;
    private final IntentRecognitionService intentRecognitionService;
    private final ChartTypeService chartTypeService;
    private final DiagnosticService diagnosticService;
    private final PromptEnhancementManager enhancementManager;
    private final NERService nerService;
    private final SQLCorrectionAgent sqlCorrectionAgent;
    private final ModelPerformanceMonitor performanceMonitor;
    private final Text2SQLAgent text2SQLAgent;
    // New Agents
    private final PlanningAgent planningAgent;
    private final ClarificationAgent clarificationAgent;
    private final ReportAgent reportAgent;
    private final SuggestionAgent suggestionAgent;

    // ThreadLocal 用于存储当前请求的意图信息
    private final ThreadLocal<IntentRecognitionResponse> currentIntentInfo = new ThreadLocal<>();

    // ThreadLocal 用于存储当前请求的增强信息
    private final ThreadLocal<EnhancedPrompt> currentEnhancedPrompt = new ThreadLocal<>();

    // ThreadLocal 用于存储当前请求的对话ID
    private final ThreadLocal<String> currentConversationId = new ThreadLocal<>();

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            ModelOptionsProvider modelOptions,
            ReadSchemaStructureService schemaService,
            DynamicJdbcTemplateProvider jdbcTemplateProvider,
            ConversationService conversationService,
            IntentRecognitionService intentRecognitionService,
            ChartTypeService chartTypeService,
            DiagnosticService diagnosticService,
            PromptEnhancementManager enhancementManager,
            NERService nerService,
            SQLCorrectionAgent sqlCorrectionAgent,
            ModelPerformanceMonitor performanceMonitor,
            Text2SQLAgent text2SQLAgent,
            // New Agents
            PlanningAgent planningAgent,
            ClarificationAgent clarificationAgent,
            ReportAgent reportAgent,
            SuggestionAgent suggestionAgent
    ) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
        this.schemaService = schemaService;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.conversationService = conversationService;
        this.intentRecognitionService = intentRecognitionService;
        this.chartTypeService = chartTypeService;
        this.diagnosticService = diagnosticService;
        this.enhancementManager = enhancementManager;
        this.nerService = nerService;
        this.sqlCorrectionAgent = sqlCorrectionAgent;
        this.performanceMonitor = performanceMonitor;
        this.text2SQLAgent = text2SQLAgent;
        // Assign New Agents
        this.planningAgent = planningAgent;
        this.clarificationAgent = clarificationAgent;
        this.reportAgent = reportAgent;
        this.suggestionAgent = suggestionAgent;
    }

    /**
     * 获取当前激活数据源的 JdbcTemplate
     */
    private JdbcTemplate getJdbcTemplate() {
        return jdbcTemplateProvider.getJdbcTemplate();
    }

    /**
     * 智能聊天 - 自动识别用户意图（带对话历史支持）
     */
    public ChatResponse smartChat(String message, String conversationId) {
        // 验证输入
        if (message == null || message.trim().isEmpty()) {
            log.error("smartChat 接收到空的消息参数");
            return new ChatResponse("错误：消息内容不能为空", null);
        }
        
        message = message.trim();
        currentConversationId.set(conversationId);
        long flowStartTime = System.currentTimeMillis();

        // 如果没有对话ID，创建新对话
        if (conversationId == null || conversationId.isEmpty()) {
            var newConv = conversationService.createConversation(extractTitle(message));
            conversationId = newConv.getConversationId();
            currentConversationId.set(conversationId);
            log.info("创建新对话: {}", conversationId);
        }

        // 保存用户消息
        conversationService.saveMessage(conversationId, "user", message, null);

        // 0. 特殊意图检查：生成报告（含 AI Insight）
        // 匹配 "生成报告"/"生成XX报告"/"总结对话"/"分析汇报" 等
        if (isReportRequest(message)) {
             ChatResponse response = reportAgent.generateInsightReport(conversationId);
             conversationService.saveMessage(conversationId, "assistant", response.getReply(), response.getTags());
             return response;
        }

        // 1. 意图识别（同时获取完整意图信息）
        IntentType intent = detectIntent(message);
        IntentRecognitionResponse intentInfo = currentIntentInfo.get();
        String subtype = intentInfo != null ? intentInfo.getSubtype() : null;
        String modeName = getModeName(intent);
        log.info("请求处理: intent={}, subtype={}, 路由模式={}, conversationId={}", intent, subtype, modeName, conversationId);

        // 2. Prompt 增强（在意图识别后、路由处理前）
        String promptToUse = message;
        try {
            EnhancementContext context = buildEnhancementContext(message, conversationId, intentInfo);
            EnhancedPrompt enhancedPrompt = enhancementManager.enhance(message, context);
            
            if (enhancedPrompt.isEnhanced()) {
                promptToUse = enhancedPrompt.getEnhancedPrompt();
                log.debug("Prompt 增强: {} 项增强", enhancedPrompt.getEnhancements().size());
                // 存储增强信息供后续使用
                currentEnhancedPrompt.set(enhancedPrompt);
            }
        } catch (Exception e) {
            log.warn("Prompt 增强失败，使用原始 prompt: {}", e.getMessage());
            promptToUse = message;
        }

        // 3. 根据意图路由到处理逻辑
        ChatResponse response;
        try {
            switch (intent) {
                case DATA_QUERY:
                    // 所有数据查询统一走多 Agent 协同分析流程
                    log.info("DATA_QUERY 路由到 Agent 流程");
                    response = handleDataAnalysisWithAgents(promptToUse);
                    break;

                case GENERAL_CHAT:
                    response = chat(message);
                    break;

                case HYBRID:
                    // 混合模式也走多 Agent 协同分析流程
                    log.info("HYBRID 路由到 Agent 流程");
                    response = handleDataAnalysisWithAgents(promptToUse);
                    break;

                case DATA_OPERATION:
                    response = handleDataOperation(promptToUse);
                    break;

                case DIAGNOSTIC_ANALYSIS:
                    response = diagnosticService.analyzeRootCause(promptToUse);
                    break;

                case DATA_ANALYSIS:
                    // 使用 PlanningAgent 进行深度分析规划
                    response = handleDataAnalysisWithAgents(promptToUse);
                    break;

                default:
                    log.warn("未知意图，使用默认对话模式: intent={}", intent);
                    response = chat(promptToUse);
                    break;
            }

            // 添加意图信息到响应
            if (intentInfo != null) {
                response.setIntentInfo(createIntentInfo(intentInfo));
            }

            // 添加增强信息到响应
            EnhancedPrompt enhancedPrompt = currentEnhancedPrompt.get();
            if (enhancedPrompt != null && enhancedPrompt.isEnhanced()) {
                response.setEnhancementInfo(enhancedPrompt.getEnhancements());
            }

            // 保存助手回复
            conversationService.saveMessage(conversationId, "assistant", response.getReply(), response.getTags());

            // 结构化日志: 请求流程汇总
            long flowDuration = System.currentTimeMillis() - flowStartTime;
            int tagCount = response.getTags() != null ? response.getTags().size() : 0;
            log.info("[STRUCT] event=request_complete intent={} subtype={} route={} tag_count={} duration={}ms conversationId={}",
                    intent, subtype, modeName, tagCount, flowDuration, conversationId);

            return response;

        } catch (Exception e) {
            long flowDuration = System.currentTimeMillis() - flowStartTime;
            log.error("[STRUCT] event=request_failed intent={} error={} duration={}ms conversationId={}",
                    intent, e.getMessage(), flowDuration, conversationId);
            log.error("处理消息失败: {}", e.getMessage(), e);
            ChatResponse errorResponse = new ChatResponse("抱歉，处理您的请求时出现错误。", null);
            conversationService.saveMessage(conversationId, "assistant", errorResponse.getReply(), null);
            return errorResponse;
        } finally {
            // 清理 ThreadLocal，防止内存泄漏
            currentIntentInfo.remove();
            currentEnhancedPrompt.remove();
            currentConversationId.remove();
        }
    }

    /**
     * 简单的 AI 对话
     */
    public ChatResponse chat(String message) {
        String response = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(message)
                .call()
                .content();

        return new ChatResponse(response, null);
    }

    /**
     * 直接执行 SQL (用于手动运行)
     */
    public ChatResponse executeSql(String sql) {
        return executeSQLAndBuildResponse("Manual Execution", sql);
    }

    /**
     * Text2SQL：将自然语言转换为 SQL 查询并执行（增强版，包含 Schema 信息）
     */
    public ChatResponse text2SQL(String question) {
        // 验证输入
        if (question == null || question.trim().isEmpty()) {
            log.error("text2SQL 接收到空的问题参数");
            return new ChatResponse("错误：问题内容不能为空", null);
        }
        
        question = question.trim();
        
        // 获取数据库 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        String systemPrompt = String.format("""
            你是一个数据库专家。根据用户的自然语言问题，生成相应的 SQL 查询语句。

            数据库结构如下：
            %s

            时间范围查询注意事项：
            - "2024.3 - 2025.1" 表示从2024年3月到2025年1月，使用 (Year=2024 AND Month>=3) OR (Year=2025 AND Month<=1) 或 BETWEEN 处理跨年
            - "前两个Q" 指当前年度的前两个季度（Q1、Q2）
            - "FY23/24" 或 "24财年" 表示财年（通常4月1日起），需转换为对应自然年月份范围
            - 跨年时间范围必须用 OR 或复合条件，不能写 Month BETWEEN 3 AND 1（逻辑错误）

            常见错误（请避免）：
            - WHERE 中 AND/OR 混用未加括号导致优先级错误
            - 跨年月份写成 Month BETWEEN 3 AND 1
            - SS、上市 等业务术语未映射到 Listing 相关字段
            - 产品系列（如S3）与产品名称混淆

            注意：
            1. 只返回 SQL 语句，不要有任何解释
            2. 使用 MySQL 语法
            3. 表名和列名要严格按照上述结构使用
            4. 支持多表 JOIN、子查询、聚合函数等复杂查询
            5. 利用表之间的外键关系进行正确的关联
            6. 如果涉及日期，使用标准的 MySQL 日期函数
            7. 列别名使用中文，提升可读性
            8. 如果需要查询多个不相关的表（例如“显示表A和表B的数据”），请生成多条 SQL 语句，并使用 "###SQL_SEPARATOR###" 分隔
            9. WHERE 中 AND/OR 混用时必须用括号明确优先级
            """, schemaInfo);

        String sql = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(question)
                .system(systemPrompt)
                .call()
                .content();

        // 检查 SQL 是否为 null 或空
        if (sql == null || sql.trim().isEmpty()) {
            log.error("AI 生成的 SQL 为空，无法执行查询");
            return new ChatResponse("抱歉，AI 未能生成有效的 SQL 查询语句。请尝试重新表述您的问题。", null);
        }

        // SQL 纠错 Agent 处理
        long correctionStart = System.currentTimeMillis();
        CorrectionResult correctionResult = sqlCorrectionAgent.correctSQL(sql, question, null);
        performanceMonitor.recordSQLCorrection(correctionResult.isCorrected());
        performanceMonitor.recordTiming("sql_correction_ms", System.currentTimeMillis() - correctionStart);
        String finalSQL = correctionResult.getCorrectedSQL();
        if (correctionResult.isCorrected()) {
            log.info("SQL 纠错完成: {} 处修正", correctionResult.getCorrections().size());
        }

        // 执行 SQL 并构建标签化响应
        ChatResponse response = executeSQLAndBuildResponse(question, finalSQL);
        performanceMonitor.recordSQLGeneration(true);
        return response;
    }

    /**
     * 意图识别 - 使用意图识别服务
     */
    private IntentType detectIntent(String message) {
        // 快速短路：明显的闲聊直接返回，不进入任何分析流程
        if (isObviousChat(message)) {
            log.info("快速短路: 识别为明显的闲聊");
            IntentRecognitionResponse intentInfo = new IntentRecognitionResponse();
            intentInfo.setCategory("GENERAL_CHAT");
            intentInfo.setCategoryCn("日常对话");
            intentInfo.setCategoryConfidence(1.0);
            intentInfo.setSubtype("GREETING");
            intentInfo.setSubtypeConfidence(1.0);
            currentIntentInfo.set(intentInfo);
            return IntentType.GENERAL_CHAT;
        }

        // 0. 优先规则匹配：归因分析 (强制覆盖，确保新功能生效)
        if (containsDiagnosticKeywords(message)) {
            log.info("通过关键词匹配识别为归因分析");
            
            IntentRecognitionResponse intentInfo = new IntentRecognitionResponse();
            intentInfo.setCategory("DIAGNOSTIC_ANALYSIS");
            intentInfo.setCategoryCn("归因分析");
            intentInfo.setCategoryConfidence(1.0);
            intentInfo.setSubtype("ROOT_CAUSE");
            intentInfo.setSubtypeConfidence(1.0);
            
            currentIntentInfo.set(intentInfo);
            
            return IntentType.DIAGNOSTIC_ANALYSIS;
        }

        // 0.1 优先规则匹配：深度数据分析（模型未训练此类别，必须用规则覆盖）
        if (containsDataAnalysisKeywords(message)) {
            log.info("通过关键词匹配识别为深度数据分析");
            
            IntentRecognitionResponse intentInfo = new IntentRecognitionResponse();
            intentInfo.setCategory("DATA_ANALYSIS");
            intentInfo.setCategoryCn("数据分析");
            intentInfo.setCategoryConfidence(1.0);
            intentInfo.setSubtype("UNKNOWN_QUERY");
            intentInfo.setSubtypeConfidence(1.0);
            
            currentIntentInfo.set(intentInfo);
            
            return IntentType.DATA_ANALYSIS;
        }

        try {
            // 优先使用意图识别服务
            var intentResponse = intentRecognitionService.recognize(message);
            String category = intentResponse.getCategory();
            String subtype = intentResponse.getSubtype();
            log.debug("意图识别服务结果: category={}, subtype={}, confidence={}",
                    category, subtype, intentResponse.getCategoryConfidence());

            // 置信度门槛：非闲聊结果置信度过低时，降级为闲聊
            double confidence = intentResponse.getCategoryConfidence();
            if (!"GENERAL_CHAT".equals(category) && confidence < 0.7) {
                log.info("意图置信度过低({}), 原分类={}, 降级为 GENERAL_CHAT", confidence, category);
                intentResponse.setCategory("GENERAL_CHAT");
                intentResponse.setCategoryCn("日常对话");
                category = "GENERAL_CHAT";
            }

            // 将完整意图信息存储到 ThreadLocal 中，供后续使用
            currentIntentInfo.set(intentResponse);

            // 将识别结果转换为 IntentType
            try {
                return IntentType.valueOf(category);
            } catch (IllegalArgumentException e) {
                log.warn("未知的意图类型: {}, 使用默认对话模式", category);
                return IntentType.GENERAL_CHAT;
            }

        } catch (Exception e) {
            log.warn("意图识别服务调用失败，使用降级策略: {}", e.getMessage());

            // 降级策略：使用原有的规则匹配
            IntentType intent = detectIntentFallback(message);
            
            // 为降级结果手动创建意图信息，避免置信度为 0
            IntentRecognitionResponse fallbackResponse = new IntentRecognitionResponse();
            fallbackResponse.setText(message);
            fallbackResponse.setCategory(intent.name());
            fallbackResponse.setCategoryCn(getCategoryCnName(intent));
            fallbackResponse.setCategoryConfidence(0.5); // 降级识别，给予中等置信度
            fallbackResponse.setSubtype("UNKNOWN_QUERY");
            fallbackResponse.setSubtypeConfidence(0.5);
            
            currentIntentInfo.set(fallbackResponse);
            
            return intent;
        }
    }

    /**
     * 意图识别降级策略（规则匹配）
     */
    private IntentType detectIntentFallback(String message) {
        // 第一层：快速规则匹配（性能优化）
        if (containsDiagnosticKeywords(message)) {
            return IntentType.DIAGNOSTIC_ANALYSIS;
        }
        if (containsDataAnalysisKeywords(message)) {
            return IntentType.DATA_ANALYSIS;
        }
        if (containsQueryKeywords(message)) {
            return IntentType.DATA_QUERY;
        }
        if (containsChatKeywords(message)) {
            return IntentType.GENERAL_CHAT;
        }

        // 第二层：AI 智能识别（处理复杂语义）
        String intentPrompt = String.format("""
            分析用户意图，判断用户想要：
            1. DATA_QUERY - 查询数据库数据（如：查询销售额、统计用户数、展示排名等）
            2. GENERAL_CHAT - 普通对话（如：问候、感谢、一般性咨询）
            3. HYBRID - 需要数据查询和AI解释（如：分析趋势并解释原因）
            4. DATA_OPERATION - 数据操作（如：创建、更新、删除、导出等）
            5. DIAGNOSTIC_ANALYSIS - 归因分析（如：为什么下降、分析原因、下跌原因等）
            6. DATA_ANALYSIS - 深度数据分析（如：预测、相关性分析、拟合、复杂计算、高级绘图等）

            只返回分类结果（DATA_QUERY/GENERAL_CHAT/HYBRID/DATA_OPERATION/DIAGNOSTIC_ANALYSIS/DATA_ANALYSIS），不要其他内容。

            用户输入：%s
            """, message);

        String intentStr = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(intentPrompt)
                .call()
                .content()
                .trim()
                .toUpperCase();

        try {
            return IntentType.valueOf(intentStr);
        } catch (IllegalArgumentException e) {
            log.warn("无法识别意图: {}, 默认使用普通对话", intentStr);
            return IntentType.GENERAL_CHAT;
        }
    }

    /**
     * 检查是否包含深度数据分析关键词
     */
    private boolean containsDataAnalysisKeywords(String message) {
        String[] keywords = {
            // 绘图类
            "画图", "画一个", "画一张", "折线图", "柱状图", "散点图", "饼图",
            "热力图", "直方图", "箱线图", "绘图", "可视化", "图表",
            // 统计分析类
            "预测", "回归", "相关性", "相关系数", "拟合", "聚类",
            "分布分析", "方差", "标准差", "统计分析",
            // 高级分析类
            "机器学习", "趋势预测", "异常检测", "时间序列"
        };
        return Arrays.stream(keywords)
                .anyMatch(keyword -> message.contains(keyword));
    }

    /**
     * 检查是否包含归因分析关键词
     */
    private boolean containsDiagnosticKeywords(String message) {
        String[] keywords = {
            "为什么", "原因", "怎么回事", "分析一下", "下降了", "下跌了", "减少了"
        };
        return Arrays.stream(keywords)
                .anyMatch(keyword -> message.contains(keyword));
    }

    /**
     * 检查是否包含查询关键词
     */
    private boolean containsQueryKeywords(String message) {
        String[] queryKeywords = {
            "查询", "统计", "展示", "分析", "多少", "排名", "总计", "平均",
            "最大", "最小", "数量", "列表", "数据", "表", "记录", "个"
        };
        return Arrays.stream(queryKeywords)
                .anyMatch(keyword -> message.contains(keyword));
    }

    /**
     * 检查是否包含聊天关键词
     */
    private boolean containsChatKeywords(String message) {
        String[] chatKeywords = {
            "你好", "谢谢", "再见", "帮助", "是什么", "怎么做", "怎么样"
        };
        return Arrays.stream(chatKeywords)
                .anyMatch(keyword -> message.contains(keyword));
    }

    /**
     * 判断是否为明显的闲聊 - 用排除法而非枚举法
     * 消息短 + 不含任何业务关键词 = 闲聊
     */
    private boolean isObviousChat(String message) {
        // 条件1：消息很短（<=10字），且不含任何业务关键词
        if (message.length() <= 10 && !containsAnyBusinessKeyword(message)) {
            return true;
        }

        // 条件2：匹配明确的闲聊模式
        String[] chatPatterns = {
            "你好", "您好", "hello", "hi", "嗨", "hey",
            "谢谢", "感谢", "thanks", "thank you",
            "再见", "拜拜", "bye",
            "你是谁", "你叫什么", "你能做什么", "你会什么",
            "早上好", "下午好", "晚上好", "晚安", "早安",
            "好的", "明白了", "知道了", "收到", "了解",
            "帮助", "help", "你好呀", "在吗", "在不在"
        };

        String lower = message.toLowerCase().trim();
        for (String pattern : chatPatterns) {
            if (lower.equals(pattern) || lower.startsWith(pattern + "，")
                || lower.startsWith(pattern + "。") || lower.startsWith(pattern + "！")
                || lower.startsWith(pattern + "!") || lower.startsWith(pattern + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否包含业务/数据相关关键词
     */
    private boolean containsAnyBusinessKeyword(String message) {
        String[] businessKeywords = {
            "查询", "统计", "销售", "收入", "利润", "用户", "订单",
            "多少", "排名", "总计", "平均", "最大", "最小", "数量",
            "为什么", "下降", "增长", "趋势", "预测", "原因",
            "画图", "图表", "柱状图", "折线图", "饼图", "散点图",
            "分析", "对比", "比较", "环比", "同比", "占比",
            "导出", "删除", "修改", "更新", "创建", "添加",
            "报告", "汇报", "总结"
        };
        return Arrays.stream(businessKeywords)
                .anyMatch(message::contains);
    }

    /**
     * 混合模式处理：生成SQL + AI解释
     */
    private ChatResponse handleHybridQuery(String question) {
        // 1. 生成 SQL
        ChatResponse sqlResponse = text2SQL(question);

        // 2. 生成 AI 解释
        String explanation = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(String.format("问题：%s\n\n请用简洁的语言解释这个查询的目的。", question))
                .call()
                .content();

        // 3. 返回组合响应（保留 tags）
        String combinedReply = String.format("**AI分析：**\n%s\n\n**已执行查询并返回结果**", explanation);
        sqlResponse.setReply(combinedReply);
        return sqlResponse;
    }

    /**
     * 数据操作模式处理
     * 注意：当前版本仅返回提示信息，实际数据操作需要额外的安全验证和权限控制
     */
    private ChatResponse handleDataOperation(String question) {
        log.info("处理数据操作请求: {}", question);

        String response = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(String.format("""
                    用户请求进行数据操作：%s

                    请友好地告知用户：
                    1. 当前版本的数据操作功能正在开发中
                    2. 您可以继续使用查询功能
                    3. 如需数据操作，请联系系统管理员

                    请用简洁、友好的语气回复。
                    """, question))
                .call()
                .content();

        return new ChatResponse(response, null);
    }

    /**
     * 根据查询结果生成自然语言总结
     */
    private String generateResultSummary(String question, List<Map<String, Object>> rows) {
        // 1. 处理空数据情况
        if (rows.isEmpty()) {
            return "未查询到相关数据。";
        }

        // 2. 准备数据预览 (避免 Token 超限，仅取前 5 条)
        int previewCount = Math.min(rows.size(), 5);
        List<Map<String, Object>> previewData = rows.subList(0, previewCount);

        // 3. 构造 Prompt
        String prompt = String.format("""
            用户问题：%s
            总行数：%d
            数据预览（前 %d 行）：%s
            
            请根据上述数据，用一句话简要回答用户问题或总结数据结果。
            要求：
            1. 必须基于提供的数据回答，严禁编造。
            2. 语言自然、简洁（100字以内）。
            3. 如果是统计类数据（如总销售额），直接回答数值。
            4. 如果是列表类数据（如前十名），列举前 1-3 个关键项并概括。
            5. 不要提及 "SQL"、"查询"、"数据库" 等技术术语。
            """,
                question,
                rows.size(),
                previewCount,
                previewData.toString()
        );

        // 4. 调用 AI (增加异常处理，失败则降级)
        try {
            return chatClient.prompt()
                    .options(modelOptions.getOptions("chat"))
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("生成总结失败", e);
            return "查询成功，结果如下："; // 降级回复
        }
    }

    /**
     * 执行 SQL 并构建标签化响应
     * 支持多条 SQL 语句（使用 ###SQL_SEPARATOR### 分隔）
     */
    private ChatResponse executeSQLAndBuildResponse(String question, String sql) {
        String conversationId = currentConversationId.get();
        String sqlPreview = sql != null && sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
        log.info("执行 SQL: conversationId={}, sqlPreview={}", conversationId, sqlPreview);

        // 获取当前意图信息
        IntentRecognitionResponse intentInfo = currentIntentInfo.get();
        String subtype = intentInfo != null ? intentInfo.getSubtype() : null;

        List<MessageTag> tags = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        // 分割多条 SQL
        String[] sqls = sql.split("###SQL_SEPARATOR###");
        List<Map<String, Object>> allRowsForSummary = new ArrayList<>();
        boolean hasError = false;
        StringBuilder errorMsg = new StringBuilder();

        for (String singleSql : sqls) {
            if (singleSql.trim().isEmpty()) continue;

            try {
                // 清理 SQL 语句
                String cleanSql = singleSql.trim()
                        .replaceAll("^```sql\\s*", "")
                        .replaceAll("^```\\s*", "")
                        .replaceAll("\\s*```$", "")
                        .trim();

                // 执行查询
                List<Map<String, Object>> rows = getJdbcTemplate().queryForList(cleanSql);
                
                // 收集用于总结的数据（限制数量防止 Token 超限）
                if (allRowsForSummary.size() < 20) {
                    int needed = 20 - allRowsForSummary.size();
                    allRowsForSummary.addAll(rows.subList(0, Math.min(rows.size(), needed)));
                }

                // 构建查询结果
                QueryResult queryResult = new QueryResult();
                if (!rows.isEmpty()) {
                    queryResult.setColumns(new ArrayList<>(rows.get(0).keySet()));
                } else {
                    queryResult.setColumns(new ArrayList<>());
                }
                
                // 分页传输：rows > 50 时只推预览数据 + dataRefId
                int previewLimit = 50;
                if (rows.size() > previewLimit) {
                    queryResult.setRows(rows.subList(0, previewLimit));
                    // 全量数据序列化存入 DATA_STORE
                    try {
                        ObjectMapper jsonMapper = new ObjectMapper().registerModule(new JavaTimeModule());
                        String fullJson = jsonMapper.writeValueAsString(rows);
                        String refId = "data_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        SandboxToolsConfig.DATA_STORE.put(refId, new SandboxToolsConfig.DataEntry(fullJson));
                        queryResult.setDataRefId(refId);
                        log.info("大数据集分页: refId={}, totalRows={}, previewRows={}", refId, rows.size(), previewLimit);
                    } catch (Exception e) {
                        log.warn("序列化全量数据失败，降级为截断模式: {}", e.getMessage());
                        queryResult.setRows(rows.subList(0, Math.min(rows.size(), 500)));
                    }
                } else {
                    queryResult.setRows(rows);
                }
                
                queryResult.setTotalRows(rows.size());
                queryResult.setSuccess(true);
                queryResult.setExecutionTime(0L); // 多查询暂不统计单条耗时

                // 添加标签
                tags.add(new MessageTag("sql", cleanSql, "SQL 查询", null));
                tags.add(new MessageTag("table", queryResult, "查询结果 (" + rows.size() + " 行)", null));

                // 尝试生成图表
                MessageTag chartTag = chartTypeService.createChartTag(queryResult, subtype);
                if (chartTag != null) {
                    tags.add(chartTag);
                }

                performanceMonitor.recordSQLExecution(true);

            } catch (Exception e) {
                log.error("SQL 执行失败: {}", e.getMessage());
                hasError = true;
                errorMsg.append(e.getMessage()).append("; ");
                performanceMonitor.recordSQLExecution(false);

                tags.add(new MessageTag("sql", singleSql, "SQL 查询（执行失败）", null));
                
                QueryResult errorResult = new QueryResult();
                errorResult.setSuccess(false);
                errorResult.setError(e.getMessage());
                
                tags.add(new MessageTag("error", errorResult, "执行错误", null));
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        performanceMonitor.recordTiming("sql_execution_ms", totalTime);
        String convId = currentConversationId.get();
        int rowCount = tags.stream()
                .filter(t -> "table".equals(t.getType()))
                .map(MessageTag::getContent)
                .filter(d -> d instanceof QueryResult)
                .map(d -> ((QueryResult) d).getTotalRows() != null ? ((QueryResult) d).getTotalRows() : 0)
                .reduce(0, Integer::sum);
        log.info("[STRUCT] event=sql_execution conversationId={} duration={}ms sql_count={} row_count={} success={}",
                convId, totalTime, sqls.length, rowCount, !hasError);
        log.info("SQL 执行完成: conversationId={}, 耗时={}ms", convId, totalTime);

        // 生成总结
        String summary;
        if (hasError && tags.stream().noneMatch(t -> "table".equals(t.getType()))) {
            // 如果全部失败
            summary = "SQL 执行失败：" + errorMsg.toString();
        } else {
            // 如果部分成功或全部成功
            summary = generateResultSummary(question, allRowsForSummary);
            if (hasError) {
                summary += "\n\n(注意：部分查询执行失败)";
            }
        }

        return new ChatResponse(summary, tags);
    }

    /**
     * 构建标题生成的 Prompt
     */
    private String buildTitleGenerationPrompt(String message) {
        return String.format("""
            你是一个对话标题生成专家。请为以下用户消息生成一个简洁、准确的标题。

            要求：
            1. 标题长度：10-30个字符（中文）或 10-20个单词（英文）
            2. 语言：与用户消息语言保持一致
            3. 风格：简洁明了，突出主题
            4. 格式：只返回标题，不要标点符号、引号
            5. 不需要区分对话类型，统一生成简洁标题

            示例：
            - "查询上个月销售额最高的三个产品" → "销售额最高的三个产品查询"
            - "Show me the total revenue by region" → "Total Revenue by Region"
            - "分析用户购买行为" → "用户购买行为分析"

            用户消息：%s

            请生成标题：
            """, message);
    }

    /**
     * 清理 AI 返回的标题
     * 去除可能的引号、标点等
     */
    private String cleanTitleResponse(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }

        // 去除首尾空格
        String cleaned = title.trim();

        // 去除可能的引号
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
            (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        // 去除末尾的句号、逗号等标点
        cleaned = cleaned.replaceAll("[。.,，!！?？]$", "");

        // 验证长度
        if (cleaned.length() < 5 || cleaned.length() > 50) {
            log.warn("AI生成的标题长度不合适: {}", cleaned);
            return null;
        }

        return cleaned;
    }

    /**
     * 使用 AI 生成标题（带超时保护）
     * @param message 用户第一条消息
     * @return AI 生成的标题，失败返回 null
     */
    private String generateTitleWithAI(String message) {
        long startTime = System.currentTimeMillis();
        try {
            // 使用 CompletableFuture 实现超时控制
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String prompt = buildTitleGenerationPrompt(message);
                    String response = chatClient.prompt()
                            .options(modelOptions.getOptions("chat"))
                            .user(prompt)
                            .call()
                            .content();
                    return cleanTitleResponse(response);
                } catch (Exception e) {
                    log.error("AI标题生成调用失败: {}", e.getMessage());
                    return null;
                }
            });

            // 3秒超时
            String result = future.get(3, TimeUnit.SECONDS);

            if (result != null && !result.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("AI生成标题成功: {} (耗时: {}ms)", result, duration);
            }

            return result;

        } catch (TimeoutException e) {
            log.warn("AI标题生成超时（3秒），使用降级策略");
            return null;
        } catch (Exception e) {
            log.error("AI标题生成异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从第一条消息提取对话标题（AI增强版）
     * 优先使用AI生成，失败时降级到截取方式
     */
    private String extractTitle(String message) {
        if (message == null || message.isEmpty()) {
            return "新对话";
        }

        // 策略1: 如果消息很短（<= 10字符），直接使用
        if (message.length() <= 10) {
            return message;
        }

        // 策略2: 尝试使用 AI 生成标题（带超时）
        String aiTitle = generateTitleWithAI(message);
        if (aiTitle != null && !aiTitle.isEmpty()) {
            return aiTitle;
        }

        // 策略3: 降级到原有方式（截取前30字符）
        log.warn("AI生成标题失败，使用截取方式");
        return message.substring(0, Math.min(30, message.length())) +
               (message.length() > 30 ? "..." : "");
    }

    /**
     * 智能聊天 - 返回带 conversationId 的响应
     */
    public ChatResponseWithConversation smartChatWithConversation(String message, String conversationId) {
        ChatResponse response = smartChat(message, conversationId);
        
        ChatResponseWithConversation result = new ChatResponseWithConversation(
                response.getReply(),
                response.getTags(),
                conversationId
        );
        
        // 传递意图信息
        result.setIntentInfo(response.getIntentInfo());
        // 传递推荐后续问题
        result.setSuggestions(response.getSuggestions());
        
        return result;
    }

    /**
     * 创建意图信息对象
     */
    private ChatResponse.IntentInfo createIntentInfo(IntentRecognitionResponse intentResponse) {
        if (intentResponse == null) {
            return null;
        }

        ChatResponse.IntentInfo intentInfo = new ChatResponse.IntentInfo();
        intentInfo.setCategory(intentResponse.getCategory());
        intentInfo.setCategoryCn(intentResponse.getCategoryCn());
        intentInfo.setCategoryConfidence(intentResponse.getCategoryConfidence());
        intentInfo.setSubtype(intentResponse.getSubtype());
        intentInfo.setSubtypeConfidence(intentResponse.getSubtypeConfidence());
        
        // 添加子类型中文描述
        intentInfo.setSubtypeCn(getSubtypeCnName(intentResponse.getSubtype()));
        
        return intentInfo;
    }

    /**
     * 获取子类型的中文名称
     */
    private String getSubtypeCnName(String subtype) {
        if (subtype == null) {
            return "未知";
        }
        
        Map<String, String> subtypeNames = Map.ofEntries(
            Map.entry("AGGREGATION_SUM", "求和聚合"),
            Map.entry("AGGREGATION_COUNT", "计数聚合"),
            Map.entry("AGGREGATION_AVG", "平均值聚合"),
            Map.entry("AGGREGATION_MAX_MIN", "最大最小值"),
            Map.entry("DETAIL_LIST", "明细列表"),
            Map.entry("DETAIL_SINGLE", "单条明细"),
            Map.entry("DETAIL_SEARCH", "明细搜索"),
            Map.entry("TREND_ANALYSIS", "趋势分析"),
            Map.entry("COMPARISON_ANALYSIS", "对比分析"),
            Map.entry("RANKING_ANALYSIS", "排名分析"),
            Map.entry("DISTRIBUTION_ANALYSIS", "分布分析"),
            Map.entry("JOIN_QUERY", "关联查询"),
            Map.entry("SUB_QUERY", "子查询"),
            Map.entry("METADATA_QUERY", "元数据查询"),
            Map.entry("UNKNOWN_QUERY", "未知查询")
        );
        
        return subtypeNames.getOrDefault(subtype, subtype);
    }

    /**
     * 获取意图类别的中文名称
     */
    private String getCategoryCnName(IntentType intent) {
        switch (intent) {
            case DATA_QUERY: return "数据查询";
            case GENERAL_CHAT: return "普通对话";
            case HYBRID: return "混合查询";
            case DATA_OPERATION: return "数据操作";
            case DIAGNOSTIC_ANALYSIS: return "归因分析";
            default: return "未知意图";
        }
    }

    /**
     * 获取路由模式名称
     */
    private String getModeName(IntentType intent) {
        switch (intent) {
            case DATA_QUERY: return "数据查询";
            case GENERAL_CHAT: return "普通对话";
            case HYBRID: return "混合模式";
            case DATA_OPERATION: return "数据操作";
            case DIAGNOSTIC_ANALYSIS: return "归因分析";
            default: return "默认对话";
        }
    }

    /**
     * 构建 Prompt 增强上下文
     */
    private EnhancementContext buildEnhancementContext(String message, String conversationId,
                                                       IntentRecognitionResponse intentInfo) {
        EnhancementContext.EnhancementContextBuilder builder = EnhancementContext.builder()
                .originalMessage(message)
                .conversationId(conversationId);

        // 设置意图信息
        if (intentInfo != null) {
            try {
                builder.intentType(IntentType.valueOf(intentInfo.getCategory()));
            } catch (IllegalArgumentException e) {
                log.warn("未知的意图类型: {}", intentInfo.getCategory());
            }
            builder.subtype(intentInfo.getSubtype());
        }

        // 设置 Schema 信息
        try {
            String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
            builder.schemaInfo(schemaInfo);
        } catch (Exception e) {
            log.warn("获取 Schema 信息失败: {}", e.getMessage());
        }

        // 设置对话历史（限制最近5条）
        if (conversationId != null && !conversationId.isEmpty()) {
            try {
                List<MessageDTO> history = conversationService.getMessages(conversationId);
                if (history != null && !history.isEmpty()) {
                    // 只取最近5条历史消息
                    int maxHistory = Math.min(history.size(), 5);
                    builder.conversationHistory(history.subList(0, maxHistory));
                }
            } catch (Exception e) {
                log.warn("获取对话历史失败: {}", e.getMessage());
            }
        }
        
        return builder.build();
    }

    /**
     * 构建增强的 Text2SQL System Prompt，融合 NER 实体信息
     */
    private String buildEnhancedText2SQLPrompt(String schemaInfo, NERResponse nerResponse) {
        StringBuilder promptBuilder = new StringBuilder();
        
        promptBuilder.append("你是一个数据库专家。根据用户的自然语言问题，生成相应的 SQL 查询语句。\n\n");
        
        // 添加 Schema 信息
        promptBuilder.append("数据库结构如下：\n").append(schemaInfo).append("\n\n");
        
        // 如果有 NER 结果，添加实体映射信息
        if (nerResponse != null && nerResponse.getEntities() != null && !nerResponse.getEntities().isEmpty()) {
            promptBuilder.append("已识别的关键实体信息：\n");
            
            Map<String, List<Entity>> entitiesByType = new HashMap<>();
            for (Entity entity : nerResponse.getEntities()) {
                entitiesByType.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
            }
            
            // 按类型组织实体信息
            if (entitiesByType.containsKey("TABLE")) {
                promptBuilder.append("- 表名: ");
                entitiesByType.get("TABLE").forEach(e -> {
                    promptBuilder.append("'").append(e.getText()).append("'");
                    if (e.getNormalizedValue() != null) {
                        promptBuilder.append(" (映射到: ").append(e.getNormalizedValue()).append(")");
                    }
                    promptBuilder.append(", ");
                });
                promptBuilder.setLength(promptBuilder.length() - 2); // 移除最后的 ", "
                promptBuilder.append("\n");
            }
            
            if (entitiesByType.containsKey("COLUMN")) {
                promptBuilder.append("- 列名: ");
                entitiesByType.get("COLUMN").forEach(e -> {
                    promptBuilder.append("'").append(e.getText()).append("'");
                    if (e.getNormalizedValue() != null) {
                        promptBuilder.append(" (映射到: ").append(e.getNormalizedValue()).append(")");
                    }
                    promptBuilder.append(", ");
                });
                promptBuilder.setLength(promptBuilder.length() - 2);
                promptBuilder.append("\n");
            }
            
            if (entitiesByType.containsKey("VALUE")) {
                promptBuilder.append("- 值/条件: ");
                entitiesByType.get("VALUE").forEach(e -> 
                    promptBuilder.append("'").append(e.getText()).append("', "));
                promptBuilder.setLength(promptBuilder.length() - 2);
                promptBuilder.append("\n");
            }
            
            if (entitiesByType.containsKey("TIME_RANGE")) {
                promptBuilder.append("- 时间范围: ");
                entitiesByType.get("TIME_RANGE").forEach(e -> 
                    promptBuilder.append("'").append(e.getText()).append("', "));
                promptBuilder.setLength(promptBuilder.length() - 2);
                promptBuilder.append("\n");
            }
            
            if (entitiesByType.containsKey("AGGREGATION")) {
                promptBuilder.append("- 聚合函数: ");
                entitiesByType.get("AGGREGATION").forEach(e -> 
                    promptBuilder.append("'").append(e.getText()).append("', "));
                promptBuilder.setLength(promptBuilder.length() - 2);
                promptBuilder.append("\n");
            }
            
            promptBuilder.append("\n");
        }
        
        // 时间范围查询专用模板（场景特化）
        promptBuilder.append("""
            时间范围查询注意事项：
            - "2024.3 - 2025.1" 表示从2024年3月到2025年1月，使用 (Year=2024 AND Month>=3) OR (Year=2025 AND Month<=1) 或 BETWEEN 处理跨年
            - "前两个Q" 指当前年度的前两个季度（Q1、Q2）
            - "FY23/24" 或 "24财年" 表示财年（通常4月1日起），需转换为对应自然年月份范围
            - 跨年时间范围必须用 OR 或复合条件，不能写 Month BETWEEN 3 AND 1（逻辑错误）
            
            """);

        promptBuilder.append("""
            注意：
            1. 只返回 SQL 语句，不要有任何解释
            2. 使用 MySQL 语法
            3. 表名和列名要严格按照上述结构使用
            4. 优先使用已识别的实体映射信息进行精确匹配
            5. 支持多表 JOIN、子查询、聚合函数等复杂查询
            6. 利用表之间的外键关系进行正确的关联
            7. 如果涉及日期，使用标准的 MySQL 日期函数
            8. 列别名使用中文，提升可读性
            9. 如果需要查询多个不相关的表，请生成多条 SQL 语句，并使用 "###SQL_SEPARATOR###" 分隔
            10. 对于已识别的实体，请优先使用其映射后的标准名称
            11. WHERE 中 AND/OR 混用时必须用括号明确优先级，避免逻辑错误
            """);
            
        return promptBuilder.toString();
    }

    /**
     * 构建增强的用户问题，突出关键实体
     */
    private String buildEnhancedUserQuestion(String originalQuestion, NERResponse nerResponse) {
        if (nerResponse == null || nerResponse.getEntities() == null || nerResponse.getEntities().isEmpty()) {
            return originalQuestion;
        }
        
        StringBuilder enhancedBuilder = new StringBuilder();
        enhancedBuilder.append("用户问题: ").append(originalQuestion).append("\n\n");
        
        // 添加实体解析说明
        enhancedBuilder.append("关键实体解析:\n");
        for (Entity entity : nerResponse.getEntities()) {
            enhancedBuilder.append("- '").append(entity.getText()).append("' (类型: ").append(entity.getType());
            if (entity.getNormalizedValue() != null) {
                enhancedBuilder.append(", 映射: ").append(entity.getNormalizedValue());
            }
            enhancedBuilder.append(")\n");
        }
        
        return enhancedBuilder.toString();
    }

    /**
     * NER增强版Text2SQL方法（使用已有的NER结果，避免重复调用）
     */
    public ChatResponse text2SQLWithNER(String originalQuestion, NERResponse nerResponse) {
        log.info("使用已有NER结果进行Text2SQL，避免重复调用NER服务");
        
        // 1. 获取数据库 Schema
        String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();

        // 2. 构建增强的 System Prompt
        String systemPrompt = buildEnhancedText2SQLPrompt(schemaInfo, nerResponse);

        // 3. 构建增强的用户问题（使用原始问题，不是增强后的prompt）
        String enhancedQuestion = buildEnhancedUserQuestion(originalQuestion, nerResponse);

        String sql = chatClient.prompt()
                .options(modelOptions.getOptions("chat"))
                .user(enhancedQuestion)
                .system(systemPrompt)
                .call()
                .content();

        // 检查 SQL 是否为 null 或空
        if (sql == null || sql.trim().isEmpty()) {
            log.error("AI 生成的 SQL 为空，无法执行查询");
            return new ChatResponse("抱歉，AI 未能生成有效的 SQL 查询语句。请尝试重新表述您的问题。", null);
        }

        // SQL 纠错 Agent 处理
        long correctionStart = System.currentTimeMillis();
        List<Entity> entities = nerResponse != null ? nerResponse.getEntities() : null;
        CorrectionResult correctionResult = sqlCorrectionAgent.correctSQL(sql, originalQuestion, entities);
        performanceMonitor.recordSQLCorrection(correctionResult.isCorrected());
        performanceMonitor.recordTiming("sql_correction_ms", System.currentTimeMillis() - correctionStart);
        String finalSQL = correctionResult.getCorrectedSQL();
        if (correctionResult.isCorrected()) {
            log.info("SQL 纠错完成: {} 处修正", correctionResult.getCorrections().size());
        }

        // 执行 SQL 并构建标签化响应
        ChatResponse response = executeSQLAndBuildResponse(originalQuestion, finalSQL);
        performanceMonitor.recordSQLGeneration(true);
        return response;
    }

    /**
     * NER增强版Text2SQL方法（兼容旧版本，会进行NER调用）
     * @deprecated 建议使用 text2SQLWithNER(String, NERResponse) 避免重复NER调用
     */
    @Deprecated
    public ChatResponse text2SQLWithNER(String question) {
        log.warn("使用了已废弃的text2SQLWithNER方法，建议传入已有的NER结果以避免重复调用");
        
        // 1. 使用 NER 服务提取实体
        NERResponse nerResponse = null;
        try {
            nerResponse = nerService.extractEntities(question);
            log.info("NER 识别到 {} 个实体", nerResponse.getEntities().size());
        } catch (Exception e) {
            log.warn("NER 实体识别失败，继续使用原始问题: {}", e.getMessage());
        }

        return text2SQLWithNER(question, nerResponse);
    }

    /**
     * 从当前线程的增强信息中提取NER结果
     */
    private NERResponse extractNERResponseFromEnhancements() {
        EnhancedPrompt enhancedPrompt = currentEnhancedPrompt.get();
        if (enhancedPrompt == null || !enhancedPrompt.isEnhanced()) {
            return null;
        }

        // 查找NER_ENTITIES类型的增强项
        List<Enhancement> nerEnhancements = enhancedPrompt.getEnhancementsByType("NER_ENTITIES");
        for (Enhancement enhancement : nerEnhancements) {
            Object data = enhancement.getData();
            if (data instanceof NERResponse) {
                return (NERResponse) data;
            }
        }
        
        return null;
    }

    /**
     * 多 Agent 协同深度数据分析
     */
    private ChatResponse handleDataAnalysisWithAgents(String question) {
        log.info("开始数据分析: {}", question);

        // 1. 澄清 Agent: 检查问题是否模糊
        List<String> clarifications = clarificationAgent.clarify(question);
        if (!clarifications.isEmpty()) {
            StringBuilder reply = new StringBuilder("为了更准确地回答您，我需要确认以下信息：\n");
            for (String q : clarifications) {
                reply.append("- ").append(q).append("\n");
            }
            return new ChatResponse(reply.toString(), null);
        }

        // 2. Function Calling 流程
        String toolResult = planningAgent.planWithTools(question);
        if (toolResult == null || toolResult.isBlank()) {
            return new ChatResponse("分析未返回结果，请重新描述您的问题。", null);
        }

        log.info("[planWithTools] LLM 自主分析完成，结果长度: {}", toolResult.length());

        // 3. 生成建议
        List<MessageTag> tags = new ArrayList<>();
        List<String> suggestions = List.of();
        try {
            String summaryForSuggestion = toolResult.length() > 500
                    ? toolResult.substring(0, 500) : toolResult;
            suggestions = suggestionAgent.suggestAsync(question, summaryForSuggestion).join();
        } catch (Exception e) {
            log.warn("建议生成失败", e);
        }
        if (!suggestions.isEmpty()) {
            tags.add(new MessageTag("suggestions", suggestions, "推荐后续问题", null));
        }

        ChatResponse chatResponse = new ChatResponse(toolResult, tags.isEmpty() ? null : tags);
        chatResponse.setSuggestions(suggestions.isEmpty() ? null : suggestions);
        return chatResponse;
    }

    /**
     * 判断用户消息是否为报告生成请求
     * 匹配：生成报告、生成XX报告、总结对话、分析汇报 等
     */
    private static boolean isReportRequest(String message) {
        if (message == null) return false;
        if (message.contains("总结对话") || message.contains("分析汇报")) return true;
        // "生成...报告" 模式（中间最多 20 个字）
        return message.matches(".*生成.{0,20}报告.*");
    }

    /**
     * 非流式查数模式 - 包含完整流程（NER + MCP + Text2SQL）
     * 用于测试和评估
     */
    public ChatResponse queryModeNonStreaming(String question) {
        log.info("[QueryModeNonStreaming] 开始处理查数请求: {}", question);

        try {
            // 1. 获取 Schema
            String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
            log.info("[QueryModeNonStreaming] Schema 获取成功，长度: {}", schemaInfo.length());

            // 2. 使用 Text2SQLAgent 构建 Prompt（包含 MCP 增强）
            String systemPrompt = text2SQLAgent.buildSqlPromptWithMCP(question, schemaInfo);
            log.info("[QueryModeNonStreaming] Prompt 构建完成（含 MCP 增强），长度: {}", systemPrompt.length());

            // 3. 生成 SQL
            String sql = chatClient.prompt()
                    .options(modelOptions.getOptions("text2sql"))
                    .user(systemPrompt)
                    .call()
                    .content();

            log.info("[QueryModeNonStreaming] AI 生成 SQL 完成，长度: {}", sql != null ? sql.length() : 0);

            if (sql == null || sql.trim().isEmpty()) {
                log.warn("[QueryModeNonStreaming] 生成的 SQL 为空");
                return new ChatResponse("未能生成有效的 SQL", null);
            }

            // 4. SQL 纠错
            var correctionResult = sqlCorrectionAgent.correctSQL(sql, question, null);
            String finalSQL = correctionResult.getCorrectedSQL();
            log.info("[QueryModeNonStreaming] SQL 纠错完成");

            // 5. 清理 SQL（去除 markdown 代码块标记）
            finalSQL = finalSQL.trim()
                    .replaceAll("^```sql\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();

            // 6. 构建响应
            MessageTag sqlTag = new MessageTag();
            sqlTag.setType("sql_editable");
            sqlTag.setTitle("生成的 SQL");
            sqlTag.setContent(Map.of(
                    "sql", finalSQL,
                    "editable", true,
                    "query", question
            ));

            log.info("[QueryModeNonStreaming] 查数请求处理完成");
            return new ChatResponse("", List.of(sqlTag));

        } catch (Exception e) {
            log.error("[QueryModeNonStreaming] 处理失败", e);
            return new ChatResponse("查数模式处理失败: " + e.getMessage(), null);
        }
    }
}
