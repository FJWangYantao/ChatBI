package com.chatbi.context;

import com.chatbi.dto.MessageTag;
import com.chatbi.dto.StreamingTagEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SSE Emitter 上下文管理器
 * 使用 ThreadLocal 在工具调用时访问当前请求的 SseEmitter，
 * 同时收集工具内部发送的 tag，供保存消息时使用。
 *
 * Holder 内部类将所有字段封装为可传递对象，支持跨线程共享（并行子任务场景）。
 */
@Component
public class SseEmitterContext {

    /**
     * 可跨线程传递的上下文持有者。
     * 并行 CodeAgent 线程通过 setHolder(holder) 复用主线程的 SSE 连接。
     */
    public static class Holder {
        private final SseEmitter emitter;
        private final List<MessageTag> tagCollector;
        private final Consumer<StreamingTagEvent> tagStreamCallback;
        private final AtomicBoolean disconnected;

        public Holder(SseEmitter emitter,
                      Consumer<StreamingTagEvent> tagStreamCallback) {
            this.emitter = emitter;
            this.tagCollector = Collections.synchronizedList(new ArrayList<>());
            this.tagStreamCallback = tagStreamCallback;
            this.disconnected = new AtomicBoolean(false);
        }

        public SseEmitter getEmitter() { return emitter; }
        public Consumer<StreamingTagEvent> getTagStreamCallback() { return tagStreamCallback; }
        public boolean isDisconnected() { return disconnected.get(); }
        public void markDisconnected() { disconnected.set(true); }

        public void collectTag(MessageTag tag) {
            tagCollector.add(tag);
        }

        public List<MessageTag> drainCollectedTags() {
            if (tagCollector.isEmpty()) return List.of();
            List<MessageTag> result = new ArrayList<>(tagCollector);
            tagCollector.clear();
            return result;
        }

        /**
         * 线程安全的 SSE 写入（多个 CodeAgent 线程共享同一个 emitter）
         */
        public void safeSend(SseEmitter.SseEventBuilder event) {
            if (disconnected.get()) return;
            synchronized (emitter) {
                try {
                    emitter.send(event);
                } catch (IOException e) {
                    disconnected.set(true);
                }
            }
        }
    }

    private static final ThreadLocal<Holder> holderLocal = new ThreadLocal<>();

    /**
     * 设置当前线程的 Holder（并行线程使用）
     */
    public static void setHolder(Holder holder) {
        holderLocal.set(holder);
    }

    /**
     * 获取当前线程的 Holder
     */
    public static Holder getHolder() {
        return holderLocal.get();
    }

    /**
     * 设置当前线程的 SseEmitter（向后兼容，内部创建 Holder）
     */
    public static void setEmitter(SseEmitter emitter) {
        Holder holder = new Holder(emitter, null);
        holderLocal.set(holder);
    }

    /**
     * 标记客户端已断开连接
     */
    public static void markDisconnected() {
        Holder holder = holderLocal.get();
        if (holder != null) holder.markDisconnected();
    }

    /**
     * 检查客户端是否已断开连接
     */
    public static boolean isDisconnected() {
        Holder holder = holderLocal.get();
        return holder != null && holder.isDisconnected();
    }

    /**
     * 获取当前线程的 SseEmitter
     */
    public static SseEmitter getEmitter() {
        Holder holder = holderLocal.get();
        return holder != null ? holder.emitter : null;
    }

    /**
     * 收集一个 tag
     */
    public static void collectTag(MessageTag tag) {
        Holder holder = holderLocal.get();
        if (holder != null) holder.collectTag(tag);
    }

    /**
     * 获取并清空已收集的 tags
     */
    public static List<MessageTag> drainCollectedTags() {
        Holder holder = holderLocal.get();
        if (holder == null) return List.of();
        return holder.drainCollectedTags();
    }

    /**
     * 设置流式 tag 回调
     */
    public static void setTagStreamCallback(Consumer<StreamingTagEvent> callback) {
        Holder holder = holderLocal.get();
        if (holder != null) {
            // Holder 的 callback 是 final 的，需要重建 Holder
            // 但为了向后兼容，这里用一个额外的 ThreadLocal
            tagStreamCallbackOverride.set(callback);
        } else {
            tagStreamCallbackOverride.set(callback);
        }
    }

    private static final ThreadLocal<Consumer<StreamingTagEvent>> tagStreamCallbackOverride = new ThreadLocal<>();

    /**
     * 获取流式 tag 回调
     */
    public static Consumer<StreamingTagEvent> getTagStreamCallback() {
        // 优先使用 override（setTagStreamCallback 设置的）
        Consumer<StreamingTagEvent> override = tagStreamCallbackOverride.get();
        if (override != null) return override;
        Holder holder = holderLocal.get();
        return holder != null ? holder.getTagStreamCallback() : null;
    }

    /**
     * 清理当前线程的所有上下文
     */
    public static void clear() {
        holderLocal.remove();
        tagStreamCallbackOverride.remove();
    }
}
