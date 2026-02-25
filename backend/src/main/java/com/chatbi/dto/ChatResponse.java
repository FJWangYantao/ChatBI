package com.chatbi.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponse {
    /**
     * 主要回复内容
     */
    private String reply;

    /**
     * 标签化内容
     * 可包含：SQL 代码块、数据表格、图表等
     */
    private List<MessageTag> tags;

    /**
     * 意图识别信息（可选）
     */
    private IntentInfo intentInfo;

    /**
     * Prompt 增强信息（可选）
     */
    private List<Enhancement> enhancementInfo;

    /**
     * 推荐后续问题（可选，用于数据分析场景）
     */
    private List<String> suggestions;

    /**
     * 构造函数（兼容旧代码）
     */
    public ChatResponse(String reply, List<MessageTag> tags) {
        this.reply = reply;
        this.tags = tags;
        this.intentInfo = null;
    }

    /**
     * 意图信息内部类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IntentInfo {
        /**
         * 意图类别 (DATA_QUERY, GENERAL_CHAT, HYBRID, DATA_OPERATION)
         */
        private String category;

        /**
         * 意图类别中文描述
         */
        private String categoryCn;

        /**
         * 意图类别置信度
         */
        private double categoryConfidence;

        /**
         * 意图子类型
         */
        private String subtype;

        /**
         * 意图子类型置信度
         */
        private double subtypeConfidence;

        /**
         * 子类型中文描述
         */
        private String subtypeCn;
        
        /**
         * AI生成总结
         */
        private String aiSummary;
    }
}
