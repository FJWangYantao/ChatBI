package com.chatbi.exception;

import com.chatbi.config.LLMConfigProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LLMConfigProvider.LLMConfigMissingException.class)
    public ResponseEntity<Map<String, String>> handleLLMConfigMissing(
            LLMConfigProvider.LLMConfigMissingException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "配置缺失",
                "message", "请在前端配置 LLM 模型信息",
                "detail", ex.getMessage()
            ));
    }
}
