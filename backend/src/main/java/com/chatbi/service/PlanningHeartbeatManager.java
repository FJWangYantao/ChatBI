package com.chatbi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Planning 心跳管理器
 * 在 planWithTools 执行期间定期发送进度更新，保持 SSE 连接活跃
 */
@Slf4j
@Component
public class PlanningHeartbeatManager {

    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> {
                Thread thread = new Thread(r, "planning-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 启动心跳
     * @param sessionId 会话 ID
     * @param statusEmitter 状态发送器，接收进度消息
     */
    public void startHeartbeat(String sessionId, Consumer<String> statusEmitter) {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                int count = counter.incrementAndGet();
                String message = count == 1
                        ? "正在分析问题，准备执行代码..."
                        : "代码执行中，请稍候... (" + (count * 10) + "秒)";
                statusEmitter.accept(message);
                log.debug("[Heartbeat] Session {} - {}", sessionId, message);
            } catch (Exception e) {
                log.warn("[Heartbeat] Session {} 心跳发送失败: {}", sessionId, e.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);

        heartbeats.put(sessionId, future);
        log.info("[Heartbeat] Session {} 心跳已启动", sessionId);
    }

    /**
     * 停止心跳
     * @param sessionId 会话 ID
     */
    public void stopHeartbeat(String sessionId) {
        ScheduledFuture<?> future = heartbeats.remove(sessionId);
        if (future != null) {
            future.cancel(false);
            log.info("[Heartbeat] Session {} 心跳已停止", sessionId);
        }
    }

    /**
     * 清理所有心跳（用于应用关闭时）
     */
    public void shutdown() {
        heartbeats.values().forEach(future -> future.cancel(false));
        heartbeats.clear();
        scheduler.shutdown();
        log.info("[Heartbeat] 心跳管理器已关闭");
    }
}
