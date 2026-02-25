package com.chatbi.controller;

import com.chatbi.dto.ChatResponse;
import com.chatbi.dto.NERResponse;
import com.chatbi.service.ChatService;
import com.chatbi.service.NERService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NER 测试控制器
 * 用于测试 NER 模型与 Text2SQL 的整合效果
 */
@Slf4j
@RestController
@RequestMapping("/ner-test")
public class NERTestController {

    private final NERService nerService;
    private final ChatService chatService;

    public NERTestController(NERService nerService, ChatService chatService) {
        this.nerService = nerService;
        this.chatService = chatService;
    }

    /**
     * 测试 NER 实体识别
     */
    @PostMapping("/extract-entities")
    public Map<String, Object> extractEntities(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }

        Map<String, Object> response = new HashMap<>();
        try {
            NERResponse nerResponse = nerService.extractEntities(text);
            response.put("success", true);
            response.put("data", nerResponse);
            response.put("message", String.format("成功识别 %d 个实体", nerResponse.getEntities().size()));
        } catch (Exception e) {
            log.error("NER实体识别失败", e);
            response.put("success", false);
            response.put("message", "实体识别失败: " + e.getMessage());
        }

        return response;
    }

    /**
     * 对比测试：原版 Text2SQL vs NER增强版 Text2SQL
     * 当 LLM 调用失败时仍返回 NER 结果
     */
    @PostMapping("/compare-text2sql")
    public Map<String, Object> compareText2SQL(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("question", question);
        
        try {
            // 1. NER 实体识别（不依赖 LLM）
            NERResponse nerResponse = nerService.extractEntities(question);
            response.put("nerEntities", nerResponse.getEntities());
            
            // 2. 原版 Text2SQL（依赖 LLM，可能失败）
            Map<String, Object> originalMap = new HashMap<>();
            try {
                ChatResponse originalResponse = chatService.text2SQL(question);
                originalMap.put("reply", originalResponse.getReply());
                originalMap.put("tags", originalResponse.getTags() != null ? originalResponse.getTags() : List.of());
            } catch (Exception e) {
                log.warn("原版 Text2SQL 失败: {}", e.getMessage());
                originalMap.put("reply", "[LLM 调用失败: " + e.getMessage() + "]");
                originalMap.put("tags", List.of());
                originalMap.put("error", e.getMessage());
            }
            response.put("original", originalMap);
            
            // 3. NER增强版 Text2SQL（依赖 LLM，可能失败）
            Map<String, Object> enhancedMap = new HashMap<>();
            try {
                NERResponse enhancedNerResponse = nerService.extractEntities(question);
                ChatResponse enhancedResponse = chatService.text2SQLWithNER(question, enhancedNerResponse);
                enhancedMap.put("reply", enhancedResponse.getReply());
                enhancedMap.put("tags", enhancedResponse.getTags() != null ? enhancedResponse.getTags() : List.of());
            } catch (Exception e) {
                log.warn("NER增强版 Text2SQL 失败: {}", e.getMessage());
                enhancedMap.put("reply", "[LLM 调用失败: " + e.getMessage() + "]");
                enhancedMap.put("tags", List.of());
                enhancedMap.put("error", e.getMessage());
            }
            response.put("enhanced", enhancedMap);
            
            response.put("success", true);
            
        } catch (Exception e) {
            log.error("Text2SQL对比测试失败", e);
            response.put("success", false);
            response.put("message", "对比测试失败: " + e.getMessage());
        }

        return response;
    }

    /**
     * 测试实体链接效果
     */
    @PostMapping("/test-entity-linking")
    public Map<String, Object> testEntityLinking(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }

        Map<String, Object> response = new HashMap<>();
        try {
            // 提取实体（包含链接）
            NERResponse nerResponse = nerService.extractEntities(text);
            
            // 统计链接成功的实体
            long linkedCount = nerResponse.getEntities().stream()
                    .filter(e -> e.getNormalizedValue() != null && !e.getNormalizedValue().isEmpty())
                    .count();
            
            response.put("success", true);
            response.put("totalEntities", nerResponse.getEntities().size());
            response.put("linkedEntities", linkedCount);
            response.put("linkingRate", nerResponse.getEntities().isEmpty() ? 0.0 : 
                (double) linkedCount / nerResponse.getEntities().size());
            response.put("entities", nerResponse.getEntities());
            
        } catch (Exception e) {
            log.error("实体链接测试失败", e);
            response.put("success", false);
            response.put("message", "实体链接测试失败: " + e.getMessage());
        }

        return response;
    }

    /**
     * NER 服务健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            boolean isHealthy = nerService != null && nerService.isHealthy();
            response.put("success", true);
            response.put("healthy", isHealthy);
            response.put("message", isHealthy ? "NER服务运行正常" : "NER服务不可用");
        } catch (Throwable e) {
            log.error("NER健康检查失败", e);
            response.put("success", false);
            response.put("healthy", false);
            response.put("message", "健康检查失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        return response;
    }

    /**
     * 刷新实体链接缓存
     */
    @PostMapping("/refresh-cache")
    public Map<String, Object> refreshCache() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            nerService.refreshEntityLinkingCache();
            response.put("success", true);
            response.put("message", "实体链接缓存已刷新");
        } catch (Exception e) {
            log.error("刷新缓存失败", e);
            response.put("success", false);
            response.put("message", "刷新缓存失败: " + e.getMessage());
        }

        return response;
    }
}