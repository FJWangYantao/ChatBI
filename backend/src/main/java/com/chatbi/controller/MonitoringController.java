package com.chatbi.controller;

import com.chatbi.service.ModelPerformanceMonitor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型性能监控 API
 */
@RestController
@RequestMapping("/monitoring")
public class MonitoringController {

    private final ModelPerformanceMonitor performanceMonitor;

    public MonitoringController(ModelPerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }

    /**
     * 获取性能指标
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        ModelPerformanceMonitor.PerformanceMetrics m = performanceMonitor.getMetrics();
        Map<String, Object> result = new HashMap<>();
        result.put("ner", Map.of(
                "totalCalls", m.getNerTotalCalls(),
                "successCalls", m.getNerSuccessCalls(),
                "successRate", m.getNerSuccessRate()
        ));
        result.put("sqlGeneration", Map.of(
                "total", m.getSqlGenerationTotal(),
                "success", m.getSqlGenerationSuccess(),
                "successRate", m.getSqlGenerationSuccessRate()
        ));
        result.put("sqlCorrection", Map.of(
                "total", m.getSqlCorrectionTotal(),
                "applied", m.getSqlCorrectionApplied(),
                "correctionRate", m.getSqlCorrectionRate()
        ));
        result.put("sqlExecution", Map.of(
                "total", m.getSqlExecutionTotal(),
                "success", m.getSqlExecutionSuccess(),
                "successRate", m.getSqlExecutionSuccessRate()
        ));
        return ResponseEntity.ok(result);
    }

    /**
     * 重置统计（用于测试）
     */
    @PostMapping("/reset")
    public ResponseEntity<String> reset() {
        performanceMonitor.reset();
        return ResponseEntity.ok("已重置");
    }
}
