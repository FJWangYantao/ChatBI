package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 增强后的 Prompt
 * 包含增强后的完整提示词和增强项列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnhancedPrompt {
    /**
     * 增强后的完整提示词
     */
    private String enhancedPrompt;

    /**
     * 增强项列表
     */
    private List<Enhancement> enhancements = new ArrayList<>();

    /**
     * 增强说明（用于调试和日志）
     */
    private String explanation;

    /**
     * 是否进行了增强
     */
    public boolean isEnhanced() {
        return enhancements != null && !enhancements.isEmpty();
    }

    /**
     * 获取指定类型的增强项
     */
    public List<Enhancement> getEnhancementsByType(String type) {
        if (enhancements == null) {
            return Collections.emptyList();
        }
        return enhancements.stream()
                .filter(e -> type.equals(e.getType()))
                .toList();
    }
}
