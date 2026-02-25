package com.chatbi.service.enhancement;

import com.chatbi.dto.Enhancement;
import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 意图细化增强器
 * 根据意图子类型，添加更具体的查询指令
 */
@Slf4j
@Component
public class IntentRefinementEnhancer implements PromptEnhancer {

    @Value("${prompt-enhancement.strategies.data-query.aggregation-enhancement:true}")
    private boolean aggregationEnhancementEnabled;

    // 子类型到细化指令的映射
    private static final Map<String, String> SUBTYPE_INSTRUCTIONS = Map.ofEntries(
        // 聚合类
        Map.entry("AGGREGATION_SUM", "请使用 SUM() 函数进行求和聚合，返回总和值。"),
        Map.entry("AGGREGATION_COUNT", "请使用 COUNT() 函数进行计数，返回记录数量。"),
        Map.entry("AGGREGATION_AVG", "请使用 AVG() 函数计算平均值，返回平均数。"),
        Map.entry("AGGREGATION_MAX_MIN", "请使用 MAX() 和 MIN() 函数获取最大值和最小值。"),
        
        // 明细类
        Map.entry("DETAIL_LIST", "请返回明细列表，使用 ORDER BY 进行排序，限制返回前 100 条记录。"),
        Map.entry("DETAIL_SINGLE", "请返回单条明细记录，使用 LIMIT 1。"),
        Map.entry("DETAIL_SEARCH", "请使用 LIKE 或 = 进行精确或模糊搜索。"),
        
        // 分析类
        Map.entry("TREND_ANALYSIS", "请按时间维度（如日期、月份）分组，展示数据趋势变化。"),
        Map.entry("COMPARISON_ANALYSIS", "请进行对比分析，可以使用 CASE WHEN 或 UNION 来对比不同条件的数据。"),
        Map.entry("RANKING_ANALYSIS", "请使用 ORDER BY ... DESC 进行排名，使用 RANK() 或 ROW_NUMBER() 函数。"),
        Map.entry("DISTRIBUTION_ANALYSIS", "请按维度分组统计，使用 GROUP BY 和 COUNT() 展示数据分布。"),
        
        // 复杂查询
        Map.entry("JOIN_QUERY", "请使用 JOIN 关联相关表，确保关联条件正确。"),
        Map.entry("SUB_QUERY", "请使用子查询来实现复杂的查询逻辑。"),
        Map.entry("METADATA_QUERY", "请查询数据库元数据，如 INFORMATION_SCHEMA。")
    );

    @Override
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        List<Enhancement> enhancements = new ArrayList<>();
        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        // 1. 基于子类型的细化增强
        if (aggregationEnhancementEnabled && context.getSubtype() != null) {
            String refinement = buildSubtypeRefinement(context.getSubtype());
            if (refinement != null && !refinement.isEmpty()) {
                enhancements.add(new Enhancement("INTENT_REFINEMENT", "", refinement, "基于子类型的意图细化"));
                enhancedPrompt.append("\n\n").append(refinement);
            }
        }

        // 2. 分组维度增强
        String groupingEnhancement = detectAndEnhanceGrouping(originalPrompt, context.getSubtype());
        if (groupingEnhancement != null && !groupingEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("GROUPING", "", groupingEnhancement, "明确分组维度"));
            enhancedPrompt.append("\n\n").append(groupingEnhancement);
        }

        // 3. 排序增强
        String orderingEnhancement = detectAndEnhanceOrdering(originalPrompt, context.getSubtype());
        if (orderingEnhancement != null && !orderingEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("ORDERING", "", orderingEnhancement, "明确排序方式"));
            enhancedPrompt.append("\n\n").append(orderingEnhancement);
        }

        EnhancedPrompt result = new EnhancedPrompt();
        result.setEnhancedPrompt(enhancedPrompt.toString());
        result.setEnhancements(enhancements);
        result.setExplanation("意图细化增强完成，共应用 " + enhancements.size() + " 项增强");

        log.info("IntentRefinementEnhancer: 原始Prompt='{}', 子类型='{}', 增强项数={}", 
                originalPrompt, context.getSubtype(), enhancements.size());

        return result;
    }

    @Override
    public boolean supports(IntentType intentType) {
        // 主要支持数据查询，但也支持其他需要细化的意图
        return intentType == IntentType.DATA_QUERY || 
               intentType == IntentType.HYBRID ||
               intentType == IntentType.DIAGNOSTIC_ANALYSIS;
    }

    @Override
    public int getPriority() {
        return 30; // 较低优先级，在其他增强器之后
    }

    /**
     * 基于子类型构建细化指令
     */
    private String buildSubtypeRefinement(String subtype) {
        String instruction = SUBTYPE_INSTRUCTIONS.get(subtype);
        if (instruction != null) {
            return String.format("""
                【查询类型细化】
                %s
                """, instruction);
        }
        return null;
    }

    /**
     * 检测并增强分组维度
     */
    private String detectAndEnhanceGrouping(String message, String subtype) {
        // 如果子类型明确需要分组
        if (subtype != null && (subtype.contains("TREND") || 
                               subtype.contains("DISTRIBUTION") || 
                               subtype.contains("RANKING"))) {
            return """
                【分组维度】
                请根据查询内容选择合适的分组维度：
                - 时间维度：按日期、月份、季度、年份分组
                - 地理维度：按地区、城市、省份分组
                - 产品维度：按产品类别、品牌分组
                - 其他维度：根据业务需求选择
                """;
        }

        // 检测消息中的分组关键词
        String[] groupingKeywords = {
            "按.*分组", "分组统计", "每个.*的", "各个.*的", "分别"
        };

        for (String keyword : groupingKeywords) {
            if (message.matches(".*" + keyword + ".*")) {
                return """
                    【分组维度】
                    检测到需要分组查询，请使用 GROUP BY 子句进行分组。
                    """;
            }
        }

        return null;
    }

    /**
     * 检测并增强排序方式
     */
    private String detectAndEnhanceOrdering(String message, String subtype) {
        // 如果子类型明确需要排序
        if (subtype != null && (subtype.contains("RANKING") || 
                               subtype.contains("LIST"))) {
            return """
                【排序方式】
                请使用 ORDER BY 对结果进行排序：
                - 排名类：使用 DESC 降序排列
                - 列表类：根据业务需求选择 ASC 或 DESC
                """;
        }

        // 检测消息中的排序关键词
        String[] orderingKeywords = {
            "排名", "前.*名", "最高", "最低", "最多", "最少", "升序", "降序"
        };

        for (String keyword : orderingKeywords) {
            if (message.contains(keyword)) {
                String orderDirection = message.contains("最低") || message.contains("最少") ? "ASC" : "DESC";
                return String.format("""
                    【排序方式】
                    检测到需要排序，请使用 ORDER BY ... %s 进行排序。
                    """, orderDirection);
            }
        }

        return null;
    }
}
