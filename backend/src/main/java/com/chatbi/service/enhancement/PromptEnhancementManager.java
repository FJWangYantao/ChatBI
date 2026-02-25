package com.chatbi.service.enhancement;

import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Prompt 增强管理器
 * 负责协调各个增强器，根据意图类型选择合适的增强器进行增强
 */
@Slf4j
@Service
public class PromptEnhancementManager {

    @Value("${prompt-enhancement.enabled:true}")
    private boolean enabled;

    private final List<PromptEnhancer> enhancers;

    public PromptEnhancementManager(List<PromptEnhancer> enhancers) {
        this.enhancers = enhancers;
        log.info("初始化 PromptEnhancementManager，加载 {} 个增强器", enhancers.size());
        enhancers.forEach(e -> log.debug("  - {} (优先级: {})", e.getName(), e.getPriority()));
    }

    /**
     * 增强 Prompt
     *
     * @param originalPrompt 原始提示词
     * @param context 增强上下文
     * @return 增强后的提示词
     */
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        // 检查是否启用增强功能
        if (!enabled) {
            log.debug("Prompt enhancement is disabled, returning original prompt");
            return new EnhancedPrompt(originalPrompt, List.of(), "增强功能已禁用");
        }

        // 根据意图类型选择合适的增强器
        Optional<PromptEnhancer> selectedEnhancer = selectEnhancer(context.getIntentType());

        if (selectedEnhancer.isPresent()) {
            PromptEnhancer enhancer = selectedEnhancer.get();
            log.debug("选择增强器: {} 处理意图: {}", enhancer.getName(), context.getIntentType());

            try {
                EnhancedPrompt result = enhancer.enhance(originalPrompt, context);
                log.debug("增强完成: {} 项增强", result.getEnhancements().size());
                return result;
            } catch (Exception e) {
                log.error("增强器 {} 执行失败: {}", enhancer.getName(), e.getMessage(), e);
                // 返回原始 prompt，避免影响正常流程
                return new EnhancedPrompt(originalPrompt, List.of(), "增强失败: " + e.getMessage());
            }
        } else {
            log.warn("未找到支持意图 {} 的增强器", context.getIntentType());
            return new EnhancedPrompt(originalPrompt, List.of(), "未找到合适的增强器");
        }
    }

    /**
     * 根据意图类型选择增强器
     * 如果有多个增强器支持同一意图，选择优先级最高的
     */
    private Optional<PromptEnhancer> selectEnhancer(IntentType intentType) {
        return enhancers.stream()
                .filter(e -> e.supports(intentType))
                .min(Comparator.comparingInt(PromptEnhancer::getPriority));
    }

    /**
     * 检查增强功能是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置增强功能开关
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("Prompt enhancement enabled: {}", enabled);
    }

    /**
     * 获取所有已注册的增强器
     */
    public List<PromptEnhancer> getEnhancers() {
        return List.copyOf(enhancers);
    }
}
