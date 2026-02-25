package com.chatbi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NER 实体信息
 */
public class Entity {
    
    @JsonProperty("text")
    private String text;
    
    @JsonProperty("type")
    private String type;  // TABLE, COLUMN, VALUE, TIME_RANGE, AGGREGATION, OPERATOR, JOIN_CONDITION
    
    @JsonProperty("start_pos")
    private int startPos;
    
    @JsonProperty("end_pos")
    private int endPos;
    
    @JsonProperty("normalized_value")
    private String normalizedValue;
    
    @JsonProperty("confidence")
    private double confidence;
    
    // 无参构造器
    public Entity() {
    }
    
    public Entity(String text, String type, int startPos, int endPos) {
        this.text = text;
        this.type = type;
        this.startPos = startPos;
        this.endPos = endPos;
        this.confidence = 1.0;
    }
    
    // Getters and Setters
    public String getText() {
        return text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public int getStartPos() {
        return startPos;
    }
    
    public void setStartPos(int startPos) {
        this.startPos = startPos;
    }
    
    public int getEndPos() {
        return endPos;
    }
    
    public void setEndPos(int endPos) {
        this.endPos = endPos;
    }
    
    public String getNormalizedValue() {
        return normalizedValue;
    }
    
    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }
    
    public double getConfidence() {
        return confidence;
    }
    
    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
