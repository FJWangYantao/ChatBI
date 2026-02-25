package com.chatbi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 意图识别响应 DTO
 */
public class IntentRecognitionResponse {
    /**
     * 原始文本
     */
    private String text;

    /**
     * 一级分类 (DATA_QUERY, GENERAL_CHAT, HYBRID, DATA_OPERATION)
     */
    private String category;

    /**
     * 一级分类中文描述
     */
    @JsonProperty("category_cn")
    private String categoryCn;

    /**
     * 一级分类置信度
     */
    @JsonProperty("category_confidence")
    private double categoryConfidence;

    /**
     * 二级分类
     */
    private String subtype;

    /**
     * 二级分类置信度
     */
    @JsonProperty("subtype_confidence")
    private double subtypeConfidence;

    /**
     * 二级分类中文描述
     */
    @JsonProperty("subtype_cn")
    private String subtypeCn;

    /**
     * 一级分类概率分布（可选）
     */
    @JsonProperty("category_probs")
    private Map<String, Double> categoryProbs;

    /**
     * 二级分类概率分布（可选）
     */
    @JsonProperty("subtype_probs")
    private Map<String, Double> subtypeProbs;

    public IntentRecognitionResponse() {
    }

    public IntentRecognitionResponse(String text, String category, String categoryCn,
                                     double categoryConfidence, String subtype,
                                     double subtypeConfidence) {
        this.text = text;
        this.category = category;
        this.categoryCn = categoryCn;
        this.categoryConfidence = categoryConfidence;
        this.subtype = subtype;
        this.subtypeConfidence = subtypeConfidence;
    }

    // Getters and Setters
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCategoryCn() {
        return categoryCn;
    }

    public void setCategoryCn(String categoryCn) {
        this.categoryCn = categoryCn;
    }

    public double getCategoryConfidence() {
        return categoryConfidence;
    }

    public void setCategoryConfidence(double categoryConfidence) {
        this.categoryConfidence = categoryConfidence;
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(String subtype) {
        this.subtype = subtype;
    }

    public double getSubtypeConfidence() {
        return subtypeConfidence;
    }

    public void setSubtypeConfidence(double subtypeConfidence) {
        this.subtypeConfidence = subtypeConfidence;
    }

    public String getSubtypeCn() {
        return subtypeCn;
    }

    public void setSubtypeCn(String subtypeCn) {
        this.subtypeCn = subtypeCn;
    }

    public Map<String, Double> getCategoryProbs() {
        return categoryProbs;
    }

    public void setCategoryProbs(Map<String, Double> categoryProbs) {
        this.categoryProbs = categoryProbs;
    }

    public Map<String, Double> getSubtypeProbs() {
        return subtypeProbs;
    }

    public void setSubtypeProbs(Map<String, Double> subtypeProbs) {
        this.subtypeProbs = subtypeProbs;
    }

    @Override
    public String toString() {
        return "IntentRecognitionResponse{" +
                "text='" + text + '\'' +
                ", category='" + category + '\'' +
                ", categoryCn='" + categoryCn + '\'' +
                ", categoryConfidence=" + categoryConfidence +
                ", subtype='" + subtype + '\'' +
                ", subtypeConfidence=" + subtypeConfidence +
                '}';
    }
}
