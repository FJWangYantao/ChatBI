package com.chatbi.service;

import com.chatbi.config.ModelOptionsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ClarificationAgent {

    private final ChatClient chatClient;
    private final ModelOptionsProvider modelOptions;

    public ClarificationAgent(ChatClient.Builder chatClientBuilder, ModelOptionsProvider modelOptions) {
        this.chatClient = chatClientBuilder.build();
        this.modelOptions = modelOptions;
    }

    /**
     * 判断是否需要澄清，如果需要则返回澄清问题
     */
    public List<String> clarify(String question) {
        String prompt = String.format("""
            你是一个数据分析助手。用户提出了一个数据分析请求，请判断该请求是否过于模糊，需要进一步澄清。
            
            用户问题：%s
            
            判断标准（满足以下任一条件即视为"清晰"，直接返回 NO）：
            1. 问题中包含明确的图表类型（如折线图、柱状图、饼图等）
            2. 问题中包含明确的指标（如销售额、订单数、利润等）
            3. 问题中包含明确的分析维度（如按月、按地区、按产品等）
            4. 问题中包含明确的时间范围（如2024年、最近3个月等）
            5. 问题是预测类请求（如"预测下个月"）
            
            只有当问题极度模糊（例如只说"分析一下"、"看看数据"这类完全没有指向性的问题），才生成 1-2 个追问。
            
            格式要求：
            - 清晰则只返回 NO
            - 极度模糊才每行一个追问问题
            """, question);

        try {
            String response = chatClient.prompt()
                    .options(modelOptions.getOptions("clarification"))
                    .user(prompt)
                    .call()
                    .content();
            log.debug("[ClarificationAgent] question='{}', response='{}'", question, response);

            if (response == null || response.trim().toUpperCase().startsWith("NO")) {
                return Collections.emptyList();
            }

            return response.lines()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (Exception e) {
            // 限速（429）或其他异常时，跳过澄清步骤，直接进入分析流程
            log.warn("[ClarificationAgent] 澄清步骤失败（可能是限速），跳过: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
