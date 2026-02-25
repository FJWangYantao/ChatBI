package com.chatbi.service.enhancement;

import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;

/**
 * Prompt 增强器接口
 * 定义了增强提示词的标准方法
 */
public interface PromptEnhancer {

    /**
     * 增强提示词
     *
     * @param originalPrompt 原始提示词
     * @param context 增强上下文（包含意图、历史、Schema等）
     * @return 增强后的提示词
     */
    EnhancedPrompt enhance(String originalPrompt, EnhancementContext context);

    /**
     * 判断是否支持该意图类型
     *
     * @param intentType 意图类型
     * @return 是否支持
     */
    boolean supports(IntentType intentType);

    /**
     * 获取增强器名称
     *
     * @return 增强器名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取增强器优先级（数字越小优先级越高）
     * 当多个增强器都支持同一意图时，使用优先级最高的
     *
     * @return 优先级
     */
    default int getPriority() {
        return 100;
    }
}
