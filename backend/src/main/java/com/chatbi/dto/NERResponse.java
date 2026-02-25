package com.chatbi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * NER 响应
 */
public class NERResponse {
    
    @JsonProperty("original_text")
    private String originalText;
    
    @JsonProperty("entities")
    private List<Entity> entities;
    
    @JsonProperty("relations")
    private List<EntityRelation> relations;
    
    // 无参构造器
    public NERResponse() {
        this.entities = new ArrayList<>();
        this.relations = new ArrayList<>();
    }
    
    public NERResponse(String originalText, List<Entity> entities, List<EntityRelation> relations) {
        this.originalText = originalText;
        this.entities = entities;
        this.relations = relations;
    }
    
    // Getters and Setters
    public String getOriginalText() {
        return originalText;
    }
    
    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }
    
    public List<Entity> getEntities() {
        return entities;
    }
    
    public void setEntities(List<Entity> entities) {
        this.entities = entities;
    }
    
    public List<EntityRelation> getRelations() {
        return relations;
    }
    
    public void setRelations(List<EntityRelation> relations) {
        this.relations = relations;
    }
}
