package com.chatbi.service.enhancement;

import com.chatbi.dto.Enhancement;
import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 归因分析增强器
 * 针对 DIAGNOSTIC_ANALYSIS 意图进行增强
 */
@Slf4j
@Component
public class DiagnosticEnhancer implements PromptEnhancer {

    @Value("${prompt-enhancement.strategies.diagnostic.framework-enhancement:true}")
    private boolean frameworkEnhancementEnabled;

    @Value("${prompt-enhancement.strategies.diagnostic.comparison-enhancement:true}")
    private boolean comparisonEnhancementEnabled;

    @Override
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        List<Enhancement> enhancements = new ArrayList<>();
        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        // 1. 归因分析框架增强
        if (frameworkEnhancementEnabled) {
            String frameworkEnhancement = buildFrameworkEnhancement();
            enhancements.add(new Enhancement("FRAMEWORK", "", frameworkEnhancement, "添加归因分析框架"));
            enhancedPrompt.append("\n\n").append(frameworkEnhancement);
        }

        // 2. 数据对比增强
        if (comparisonEnhancementEnabled) {
            String comparisonEnhancement = buildComparisonEnhancement(originalPrompt);
            enhancements.add(new Enhancement("COMPARISON", "", comparisonEnhancement, "添加对比维度"));
            enhancedPrompt.append("\n\n").append(comparisonEnhancement);
        }

        // 3. 时间范围增强（归因分析通常需要对比）
        String timeEnhancement = buildTimeComparisonEnhancement(originalPrompt);
        if (timeEnhancement != null && !timeEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("TIME_COMPARISON", "", timeEnhancement, "添加时间对比"));
            enhancedPrompt.append("\n\n").append(timeEnhancement);
        }

        // 4. 维度拆解增强
        String dimensionEnhancement = buildDimensionEnhancement();
        if (dimensionEnhancement != null && !dimensionEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("DIMENSION", "", dimensionEnhancement, "添加维度拆解"));
            enhancedPrompt.append("\n\n").append(dimensionEnhancement);
        }

        EnhancedPrompt result = new EnhancedPrompt();
        result.setEnhancedPrompt(enhancedPrompt.toString());
        result.setEnhancements(enhancements);
        result.setExplanation("归因分析增强完成，共应用 " + enhancements.size() + " 项增强");

        log.info("DiagnosticEnhancer: 原始Prompt='{}', 增强项数={}", originalPrompt, enhancements.size());

        return result;
    }

    @Override
    public boolean supports(IntentType intentType) {
        return intentType == IntentType.DIAGNOSTIC_ANALYSIS;
    }

    @Override
    public int getPriority() {
        return 5; // 高优先级，专门处理归因分析
    }

    /**
     * 构建归因分析框架增强
     */
    private String buildFrameworkEnhancement() {
        return """
            【归因分析框架】
            请按照以下步骤进行归因分析：
            
            1. **现象描述**：明确发生了什么变化（如：销售额下降了 X%）
            2. **维度拆解**：按以下维度进行拆解分析
               - 时间维度：按日/周/月/季度分析趋势
               - 地理维度：按地区/城市/省份分析差异
               - 产品维度：按产品类别/品牌/SKU分析
               - 客户维度：按客户类型/渠道分析
            3. **对比分析**：与以下基准进行对比
               - 历史同期：与去年同期、上月同期对比
               - 预期目标：与预算、目标值对比
               - 同类对比：与同类产品、同类地区对比
            4. **原因假设**：基于数据分析提出可能的原因
               - 内部因素：产品、价格、促销、渠道等
               - 外部因素：市场、竞争、季节、政策等
            5. **验证建议**：建议如何验证假设
               - 数据验证：查询相关数据支持假设
               - 业务验证：与业务人员确认
            """;
    }

    /**
     * 构建数据对比增强
     */
    private String buildComparisonEnhancement(String message) {
        // 检测消息中的对比关键词
        if (message.contains("同比") || message.contains("去年")) {
            return """
                【对比维度】
                检测到需要同比分析，请与去年同期数据进行对比。
                对比公式：同比 = (本期 - 去年同期) / 去年同期 * 100%
                """;
        }
        
        if (message.contains("环比") || message.contains("上月")) {
            return """
                【对比维度】
                检测到需要环比分析，请与上月数据进行对比。
                对比公式：环比 = (本期 - 上期) / 上期 * 100%
                """;
        }
        
        if (message.contains("目标") || message.contains("预算")) {
            return """
                【对比维度】
                检测到需要目标对比，请与目标值或预算进行对比。
                完成率 = 实际值 / 目标值 * 100%
                """;
        }

        // 默认对比建议
        return """
            【对比维度】
            建议进行以下对比分析：
            - 同比分析：与去年同期对比
            - 环比分析：与上月对比
            - 目标对比：与预算或目标值对比
            """;
    }

    /**
     * 构建时间对比增强
     */
    private String buildTimeComparisonEnhancement(String message) {
        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        // 检测时间关键词
        if (message.contains("本月")) {
            LocalDate lastMonth = today.minusMonths(1);
            return String.format("""
                【时间对比】
                本月数据：%s 至 %s
                上月数据：%s 至 %s
                请对比这两个时间段的数据变化。
                """,
                today.withDayOfMonth(1).format(formatter),
                today.format(formatter),
                lastMonth.withDayOfMonth(1).format(formatter),
                lastMonth.format(formatter)
            );
        }
        
        if (message.contains("本周")) {
            LocalDate lastWeek = today.minusWeeks(1);
            return String.format("""
                【时间对比】
                本周数据：请查询本周数据
                上周数据：请查询上周数据
                请对比这两个时间段的数据变化。
                """);
        }
        
        return null;
    }

    /**
     * 构建维度拆解增强
     */
    private String buildDimensionEnhancement() {
        return """
            【维度拆解建议】
            请从以下维度进行拆解分析：
            
            1. **时间维度**
               - 按日期：查看每日变化趋势
               - 按周：查看周度波动
               - 按月：查看月度趋势
               - 按季度：查看季度变化
            
            2. **地理维度**
               - 按大区：华北、华东、华南等
               - 按省份：各省数据对比
               - 按城市：重点城市分析
            
            3. **产品维度**
               - 按产品类别：各类产品表现
               - 按品牌：各品牌对比
               - 按SKU：具体商品分析
            
            4. **客户维度**
               - 按客户类型：新客/老客
               - 按渠道：线上/线下
               - 按行业：各行业客户分析
            
            请根据数据情况选择最相关的维度进行拆解。
            """;
    }
}
