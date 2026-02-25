package com.chatbi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 模型性能监控服务
 * 跟踪 NER 准确率、SQL 生成/纠错成功率、响应时间等关键指标
 */
@Service
public class ModelPerformanceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ModelPerformanceMonitor.class);

    private final LongAdder nerTotalCalls = new LongAdder();
    private final LongAdder nerSuccessCalls = new LongAdder();
    private final LongAdder sqlGenerationTotal = new LongAdder();
    private final LongAdder sqlGenerationSuccess = new LongAdder();
    private final LongAdder sqlCorrectionTotal = new LongAdder();
    private final LongAdder sqlCorrectionApplied = new LongAdder();
    private final LongAdder sqlExecutionTotal = new LongAdder();
    private final LongAdder sqlExecutionSuccess = new LongAdder();

    private final ConcurrentHashMap<String, LongAdder> timings = new ConcurrentHashMap<>();

    /**
     * 记录 NER 调用结果
     */
    public void recordNERCall(boolean success) {
        nerTotalCalls.increment();
        if (success) {
            nerSuccessCalls.increment();
        }
    }

    /**
     * 记录 SQL 生成结果
     */
    public void recordSQLGeneration(boolean success) {
        sqlGenerationTotal.increment();
        if (success) {
            sqlGenerationSuccess.increment();
        }
    }

    /**
     * 记录 SQL 纠错
     */
    public void recordSQLCorrection(boolean corrected) {
        sqlCorrectionTotal.increment();
        if (corrected) {
            sqlCorrectionApplied.increment();
        }
    }

    /**
     * 记录 SQL 执行结果
     */
    public void recordSQLExecution(boolean success) {
        sqlExecutionTotal.increment();
        if (success) {
            sqlExecutionSuccess.increment();
        }
    }

    /**
     * 记录耗时（毫秒）
     */
    public void recordTiming(String metric, long millis) {
        timings.computeIfAbsent(metric, k -> new LongAdder()).add(millis);
    }

    /**
     * 获取 NER 成功率
     */
    public double getNERSuccessRate() {
        long total = nerTotalCalls.sum();
        if (total == 0) return 0;
        return (double) nerSuccessCalls.sum() / total;
    }

    /**
     * 获取 SQL 生成成功率
     */
    public double getSQLGenerationSuccessRate() {
        long total = sqlGenerationTotal.sum();
        if (total == 0) return 0;
        return (double) sqlGenerationSuccess.sum() / total;
    }

    /**
     * 获取 SQL 纠错应用率
     */
    public double getSQLCorrectionRate() {
        long total = sqlCorrectionTotal.sum();
        if (total == 0) return 0;
        return (double) sqlCorrectionApplied.sum() / total;
    }

    /**
     * 获取 SQL 执行成功率
     */
    public double getSQLExecutionSuccessRate() {
        long total = sqlExecutionTotal.sum();
        if (total == 0) return 0;
        return (double) sqlExecutionSuccess.sum() / total;
    }

    /**
     * 获取汇总指标
     */
    public PerformanceMetrics getMetrics() {
        PerformanceMetrics m = new PerformanceMetrics();
        m.setNerTotalCalls(nerTotalCalls.sum());
        m.setNerSuccessCalls(nerSuccessCalls.sum());
        m.setNerSuccessRate(getNERSuccessRate());
        m.setSqlGenerationTotal(sqlGenerationTotal.sum());
        m.setSqlGenerationSuccess(sqlGenerationSuccess.sum());
        m.setSqlGenerationSuccessRate(getSQLGenerationSuccessRate());
        m.setSqlCorrectionTotal(sqlCorrectionTotal.sum());
        m.setSqlCorrectionApplied(sqlCorrectionApplied.sum());
        m.setSqlCorrectionRate(getSQLCorrectionRate());
        m.setSqlExecutionTotal(sqlExecutionTotal.sum());
        m.setSqlExecutionSuccess(sqlExecutionSuccess.sum());
        m.setSqlExecutionSuccessRate(getSQLExecutionSuccessRate());
        return m;
    }

    /**
     * 重置统计（用于测试或定期清零）
     */
    public void reset() {
        nerTotalCalls.reset();
        nerSuccessCalls.reset();
        sqlGenerationTotal.reset();
        sqlGenerationSuccess.reset();
        sqlCorrectionTotal.reset();
        sqlCorrectionApplied.reset();
        sqlExecutionTotal.reset();
        sqlExecutionSuccess.reset();
        timings.clear();
        logger.info("ModelPerformanceMonitor 统计已重置");
    }

    /**
     * 性能指标 DTO
     */
    public static class PerformanceMetrics {
        private long nerTotalCalls;
        private long nerSuccessCalls;
        private double nerSuccessRate;
        private long sqlGenerationTotal;
        private long sqlGenerationSuccess;
        private double sqlGenerationSuccessRate;
        private long sqlCorrectionTotal;
        private long sqlCorrectionApplied;
        private double sqlCorrectionRate;
        private long sqlExecutionTotal;
        private long sqlExecutionSuccess;
        private double sqlExecutionSuccessRate;

        // Getters and Setters
        public long getNerTotalCalls() { return nerTotalCalls; }
        public void setNerTotalCalls(long v) { this.nerTotalCalls = v; }
        public long getNerSuccessCalls() { return nerSuccessCalls; }
        public void setNerSuccessCalls(long v) { this.nerSuccessCalls = v; }
        public double getNerSuccessRate() { return nerSuccessRate; }
        public void setNerSuccessRate(double v) { this.nerSuccessRate = v; }
        public long getSqlGenerationTotal() { return sqlGenerationTotal; }
        public void setSqlGenerationTotal(long v) { this.sqlGenerationTotal = v; }
        public long getSqlGenerationSuccess() { return sqlGenerationSuccess; }
        public void setSqlGenerationSuccess(long v) { this.sqlGenerationSuccess = v; }
        public double getSqlGenerationSuccessRate() { return sqlGenerationSuccessRate; }
        public void setSqlGenerationSuccessRate(double v) { this.sqlGenerationSuccessRate = v; }
        public long getSqlCorrectionTotal() { return sqlCorrectionTotal; }
        public void setSqlCorrectionTotal(long v) { this.sqlCorrectionTotal = v; }
        public long getSqlCorrectionApplied() { return sqlCorrectionApplied; }
        public void setSqlCorrectionApplied(long v) { this.sqlCorrectionApplied = v; }
        public double getSqlCorrectionRate() { return sqlCorrectionRate; }
        public void setSqlCorrectionRate(double v) { this.sqlCorrectionRate = v; }
        public long getSqlExecutionTotal() { return sqlExecutionTotal; }
        public void setSqlExecutionTotal(long v) { this.sqlExecutionTotal = v; }
        public long getSqlExecutionSuccess() { return sqlExecutionSuccess; }
        public void setSqlExecutionSuccess(long v) { this.sqlExecutionSuccess = v; }
        public double getSqlExecutionSuccessRate() { return sqlExecutionSuccessRate; }
        public void setSqlExecutionSuccessRate(double v) { this.sqlExecutionSuccessRate = v; }
    }
}
