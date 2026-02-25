package com.chatbi.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱配置 DTO
 * 映射 business-entities.json 的完整结构
 */
@Data
public class KnowledgeGraphConfig {
    /**
     * 组织名称列表
     */
    private List<String> organizations;
    
    /**
     * 地区名称列表
     */
    private List<String> locations;
    
    /**
     * 表别名映射: 业务名称 -> 实际表名
     */
    private Map<String, String> tableAliases;
    
    /**
     * 列别名映射: 业务名称 -> 实际列名
     */
    private Map<String, String> columnAliases;
    
    /**
     * 列值域: 列名 -> 有效值列表
     */
    private Map<String, List<String>> columnDomainValues;
    
    /**
     * 产品系列: 系列名 -> 型号列表
     */
    private Map<String, List<String>> productSeries;
    
    /**
     * 业务术语: 术语/缩写 -> 完整含义
     */
    private Map<String, String> businessTerms;
    
    /**
     * 财年规则
     */
    private FiscalYearRule fiscalYearRule;
    
    /**
     * 财年映射: FY代码 -> 日期范围描述
     */
    private Map<String, String> fiscalYearMappings;
}
