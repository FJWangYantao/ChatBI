# SQL 编辑与手动运行功能改造方案

## 1. 需求分析
用户希望能够编辑 AI 生成的 SQL 查询语句，并手动运行修改后的 SQL，以获得更新后的查询结果（表格、图表等）。

## 2. 总体架构
*   **后端**: 新增 `/chat/execute-sql` 接口，接收 SQL 语句并执行，返回 `ChatResponse`（包含结果标签）。
*   **前端**: 改造 `ChatWindow` 中的 SQL 展示组件，增加“编辑”和“运行”模式。运行成功后，更新当前消息的展示内容。

## 3. 后端改造 (Java Spring Boot)

### 3.1 新增 DTO
创建 `backend/src/main/java/com/chatbi/dto/ExecuteSqlRequest.java`:
```java
package com.chatbi.dto;

import lombok.Data;

@Data
public class ExecuteSqlRequest {
    private String sql;
    private String conversationId; // 可选，用于记录日志或上下文
}
```

### 3.2 Service 层改造 (`ChatService.java`)
1.  **重构 `executeSQLAndBuildResponse`**:
    *   目前该方法是 `private` 的。
    *   需要将其核心逻辑提取出来，或者将其改为 `public` 并允许从 Controller 调用。
    *   建议提取一个 `public ChatResponse executeSql(String sql)` 方法。

2.  **新增 `executeSql` 方法**:
```java
public ChatResponse executeSql(String sql) {
    // 复用现有的 executeSQLAndBuildResponse 逻辑
    // question 参数可以传 null 或 "Manual Execution"
    return executeSQLAndBuildResponse("Manual Execution", sql);
}
```

### 3.3 Controller 层改造 (`ChatController.java`)
新增接口:
```java
@PostMapping("/execute-sql")
public ChatResponse executeSql(@RequestBody ExecuteSqlRequest request) {
    return chatService.executeSql(request.getSql());
}
```

## 4. 前端改造 (Next.js + React)

### 4.1 API Client 更新
在 `frontend/lib/api/chat.ts` (需确认文件位置，假设存在或新建) 中添加:
```typescript
export async function executeSql(sql: string) {
  const res = await fetch('/api/chat/execute-sql', { // 注意：需配置 Next.js 代理转发到后端
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sql }),
  });
  if (!res.ok) throw new Error('Execution failed');
  return res.json();
}
```

### 4.2 组件改造 (`ChatWindow.tsx`)

#### 4.2.1 状态提升
`ChatWindow` 需要能够更新 `messages` 列表中的某条消息。
目前 `messages` 是通过 props 传入的吗？
查看代码，`ChatWindow` 接收 `messages` prop。这意味着状态管理可能在父组件 `frontend/app/page.tsx` 中。
我们需要在 `ChatWindow` 中定义一个 `onUpdateMessage` 回调，或者如果 `ChatWindow` 自己维护状态（当前代码看起来是受控组件），则需要父组件传递更新函数。

**假设**: 我们将在 `ChatWindow` 内部处理临时的显示更新，或者请求父组件更新。
查看 `frontend/app/page.tsx` (虽然没读，但通常是这样)。
为了简化，我们可以在 `ChatWindow` 中处理“重新运行”的逻辑：
1.  用户点击“运行”。
2.  调用 API。
3.  成功后，**替换**当前消息的 `tags`。

#### 4.2.2 改造 `MessageTagRenderer`
目前的 `MessageTagRenderer` 是一个纯展示组件。我们需要将其改造为有状态的组件，或者拆分出一个 `SqlEditor` 组件。

**新组件 `SqlEditor`**:
*   Props: `initialSql`, `onRun(newSql) -> Promise<void>`
*   State: `isEditing`, `sqlContent`, `isLoading`
*   UI:
    *   显示 SQL (代码高亮)。
    *   “编辑”按钮 -> 切换为 `<textarea>`。
    *   “运行”按钮 -> 调用 `onRun`。
    *   “取消”按钮 -> 恢复原状。

#### 4.2.3 集成逻辑
在 `ChatWindow` 中：
1.  修改 `MessageTagRenderer` 的 `sql` 分支。
2.  传入一个 `onRun` 回调。
3.  这个回调会调用 `executeSql` API。
4.  拿到结果后，更新 `message.tags`。

**注意**: 由于 `ChatWindow` 的 `messages` 是 props，我们可能需要一种方式来更新本地状态。
如果 `ChatWindow` 只是展示，我们需要在 `page.tsx` 中传递 `setMessages` 或 `updateMessage` 方法。

**方案**:
在 `ChatWindow` 中添加 `onMessageUpdate` prop (可选)。
如果父组件没传，我们可能无法持久化更新。
但为了快速实现，我们可以先在 `ChatWindow` 内部强制刷新 UI，或者要求用户修改 `page.tsx`。

**推荐方案**:
修改 `ChatWindow` props，增加 `onUpdateMessage?: (messageId: string, newTags: MessageTag[]) => void`。

## 5. 实施步骤
1.  **后端**: 修改 `ChatService.java` 和 `ChatController.java`。
2.  **前端**:
    *   修改 `frontend/types/chat.ts` (如果存在) 或相关类型定义。
    *   修改 `frontend/app/page.tsx` 以支持消息更新。
    *   修改 `frontend/components/Chat/ChatWindow.tsx` 实现编辑和运行逻辑。

