package com.chatbi.dto;

import com.chatbi.entity.DataSourceConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 增强上下文
 * 包含增强所需的所有上下文信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnhancementContext {
    /**
     * 意图类型
     */
    private IntentType intentType;

    /**
     * 意图子类型
     */
    private String subtype;

    /**
     * 对话历史
     */
    private List<MessageDTO> conversationHistory;

    /**
     * 数据库 Schema 信息
     */
    private String schemaInfo;

    /**
     * 当前数据源配置
     */
    private DataSourceConfig dataSource;

    /**
     * 额外元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 原始用户消息
     */
    private String originalMessage;

    /**
     * 对话 ID
     */
    private String conversationId;

    /**
     * NER 信息
     */
    private NERResponse nerInfo;

    /**
     * 添加元数据
     */
    public void addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    /**
     * 获取元数据
     */
    public Object getMetadata(String key) {
        if (this.metadata == null) {
            return null;
        }
        return this.metadata.get(key);
    }

    /**
     * 获取元数据（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        Object value = getMetadata(key);
        return value != null ? (T) value : defaultValue;
    }
}
