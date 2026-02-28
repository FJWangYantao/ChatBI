package com.chatbi.context;

import com.chatbi.dto.MessageTag;
import com.chatbi.dto.StreamingTagEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SSE Emitter 上下文管理器
 * 使用 ThreadLocal 在工具调用时访问当前请求的 SseEmitter，
 * 同时收集工具内部发送的 tag，供保存消息时使用
 */
@Component
public class SseEmitterContext {

    private static final ThreadLocal<SseEmitter> emitterHolder = new ThreadLocal<>();
    private static final ThreadLocal<List<MessageTag>> tagCollector = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<StreamingTagEvent>> tagStreamCallback = new ThreadLocal<>();
    private static final ThreadLocal<AtomicBoolean> disconnectedFlag = new ThreadLocal<>();

    /**
     * 设置当前线程的 SseEmitter，同时初始化 tag 收集器和连接状态
     */
    public static void setEmitter(SseEmitter emitter) {
        emitterHolder.set(emitter);
        tagCollector.set(new ArrayList<>());
        disconnectedFlag.set(new AtomicBoolean(false));
    }

    /**
     * 标记客户端已断开连接（SSE 发送 IOException 时调用）
     */
    public static void markDisconnected() {
        AtomicBoolean flag = disconnectedFlag.get();
        if (flag != null) flag.set(true);
    }

    /**
     * 检查客户端是否已断开连接
     */
    public static boolean isDisconnected() {
        AtomicBoolean flag = disconnectedFlag.get();
        return flag != null && flag.get();
    }

    /**
     * 获取当前线程的 SseEmitter
     */
    public static SseEmitter getEmitter() {
        return emitterHolder.get();
    }

    /**
     * 收集一个 tag（工具内部发送 SSE tag 时同步调用）
     */
    public static void collectTag(MessageTag tag) {
        List<MessageTag> tags = tagCollector.get();
        if (tags != null) {
            tags.add(tag);
        }
    }

    /**
     * 获取并清空已收集的 tags
     */
    public static List<MessageTag> drainCollectedTags() {
        List<MessageTag> tags = tagCollector.get();
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<MessageTag> result = new ArrayList<>(tags);
        tags.clear();
        return result;
    }

    /**
     * 设置流式 tag 回调（用于在工具函数内部向 SSE 发送流式 tag）
     */
    public static void setTagStreamCallback(Consumer<StreamingTagEvent> callback) {
        tagStreamCallback.set(callback);
    }

    /**
     * 获取流式 tag 回调
     */
    public static Consumer<StreamingTagEvent> getTagStreamCallback() {
        return tagStreamCallback.get();
    }

    /**
     * 清理当前线程的所有上下文
     */
    public static void clear() {
        emitterHolder.remove();
        tagCollector.remove();
        tagStreamCallback.remove();
        disconnectedFlag.remove();
    }
}
