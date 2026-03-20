package com.chatbi.service.enhancement;

import com.chatbi.dto.Enhancement;
import com.chatbi.dto.EnhancedPrompt;
import com.chatbi.dto.EnhancementContext;
import com.chatbi.dto.IntentType;
import com.chatbi.dto.MessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文增强器
 * 针对多轮对话场景，添加对话历史上下文
 */
@Slf4j
@Component
public class ContextEnhancer implements PromptEnhancer {

    @Value("${prompt-enhancement.strategies.data-query.history-context:true}")
    private boolean historyContextEnabled;

    @Value("${prompt-enhancement.strategies.general-chat.context-enhancement:true}")
    private boolean chatContextEnabled;

    @Value("${prompt-enhancement.max-history-messages:5}")
    private int maxHistoryMessages;

    @Value("${prompt-enhancement.max-assistant-content-length:200}")
    private int maxAssistantContentLength;

    @Override
    public EnhancedPrompt enhance(String originalPrompt, EnhancementContext context) {
        List<Enhancement> enhancements = new ArrayList<>();
        StringBuilder enhancedPrompt = new StringBuilder(originalPrompt);

        // 1. 对话历史上下文增强
        if (isContextEnhancementEnabled(context.getIntentType())) {
            String contextEnhancement = buildContextEnhancement(context);
            if (contextEnhancement != null && !contextEnhancement.isEmpty()) {
                enhancements.add(new Enhancement("CONTEXT", "", contextEnhancement, "添加对话历史上下文"));
                enhancedPrompt.insert(0, contextEnhancement + "\n\n");
            }
        }

        // 2. 实体记忆增强（从历史中提取关键实体）
        String entityEnhancement = extractAndEnhanceEntities(context);
        if (entityEnhancement != null && !entityEnhancement.isEmpty()) {
            enhancements.add(new Enhancement("ENTITY", "", entityEnhancement, "提取关键实体信息"));
            enhancedPrompt.append("\n\n").append(entityEnhancement);
        }

        EnhancedPrompt result = new EnhancedPrompt();
        result.setEnhancedPrompt(enhancedPrompt.toString());
        result.setEnhancements(enhancements);
        result.setExplanation("上下文增强完成，共应用 " + enhancements.size() + " 项增强");

        log.info("ContextEnhancer: 增强项数={}", enhancements.size());

        return result;
    }

    @Override
    public boolean supports(IntentType intentType) {
        // 支持所有意图类型，但根据配置决定是否启用
        return true;
    }

    @Override
    public int getPriority() {
        return 20; // 中等优先级，在 DataQueryEnhancer 之后
    }

    /**
     * 判断是否启用上下文增强
     */
    private boolean isContextEnhancementEnabled(IntentType intentType) {
        if (intentType == IntentType.DATA_QUERY) {
            return historyContextEnabled;
        } else if (intentType == IntentType.GENERAL_CHAT) {
            return chatContextEnabled;
        }
        return true; // 其他类型默认启用
    }

    /**
     * 构建上下文增强内容
     */
    private String buildContextEnhancement(EnhancementContext context) {
        List<MessageDTO> history = context.getConversationHistory();

        if (history == null || history.isEmpty()) {
            return null;
        }

        // 按时间排序，取最近的消息，并过滤掉当前消息
        List<MessageDTO> relevantHistory = history.stream()
                .filter(msg -> msg.getContent() != null && !msg.getContent().equals(context.getOriginalMessage()))
                .sorted(Comparator.comparing(
                        MessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        // 只保留最近 N 条
        if (relevantHistory.size() > maxHistoryMessages) {
            relevantHistory = relevantHistory.subList(
                    relevantHistory.size() - maxHistoryMessages, relevantHistory.size());
        }

        if (relevantHistory.isEmpty()) {
            return null;
        }

        log.info("使用历史消息: conversationId={}, 总数={}, 使用数={}",
                context.getConversationId(), history.size(), relevantHistory.size());

        // 构建上下文摘要
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("【对话历史上下文】\n");
        contextBuilder.append("以下是之前的对话内容，请参考这些信息来理解当前问题：\n\n");

        for (MessageDTO msg : relevantHistory) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            String content = msg.getContent();

            // 压缩助手回复，避免 SQL、JSON 等长内容撑爆上下文
            if ("assistant".equals(msg.getRole()) && content.length() > maxAssistantContentLength) {
                content = compressAssistantContent(content);
            }

            contextBuilder.append(String.format("%s: %s\n", role, content));
        }

        return contextBuilder.toString();
    }

    /**
     * 压缩助手回复内容
     * 去除 SQL、JSON、代码块等冗长部分，只保留关键摘要
     */
    private String compressAssistantContent(String content) {
        // 去除 Markdown 代码块（SQL、JSON 等）
        String compressed = content.replaceAll("```[\\s\\S]*?```", "[代码块已省略]");
        // 去除内联代码中的长内容
        compressed = compressed.replaceAll("`[^`]{50,}`", "[长代码已省略]");

        if (compressed.length() > maxAssistantContentLength) {
            compressed = compressed.substring(0, maxAssistantContentLength) + "...（已截断）";
        }
        return compressed;
    }

    /**
     * 提取并增强实体信息
     * 从对话历史中提取关键实体（如表名、字段名、数值等）
     */
    private String extractAndEnhanceEntities(EnhancementContext context) {
        List<MessageDTO> history = context.getConversationHistory();
        
        if (history == null || history.isEmpty()) {
            return null;
        }

        // 简化实现：从历史中提取可能的实体
        // 实际实现可以使用 NLP 或 AI 来提取实体
        List<String> entities = new ArrayList<>();
        
        for (MessageDTO msg : history) {
            String content = msg.getContent();
            
            // 提取可能的表名（大写单词）
            // 简化实现，实际应该更智能
            if (content.matches(".*\\b[A-Z]{2,}\\b.*")) {
                // 这里只是示例，实际需要更精确的实体提取
            }
        }

        if (entities.isEmpty()) {
            return null;
        }

        return String.format("""
            【关键实体信息】
            从对话历史中识别到的关键实体：%s
            请在处理当前问题时考虑这些实体。
            """, String.join(", ", entities));
    }
}
