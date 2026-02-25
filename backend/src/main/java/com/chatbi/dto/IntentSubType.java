package com.chatbi.dto;

/**
 * 意图子类型常量
 * 用于标识 DATA_QUERY 类型的具体查询子类型
 */
public class IntentSubType {
    // 聚合查询类
    public static final String AGGREGATION_SUM = "AGGREGATION_SUM";
    public static final String AGGREGATION_COUNT = "AGGREGATION_COUNT";
    public static final String AGGREGATION_AVG = "AGGREGATION_AVG";
    public static final String AGGREGATION_MAX_MIN = "AGGREGATION_MAX_MIN";
    
    // 明细查询类
    public static final String DETAIL_LIST = "DETAIL_LIST";
    public static final String DETAIL_SINGLE = "DETAIL_SINGLE";
    public static final String DETAIL_SEARCH = "DETAIL_SEARCH";
    
    // 分析查询类
    public static final String TREND_ANALYSIS = "TREND_ANALYSIS";
    public static final String COMPARISON_ANALYSIS = "COMPARISON_ANALYSIS";
    public static final String RANKING_ANALYSIS = "RANKING_ANALYSIS";
    public static final String DISTRIBUTION_ANALYSIS = "DISTRIBUTION_ANALYSIS";
    
    // 归因分析类
    public static final String ROOT_CAUSE_ANALYSIS = "ROOT_CAUSE_ANALYSIS";
    
    // 关联查询类
    public static final String JOIN_QUERY = "JOIN_QUERY";
    public static final String SUB_QUERY = "SUB_QUERY";
    
    // 元数据查询
    public static final String METADATA_QUERY = "METADATA_QUERY";
    
    // 未知查询
    public static final String UNKNOWN_QUERY = "UNKNOWN_QUERY";
    
    private IntentSubType() {
        // 私有构造函数，防止实例化
    }
}
