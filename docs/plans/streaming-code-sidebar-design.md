# 代码侧栏真流式输出设计文档

## 1. 背景与目标

### 当前问题
- SQL/Python 代码通过 `emitTag()` 一次性发送完整内容，前端只能做本地打字动画模拟
- `PlanningAgent` 和 `Text2SQLAgent` 均使用 `.call()` 阻塞调用，必须等 LLM 完整生成后才能返回
- 最终回复通过 `emitTextDeltaFull()` 在后端分块模拟流式（4字符/30ms），并非真正的 LLM token 流

### 目标
将三层内容全部改为真正的 LLM token 级流式输出：
1. **SQL 代码**：`Text2SQLAgent` 生成 SQL 时实时转发 token
2. **Python 代码**：`PlanningAgent` 的 function call 参数实时转发 token
3. **最终回复**：LLM 生成推理链和结论时实时转发 token

---

## 2. SSE 事件协议设计

### 2.1 新增事件类型

现有 `tag` 事件（一次性发送完整内容）拆分为三个事件：

```
tag_start  → 通知前端：一个新的代码块开始了
tag_delta  → 增量内容：代码 token 片段
tag_end    → 通知前端：代码块结束，附带完整内容用于持久化
```

### 2.2 事件数据格式

**tag_start**
```json
{
  "id": "sql-uuid-1",
  "type": "sql",
  "title": "SQL 查询"
}
```

**tag_delta**
```json
{
  "id": "sql-uuid-1",
  "delta": "SELECT customer_name, "
}
```

**tag_end**
```json
{
  "id": "sql-uuid-1",
  "type": "sql",
  "title": "SQL 查询",
  "content": "SELECT customer_name, SUM(amount) FROM orders GROUP BY customer_name"
}
```

### 2.3 兼容性

- `tag_end` 的数据结构与现有 `MessageTag` 一致，可直接用于持久化
- 非代码类型的 tag（如 `table`、`chart`、`image`）仍使用原有 `tag` 事件一次性发送
- 只有 `sql` 和 `code` 类型走流式协议

---

## 3. 后端改造方案

### 3.1 Text2SQLAgent — SQL 流式生成

**当前代码（阻塞）：**
```java
String sql = chatClient.prompt()
    .options(modelOptions.getOptions("text2sql"))
    .user(systemPrompt)
    .call()
    .content();
```

**改造后（流式）：**
```java
public String fetchDataWithStreaming(String dataQuery, Consumer<String> onSqlDelta) {
    // ...省略 prompt 构建...

    StringBuilder sqlBuilder = new StringBuilder();
    Flux<String> sqlFlux = chatClient.prompt()
        .options(modelOptions.getOptions("text2sql"))
        .user(systemPrompt)
        .stream()
        .content();

    // 阻塞订阅，但每个 token 实时回调
    sqlFlux.doOnNext(token -> {
        sqlBuilder.append(token);
        onSqlDelta.accept(token);  // 实时转发给 SSE
    }).blockLast();

    String sql = sqlBuilder.toString();
    // 后续纠错、执行逻辑不变...
}
```

**关键点：**
- 新增 `Consumer<String> onSqlDelta` 参数，每收到一个 token 就回调
- 使用 `Flux.doOnNext()` + `blockLast()` 保持同步执行语义，同时实现实时回调
- 原有 `fetchData()` 方法保留，内部调用新方法并传入空回调（向后兼容）

### 3.2 SseEmitterContext — 扩展流式回调能力

**新增字段：**
```java
// 用于在工具函数内部向 SSE 发送流式 tag
private static final ThreadLocal<Consumer<StreamingTagEvent>> tagStreamCallback = new ThreadLocal<>();
```

**新增方法：**
```java
public static void setTagStreamCallback(Consumer<StreamingTagEvent> callback) {
    tagStreamCallback.set(callback);
}

public static Consumer<StreamingTagEvent> getTagStreamCallback() {
    return tagStreamCallback.get();
}
```

**StreamingTagEvent 记录类：**
```java
public record StreamingTagEvent(String eventType, String id, String type, String title, String delta, Object content) {
    public static StreamingTagEvent start(String id, String type, String title) { ... }
    public static StreamingTagEvent delta(String id, String delta) { ... }
    public static StreamingTagEvent end(String id, String type, String title, Object content) { ... }
}
```

**作用：** 让 `queryDatabaseFunction` 内部的 `Text2SQLAgent` 能通过 ThreadLocal 拿到回调，将 SQL token 实时转发到 SSE。

### 3.3 SandboxToolsConfig — queryDatabaseFunction 改造

**改动点：** 在 `query_database` 函数内部，调用 `Text2SQLAgent` 的流式版本。

```java
// 从 ThreadLocal 获取流式回调
Consumer<StreamingTagEvent> callback = SseEmitterContext.getTagStreamCallback();

// 生成唯一 tag ID
String tagId = "sql-" + UUID.randomUUID().toString().substring(0, 8);

// 发送 tag_start
if (callback != null) {
    callback.accept(StreamingTagEvent.start(tagId, "sql", "SQL 查询"));
}

// 调用流式版本，每个 SQL token 实时转发
List<Map<String, Object>> data = text2SQLAgent.fetchDataWithStreaming(
    dataDescription,
    delta -> {
        if (callback != null) {
            callback.accept(StreamingTagEvent.delta(tagId, delta));
        }
    }
);

// 发送 tag_end（附带完整 SQL 用于持久化）
if (callback != null) {
    callback.accept(StreamingTagEvent.end(tagId, "sql", "SQL 查询", finalSQL));
}
```

### 3.4 PlanningAgent — 核心改造（最复杂）

**挑战：** Spring AI 的 `.call()` 在 function calling 模式下是全自动的——LLM 生成 function call → 框架执行函数 → 结果回传 LLM → LLM 继续生成。整个过程对调用者不透明。

**方案：手动实现 function calling 循环 + 流式读取**

核心思路：放弃 Spring AI 的自动 function calling，改为手动控制每一轮 LLM 调用，这样可以拦截每个 token。

```java
public String planWithToolsStreaming(
        String question,
        Consumer<String> onTextDelta,
        Consumer<StreamingTagEvent> onTagEvent) {

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(buildSystemPrompt(question)));
    messages.add(new UserMessage(question));

    while (true) {
        // 流式调用 LLM（不自动执行工具）
        StreamingResult result = streamLLMCall(messages, onTextDelta, onTagEvent);

        if (!result.hasToolCalls()) {
            // 最终回复，已通过 onTextDelta 实时转发
            return result.getFullText();
        }

        // 手动执行工具
        messages.add(result.getAssistantMessage());
        for (ToolCall toolCall : result.getToolCalls()) {
            Object toolResult = executeToolManually(toolCall);
            messages.add(new ToolResponseMessage(toolCall.id(), toolResult));
        }
        // 继续循环，让 LLM 看到工具结果后继续生成
    }
}
```

**streamLLMCall 内部逻辑 — 拦截 function call 参数流：**

```java
private StreamingResult streamLLMCall(
        List<Message> messages,
        Consumer<String> onTextDelta,
        Consumer<StreamingTagEvent> onTagEvent) {

    StringBuilder textBuffer = new StringBuilder();
    Map<String, StringBuilder> toolArgBuffers = new HashMap<>();
    Map<String, String> toolNames = new HashMap<>();
    // 跟踪哪些工具已发送 tag_start
    Set<String> startedCodeTags = new HashSet<>();

    Flux<ChatResponse> flux = chatModel.stream(
        new Prompt(messages, chatOptions));

    flux.doOnNext(response -> {
        Generation gen = response.getResult();
        AssistantMessage msg = gen.getOutput();

        // 1. 文本内容 → 实时转发
        if (msg.getText() != null && !msg.getText().isEmpty()) {
            textBuffer.append(msg.getText());
            onTextDelta.accept(msg.getText());
        }

        // 2. 工具调用参数增量 → 拦截 Python 代码
        for (ToolCall toolCall : msg.getToolCalls()) {
            String toolId = toolCall.id();
            toolNames.putIfAbsent(toolId, toolCall.name());
            toolArgBuffers.computeIfAbsent(toolId, k -> new StringBuilder());
            toolArgBuffers.get(toolId).append(toolCall.arguments());

            // 如果是 execute_code，提取 "code" 字段的增量
            if ("execute_code".equals(toolCall.name())) {
                String argDelta = toolCall.arguments();
                extractCodeDelta(toolId, argDelta, onTagEvent, startedCodeTags);
            }
        }
    }).blockLast();

    return new StreamingResult(textBuffer, toolArgBuffers, toolNames);
}
```

**extractCodeDelta — 从 JSON 参数增量中提取 Python 代码：**

LLM 流式生成 function call 参数时，参数是 JSON 格式的增量片段。我们需要从累积的不完整 JSON 中提取 `"code"` 字段值：

```java
private void extractCodeDelta(String toolId, String argDelta,
        Consumer<StreamingTagEvent> onTagEvent, Set<String> started) {
    String prevCode = lastExtractedCode.getOrDefault(toolId, "");
    String fullArgs = toolArgBuffers.get(toolId).toString();

    // 尝试从不完整 JSON 中提取 code 字段
    String currentCode = extractPartialCodeField(fullArgs);

    if (currentCode != null && currentCode.length() > prevCode.length()) {
        if (!started.contains(toolId)) {
            onTagEvent.accept(StreamingTagEvent.start(
                "code-" + toolId, "code", "Python 代码"));
            started.add(toolId);
        }
        String delta = currentCode.substring(prevCode.length());
        onTagEvent.accept(StreamingTagEvent.delta("code-" + toolId, delta));
        lastExtractedCode.put(toolId, currentCode);
    }
}
```

### 3.5 ChatStreamService — 新增流式 tag 发送方法

```java
private void emitTagStart(SseEmitter emitter, String id, String type, String title) throws IOException {
    Map<String, String> data = Map.of("id", id, "type", type, "title", title);
    emitter.send(SseEmitter.event().name("tag_start").data(objectMapper.writeValueAsString(data)));
}

private void emitTagDelta(SseEmitter emitter, String id, String delta) throws IOException {
    Map<String, String> data = Map.of("id", id, "delta", delta);
    emitter.send(SseEmitter.event().name("tag_delta").data(objectMapper.writeValueAsString(data)));
}

private void emitTagEnd(SseEmitter emitter, String id, String type, String title, Object content) throws IOException {
    MessageTag tag = new MessageTag(type, content, title, null);
    Map<String, Object> data = Map.of("id", id, "tag", tag);
    emitter.send(SseEmitter.event().name("tag_end").data(objectMapper.writeValueAsString(data)));
}
```

**handleDataAnalysisStream 改造要点：**

```java
// 1. 设置流式 tag 回调到 ThreadLocal
SseEmitterContext.setTagStreamCallback(event -> {
    try {
        switch (event.eventType()) {
            case "start" -> emitTagStart(emitter, event.id(), event.type(), event.title());
            case "delta" -> emitTagDelta(emitter, event.id(), event.delta());
            case "end"   -> emitTagEnd(emitter, event.id(), event.type(), event.title(), event.content());
        }
    } catch (IOException e) {
        log.warn("流式 tag 发送失败: {}", e.getMessage());
    }
});

// 2. 调用流式版 PlanningAgent
String toolResult = planningAgent.planWithToolsStreaming(
    promptToUse,
    delta -> emitTextDelta(emitter, delta),   // 文本 token 实时转发
    event -> { /* 同上 tag 回调 */ }
);

// 3. 不再需要 emitTextDeltaFull()，文本已实时发送
// 4. 非代码类 tag（table、chart、image）仍用原有 emitTag() 一次性发送
```

---

## 4. 前端改造方案

### 4.1 chatStream.ts — 新增 SSE 事件处理

```typescript
// 新增回调类型
interface StreamCallbacks {
  // ...现有回调...
  onTagStart?: (data: { id: string; type: string; title: string }) => void;
  onTagDelta?: (data: { id: string; delta: string }) => void;
  onTagEnd?: (data: { id: string; tag: MessageTag }) => void;
}

// EventSource 事件监听
eventSource.addEventListener('tag_start', (e) => {
  const data = JSON.parse(e.data);
  callbacks.onTagStart?.(data);
});

eventSource.addEventListener('tag_delta', (e) => {
  const data = JSON.parse(e.data);
  callbacks.onTagDelta?.(data);
});

eventSource.addEventListener('tag_end', (e) => {
  const data = JSON.parse(e.data);
  callbacks.onTagEnd?.(data);
});
```

### 4.2 page.tsx — 回调改造

```typescript
// 替换现有的 onTag 中 sql/code 处理逻辑

onTagStart: (data) => {
  const entryType = data.type === 'sql' ? 'sql' : 'python';
  const newEntry: CodeEntry = {
    id: data.id,
    type: entryType,
    code: '',           // 初始为空，等待 delta 填充
    title: data.title,
    timestamp: Date.now(),
    messageId: assistantId,
    isStreaming: true,
  };
  setCodeEntries(prev => [...prev, newEntry]);
  setActiveCodeEntryId(data.id);
  setCodeSidebarOpen(true);
},

onTagDelta: (data) => {
  setCodeEntries(prev =>
    prev.map(e =>
      e.id === data.id
        ? { ...e, code: e.code + data.delta }
        : e
    )
  );
},

onTagEnd: (data) => {
  setCodeEntries(prev =>
    prev.map(e =>
      e.id === data.id
        ? { ...e, isStreaming: false, code: data.tag.content }
        : e
    )
  );
  // 同时更新 message tags 用于持久化
  tagsAccumulator.push(data.tag);
  setMessages(prev =>
    prev.map(msg =>
      msg.id === assistantId
        ? { ...msg, tags: [...tagsAccumulator] }
        : msg
    )
  );
},
```

### 4.3 StreamingCodeBlock — 简化为直接渲染

移除本地打字动画逻辑，因为内容已经是真正的流式到达：

```typescript
// 之前：本地 setInterval 模拟打字
// 之后：直接渲染 code prop，它会随 SSE delta 实时增长

const StreamingCodeBlock = ({ code, language, isStreaming }: Props) => {
  return (
    <pre className="...">
      <code>{code}</code>
      {isStreaming && <span className="animate-pulse">▊</span>}
    </pre>
  );
};
```

不再需要 `displayedLength`、`setInterval`、`charPerTick` 等状态。组件变得极其简单——React 会在 `code` prop 变化时自动重渲染。

---

## 5. 风险与难点分析

### 5.1 Spring AI 流式 Function Calling 的兼容性

**风险：** Spring AI 的 `ChatClient.stream()` 在 function calling 模式下的行为可能与预期不同。不同版本的 Spring AI 对流式 tool call 的支持程度不一。

**应对：**
- 需要先验证当前 Spring AI 版本是否支持流式返回 tool call 参数增量
- 如果不支持，降级方案：PlanningAgent 仍用 `.call()`，但 Text2SQLAgent 改为流式（SQL 流式可独立实现）
- 最终回复改为后端分块发送（类似现有 `emitTextDeltaFull` 但更细粒度）

### 5.2 增量 JSON 解析的可靠性

**风险：** 从不完整的 JSON 字符串中提取 `"code"` 字段值，可能因转义字符（`\n`、`\"`、`\\`）导致解析错误。

**应对：**
- 实现一个简单的状态机解析器，跟踪 JSON 字符串内的转义状态
- 只需处理 `"code": "..."` 这一个字段，不需要完整 JSON 解析
- 添加容错：解析失败时跳过当前 delta，等累积更多内容后重试

### 5.3 并发安全

**风险：** `SseEmitter.send()` 不是线程安全的。如果 SQL 流式回调和主线程同时发送 SSE 事件，可能导致数据损坏。

**应对：**
- 所有 `emitter.send()` 调用加 `synchronized(emitter)` 保护
- 或使用单线程 `ExecutorService` 序列化所有 SSE 发送操作

### 5.4 前端高频渲染性能

**风险：** LLM token 速度可能达到每秒 50-100 个，每个 delta 都触发 `setCodeEntries` → React 重渲染，可能造成卡顿。

**应对：**
- 使用 `requestAnimationFrame` 或 `useRef` 缓冲 delta，每 16ms 批量更新一次 state
- CodeEntryCard 使用 `React.memo` 避免无关 entry 重渲染
- 代码区域使用 `<pre>` 直接渲染文本，不做语法高亮（流式期间）

---

## 6. 实施计划（分阶段）

### Phase 1：验证 Spring AI 流式能力（前置调研）
- 确认当前 Spring AI 版本号
- 编写最小 POC：`ChatClient.stream()` + function calling，验证能否拿到 tool call 参数增量
- 如果不支持，确定降级方案

### Phase 2：后端基础设施
- 新增 `StreamingTagEvent` 记录类
- `SseEmitterContext` 扩展 `tagStreamCallback`
- `ChatStreamService` 新增 `emitTagStart/Delta/End` 方法
- 所有 `emitter.send()` 加同步保护

### Phase 3：SQL 流式（最简单，先做）
- `Text2SQLAgent` 新增 `fetchDataWithStreaming()` 方法
- `SandboxToolsConfig.queryDatabaseFunction` 接入流式回调
- `ChatStreamService.handleDataAnalysisStream` 设置 ThreadLocal 回调

### Phase 4：Python 代码流式 + 最终回复流式（最复杂）
- `PlanningAgent` 新增 `planWithToolsStreaming()` 方法
- 手动实现 function calling 循环
- 实现增量 JSON 解析器提取 `"code"` 字段
- 最终回复直接通过 `onTextDelta` 转发，移除 `emitTextDeltaFull` 调用

### Phase 5：前端适配
- `chatStream.ts` 新增 `tag_start/tag_delta/tag_end` 事件监听
- `page.tsx` 新增 `onTagStart/onTagDelta/onTagEnd` 回调
- `StreamingCodeBlock` 移除本地打字动画，改为直接渲染
- 添加 `requestAnimationFrame` 批量更新优化

### Phase 6：测试与回归
- 验证 SQL 流式：发送数据查询，观察 SQL 是否逐字符出现
- 验证 Python 流式：观察 execute_code 的代码是否逐字符出现
- 验证最终回复流式：推理链和结论是否实时显示
- 回归测试：历史对话加载、非代码类 tag（table/chart/image）、代码执行结果显示

---

## 7. 改动文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `StreamingTagEvent.java` | 新增 | 流式 tag 事件记录类 |
| `SseEmitterContext.java` | 修改 | 新增 tagStreamCallback ThreadLocal |
| `ChatStreamService.java` | 修改 | 新增 emitTagStart/Delta/End；handleDataAnalysisStream 接入流式 |
| `Text2SQLAgent.java` | 修改 | 新增 fetchDataWithStreaming() 流式方法 |
| `PlanningAgent.java` | 重构 | 新增 planWithToolsStreaming()，手动 function calling 循环 |
| `SandboxToolsConfig.java` | 修改 | queryDatabaseFunction 接入流式回调 |
| `chatStream.ts` | 修改 | 新增 tag_start/tag_delta/tag_end 事件监听 |
| `page.tsx` | 修改 | 新增 onTagStart/onTagDelta/onTagEnd 回调 |
| `StreamingCodeBlock.tsx` | 简化 | 移除本地打字动画，直接渲染 |
