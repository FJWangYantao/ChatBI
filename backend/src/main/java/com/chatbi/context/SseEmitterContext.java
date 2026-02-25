package com.chatbi.context;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE Emitter 上下文管理器
 * 使用 ThreadLocal 在工具调用时访问当前请求的 SseEmitter
 */
@Component
public class SseEmitterContext {

    private static final ThreadLocal<SseEmitter> emitterHolder = new ThreadLocal<>();

    /**
     * 设置当前线程的 SseEmitter
     */
    public static void setEmitter(SseEmitter emitter) {
        emitterHolder.set(emitter);
    }

    /**
     * 获取当前线程的 SseEmitter
     */
    public static SseEmitter getEmitter() {
        return emitterHolder.get();
    }

    /**
     * 清理当前线程的 SseEmitter
     */
    public static void clear() {
        emitterHolder.remove();
    }
}
