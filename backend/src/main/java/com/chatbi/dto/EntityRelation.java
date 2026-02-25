package com.chatbi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 实体关系
 */
public class EntityRelation {
    
    @JsonProperty("source_text")
    private String sourceText;
    
    @JsonProperty("target_text")
    private String targetText;
    
    @JsonProperty("relation_type")
    private String relationType;  // table_column, column_value, column_operator, etc.
    
    // 无参构造器
    public EntityRelation() {
    }
    
    public EntityRelation(String sourceText, String targetText, String relationType) {
        this.sourceText = sourceText;
        this.targetText = targetText;
        this.relationType = relationType;
    }
    
    // Getters and Setters
    public String getSourceText() {
        return sourceText;
    }
    
    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }
    
    public String getTargetText() {
        return targetText;
    }
    
    public void setTargetText(String targetText) {
        this.targetText = targetText;
    }
    
    public String getRelationType() {
        return relationType;
    }
    
    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }
}
