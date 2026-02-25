package com.chatbi.dto;

import lombok.Data;

/**
 * 财年规则配置
 */
@Data
public class FiscalYearRule {
    /**
     * 财年起始月份 (1-12)
     */
    private Integer startMonth;
    
    /**
     * 财年起始日期 (1-31)
     */
    private Integer startDay;
    
    /**
     * 规则描述
     */
    private String description;
}
