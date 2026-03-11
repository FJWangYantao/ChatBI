package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import com.chatbi.factory.DynamicChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class SuggestionAgent {

    private final DynamicChatClientFactory chatClientFactory;
    private final ModelOptionsProvider modelOptions;

    public SuggestionAgent(DynamicChatClientFactory chatClientFactory, ModelOptionsProvider modelOptions) {
        this.chatClientFactory = chatClientFactory;
        this.modelOptions = modelOptions;
    }

    /**
     * 异步生成推荐问题
     */
    @Async
    public CompletableFuture<List<String>> suggestAsync(String currentQuestion, String analysisResultSummary) {
        try {
            String prompt = String.format("""
                基于用户当前的问题和分析结果，推荐 3 个后续可能感兴趣的问题。

                当前问题：%s
                结果摘要：%s

                要求：
                1. 问题要具体、相关。
                2. 尝试引导用户进行更深入的分析（如归因、预测、对比）。
                3. 每行一个问题，不要标号。
                """, currentQuestion, analysisResultSummary);

            ChatClient chatClient = chatClientFactory.createChatClient("suggestion");
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null) return CompletableFuture.completedFuture(List.of());

            List<String> suggestions = List.of(response.split("\\n"));
            // 简单清理
            suggestions = suggestions.stream()
                    .map(s -> s.replaceAll("^[0-9\\.\\- ]+", "").trim())
                    .filter(s -> !s.isEmpty())
                    .limit(3)
                    .toList();

            return CompletableFuture.completedFuture(suggestions);

        } catch (Exception e) {
            log.warn("[SuggestionAgent] Failed to generate suggestions: {}", e.getMessage());
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
