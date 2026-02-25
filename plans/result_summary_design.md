# 数据查询结果智能总结功能设计方案

## 1. 目标
将原本固定的“查询成功”回复，升级为基于用户问题和查询结果的**智能自然语言总结**。让系统不仅能“查数据”，还能“读数据”。

## 2. 核心逻辑
在 SQL 执行成功并获取到结果集（`List<Map<String, Object>> rows`）之后，增加一个 AI 处理环节。

### 流程图
```mermaid
graph TD
    A[用户提问] --> B[生成 SQL]
    B --> C[执行 SQL]
    C -->|成功| D{结果集大小?}
    D -->|空结果| E[返回: 未查询到相关数据]
    D -->|有数据| F[截取前N条数据 + 统计信息]
    F --> G[调用 AI 生成总结]
    G --> H[组合最终回复 (总结 + 表格/图表)]
    C -->|失败| I[返回错误信息]
```

## 3. Prompt 设计 (提示词)

为了保证总结的准确性和简洁性，我们需要精心设计 Prompt。

**System Prompt:**
```text
你是一个数据分析助手。请根据用户的“原始问题”和数据库查询返回的“数据片段”，生成一句简短的总结。

要求：
1. 必须基于提供的数据回答，严禁编造。
2. 语言自然、简洁（100字以内）。
3. 如果是统计类数据（如总销售额），直接回答数值。
4. 如果是列表类数据（如前十名），列举前 1-3 个关键项并概括。
5. 不要提及 "SQL"、"查询"、"数据库" 等技术术语。
6. 语气客观、专业。
```

**User Prompt 模板:**
```text
用户问题：{question}
总行数：{total_rows}
数据预览（前 {preview_count} 行）：
{data_json_string}

请生成总结：
```

## 4. 代码修改计划 (`backend/src/main/java/com/chatbi/service/ChatService.java`)

### 4.1 新增方法 `generateResultSummary`
```java
private String generateResultSummary(String question, List<Map<String, Object>> rows) {
    // 1. 处理空数据情况
    if (rows.isEmpty()) {
        return "未查询到相关数据。";
    }

    // 2. 准备数据预览 (避免 Token 超限，仅取前 5 条)
    int previewCount = Math.min(rows.size(), 5);
    List<Map<String, Object>> previewData = rows.subList(0, previewCount);
    
    // 3. 构造 Prompt
    String prompt = String.format("""
        用户问题：%s
        总行数：%d
        数据预览（前 %d 行）：%s
        
        请根据上述数据，用一句话简要回答用户问题或总结数据结果。
        """, 
        question, 
        rows.size(), 
        previewCount, 
        previewData.toString()
    );

    // 4. 调用 AI (增加异常处理，失败则降级)
    try {
        return chatClient.prompt().user(prompt).call().content();
    } catch (Exception e) {
        log.error("生成总结失败", e);
        return "查询成功，结果如下："; // 降级回复
    }
}
```

### 4.2 修改 `executeSQLAndBuildResponse`
- **原签名**: `private ChatResponse executeSQLAndBuildResponse(String sql)`
- **新签名**: `private ChatResponse executeSQLAndBuildResponse(String question, String sql)`
- **逻辑变更**:
    - 在 `queryForList` 成功后，调用 `generateResultSummary(question, rows)`。
    - 将返回的 `summary` 传入 `ChatResponse` 构造函数，替代原本的 `"查询成功"`。

### 4.3 更新调用点
- `text2SQL(String question)` 方法中，调用 `executeSQLAndBuildResponse` 时传入 `question`。

## 5. 风险与对策
1.  **延迟增加**: 增加一次 LLM 调用会增加 1-2 秒的延迟。
    *   *对策*: 仅对 `DATA_QUERY` 意图且执行成功的场景启用。Prompt 保持简单以加快生成速度。
2.  **Token 超限**: 查询结果可能包含大量文本。
    *   *对策*: 严格限制 `previewData` 的大小（如最多 5 行，每行字段过长也需截断）。
3.  **幻觉风险**: AI 可能会过度解读。
    *   *对策*: System Prompt 强调“必须基于提供的数据”，并限制回答长度。

## 6. 下一步
确认无误后，切换到 Code 模式实施上述修改。
