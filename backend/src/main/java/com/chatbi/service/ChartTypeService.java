package com.chatbi.service;

import com.chatbi.dto.MessageTag;
import com.chatbi.dto.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 图表类型服务
 * 根据意图子类型和数据特征自动选择合适的图表类型
 */
@Slf4j
@Service
public class ChartTypeService {

    /**
     * 图表类型枚举
     */
    public enum ChartType {
        TABLE("table", "表格"),
        LINE("line", "折线图"),
        BAR("bar", "柱状图"),
        PIE("pie", "饼图"),
        AREA("area", "面积图"),
        SCATTER("scatter", "散点图");

        private final String code;
        private final String name;

        ChartType(String code, String name) {
            this.code = code;
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * 根据意图子类型获取推荐的图表类型
     */
    public ChartType getRecommendedChartType(String subtype) {
        if (subtype == null) {
            return ChartType.TABLE;
        }

        switch (subtype) {
            // 趋势分析 → 折线图
            case "TREND_ANALYSIS":
                return ChartType.LINE;

            // 分布分析 → 饼图
            case "DISTRIBUTION_ANALYSIS":
                return ChartType.PIE;

            // 排名分析 → 柱状图
            case "RANKING_ANALYSIS":
                return ChartType.BAR;

            // 对比分析 → 柱状图
            case "COMPARISON_ANALYSIS":
                return ChartType.BAR;

            // 聚合查询 → 柱状图
            case "AGGREGATION_SUM":
            case "AGGREGATION_AVG":
            case "AGGREGATION_MAX_MIN":
                return ChartType.BAR;

            // 计数 → 饼图或柱状图
            case "AGGREGATION_COUNT":
                return ChartType.PIE;

            // 明细查询 → 表格
            case "DETAIL_LIST":
            case "DETAIL_SINGLE":
            case "DETAIL_SEARCH":
            case "JOIN_QUERY":
            case "SUB_QUERY":
            case "METADATA_QUERY":
            default:
                return ChartType.TABLE;
        }
    }

    /**
     * 根据数据特征智能分析图表类型
     * 分析查询结果的列数、行数、数据类型等特征
     */
    public ChartType analyzeChartType(QueryResult queryResult, String subtype) {
        if (queryResult == null || queryResult.getSuccess() == null || !queryResult.getSuccess()) {
            return ChartType.TABLE;
        }

        List<Map<String, Object>> rows = queryResult.getRows();
        List<String> columns = queryResult.getColumns();

        // 无数据或列数过多 → 表格
        if (rows == null || rows.isEmpty() || columns.size() > 6) {
            return ChartType.TABLE;
        }

        // 行数过多 → 表格（不适合可视化）
        if (rows.size() > 100) {
            return ChartType.TABLE;
        }

        // 单行数据 → 表格
        if (rows.size() == 1) {
            return ChartType.TABLE;
        }

        // 先获取基于意图的推荐类型
        ChartType recommendedType = getRecommendedChartType(subtype);

        // 分析数据特征进行微调
        // 如果有多列数值数据，可能更适合柱状图
        long numericColumnCount = columns.stream()
                .filter(col -> isNumericColumn(rows, col))
                .count();

        // 如果推荐是饼图，但数值列多于2个，改用柱状图
        if (recommendedType == ChartType.PIE && numericColumnCount > 2) {
            return ChartType.BAR;
        }

        // 如果有日期/时间列，优先用折线图
        boolean hasTimeColumn = columns.stream()
                .anyMatch(col -> hasTimeData(rows, col));

        if (hasTimeColumn && recommendedType == ChartType.TABLE) {
            return ChartType.LINE;
        }

        return recommendedType;
    }

    /**
     * 检查列是否为数值类型
     */
    private boolean isNumericColumn(List<Map<String, Object>> rows, String columnName) {
        if (rows.isEmpty()) {
            return false;
        }

        // 检查前10行数据
        int checkLimit = Math.min(rows.size(), 10);
        int numericCount = 0;

        for (int i = 0; i < checkLimit; i++) {
            Object value = rows.get(i).get(columnName);
            if (value instanceof Number) {
                numericCount++;
            }
        }

        // 如果超过70%是数值，认为是数值列
        return numericCount > checkLimit * 0.7;
    }

    /**
     * 检查列是否包含时间/日期数据
     */
    private boolean hasTimeData(List<Map<String, Object>> rows, String columnName) {
        if (rows.isEmpty()) {
            return false;
        }

        // 检查列名是否包含时间相关关键词
        String lowerColName = columnName.toLowerCase();
        if (lowerColName.contains("date") || lowerColName.contains("time") ||
            lowerColName.contains("日期") || lowerColName.contains("时间") ||
            lowerColName.contains("month") || lowerColName.contains("year") ||
            lowerColName.contains("月") || lowerColName.contains("年")) {
            return true;
        }

        // 检查数据值
        Object firstValue = rows.get(0).get(columnName);
        return firstValue != null && (
            firstValue instanceof java.util.Date ||
            firstValue instanceof java.sql.Timestamp ||
            firstValue.toString().matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")
        );
    }

    /**
     * 创建图表标签
     */
    public MessageTag createChartTag(QueryResult queryResult, String subtype) {
        ChartType chartType = analyzeChartType(queryResult, subtype);

        MessageTag chartTag = new MessageTag();
        chartTag.setType("chart");
        chartTag.setContent(queryResult);
        chartTag.setTitle(chartType.getName());
        chartTag.setMetadata(Map.of(
            "chartType", chartType.getCode(),
            "chartName", chartType.getName(),
            "subtype", subtype != null ? subtype : "unknown"
        ));

        log.info("创建图表标签: subtype={}, chartType={}, rows={}",
                subtype, chartType.getCode(), queryResult.getTotalRows());

        return chartTag;
    }
}
