package com.chatbi.service;

import com.chatbi.dto.MessageDTO;
import com.chatbi.factory.DynamicChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写服务
 * 追问场景下，将依赖上下文的短消息改写为自包含的完整查询，
 * 改写后的文本同时用于意图识别和 Prompt 增强。
 */
@Slf4j
@Service
public class QueryRewriteService {

    private final DynamicChatClientFactory chatClientFactory;

    public QueryRewriteService(DynamicChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    /**
     * 判断是否需要改写，如果需要则调用 LLM 改写
     *
     * @param currentMessage 当前用户消息
     * @param history        对话历史
     * @return 改写后的消息（无需改写时返回原文）
     */
    public String rewriteIfNeeded(String currentMessage, List<MessageDTO> history) {
        // 没有历史或只有当前消息，无需改写
        if (history == null || history.size() <= 1) {
            log.info("[QueryRewriteService] 无历史记录，无需改写");
            return currentMessage;
        }

        // 提取最近 3 轮对话（用户+助手配对）
        String conversationContext = buildConversationContext(history);
        if (conversationContext.isEmpty()) {
            log.info("[QueryRewriteService] 无有效历史对话，无需改写");
            return currentMessage;
        }

        try {
            ChatClient chatClient = chatClientFactory.createChatClient("query-rewrite");

            String prompt = String.format("""
                你是一个查询改写助手。根据对话历史，判断用户的当前消息是否依赖上下文。
                - 如果当前消息本身就是完整的、不依赖上下文的问题，直接返回原文
                - 如果当前消息是追问或省略了关键信息，将其改写为一个独立完整的查询

                对话历史：
                %s

                当前消息：%s

                请只输出改写后的查询，不要输出任何解释。""", conversationContext, currentMessage);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("[QueryRewriteService] LLM 返回空响应，使用原文");
                return currentMessage;
            }

            String rewritten = response.trim();

            // 判断是否实际改写了
            if (rewritten.equals(currentMessage)) {
                log.info("[QueryRewriteService] 无需改写: \"{}\"", currentMessage);
            } else {
                log.info("[QueryRewriteService] 改写: \"{}\" → \"{}\"", currentMessage, rewritten);
            }

            return rewritten;
        } catch (Exception e) {
            // 改写失败时返回原文，不阻塞主流程
            log.warn("[QueryRewriteService] 改写失败，使用原文。错误: {}", e.getMessage());
            return currentMessage;
        }
    }

    /**
     * 从历史中提取最近 3 轮对话，助手回复压缩到 100 字
     */
    private String buildConversationContext(List<MessageDTO> history) {
        StringBuilder sb = new StringBuilder();
        int rounds = 0;

        // 从后往前遍历，跳过最后一条（当前用户消息）
        for (int i = history.size() - 2; i >= 0 && rounds < 6; i--) {
            MessageDTO msg = history.get(i);
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;

            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            String content = msg.getContent();

            // 助手回复压缩到 100 字
            if ("assistant".equals(msg.getRole()) && content.length() > 100) {
                content = content.substring(0, 100) + "...";
            }

            sb.insert(0, role + "：" + content + "\n");
            rounds++;
        }

        return sb.toString().trim();
    }
}
