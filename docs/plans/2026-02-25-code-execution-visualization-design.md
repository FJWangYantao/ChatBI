# 代码执行可视化设计文档

## 1. 背景

### 问题描述

当前 ChatBI 系统在使用 `planWithTools()` 进行数据分析时，LLM 会调用 `execute_code` 工具生成并执行 Python 代码。但前端用户无法看到：
- LLM 生成的具体代码内容
- 代码执行的实时输出（stdout）
- 代码执行状态（成功/失败）
- 代码生成和执行的进度

这导致用户在等待 2-5 分钟的代码执行过程中，只能看到"代码执行中，请稍候..."的心跳消息，缺乏透明度和信任感。

### 用户需求

用户希望前端能够显示：
1. LLM 生成的完整 Python 代码
2. 代码执行的实时输出（print 语句等）
3. 代码执行状态（成功/失败）及错误信息
4. 代码生成进度提示（"LLM 正在生成代码..."）
5. 支持 LLM 多次调用工具，提高代码准确性

### 设计目标

- 提高用户信任度：让用户看到 AI 的分析过程
- 改善用户体验：提供实时反馈，避免"黑盒"感觉
- 支持多次迭代：允许 LLM 多次调用工具优化代码
- 保持性能：不影响现有的 SSE 流式响应机制

## 2. 技术方案

### 方案选择

采用**方案 A：新增 `code_execution` SSE 事件类型**

**核心思路：**
- 在后端新增专门的 SSE 事件类型 `code_execution`
- 通过 ThreadLocal 在工具调用时访问当前请求的 SseEmitter
- 前端新增独立的代码面板组件，自动弹出显示代码执行过程

**优势：**
- 语义清晰，职责分明
- 实时性好，可以在关键节点推送事件
- 可扩展性强，易于添加新功能
- 支持多次工具调用的历史记录

## 3. 架构设计

### 数据流

```
LLM 调用 execute_code 工具
    ↓
SandboxToolsConfig.executeCodeFunction 捕获调用
    ↓
从 ThreadLocal 获取 SseEmitter
    ↓
发送 code_execution 事件（stage: executing）
    ↓
调用 MCPSandboxService.executeCode
    ↓
发送 code_execution 事件（stage: completed/failed）
    ↓
前端接收并更新 CodeExecutionPanel
```

### SSE 事件格式

```json
{
  "event": "code_execution",
  "data": {
    "executionId": "exec_1",
    "stage": "executing",
    "code": "import pandas as pd\n...",
    "stdout": "执行输出...",
    "stderr": "错误信息...",
    "success": true,
    "executionTime": 1234
  }
}
```

**字段说明：**
- `executionId`: 执行 ID，用于区分多次工具调用（如 "exec_1", "exec_2"）
- `stage`: 执行阶段
  - `executing`: 代码正在执行
  - `completed`: 执行成功
  - `failed`: 执行失败
- `code`: Python 代码（完整）
- `stdout`: 标准输出
- `stderr`: 错误输出
- `success`: 是否成功
- `executionTime`: 执行耗时（毫秒）

## 4. 后端实现

### 4.1 新增 SseEmitterContext

创建 `backend/src/main/java/com/chatbi/context/SseEmitterContext.java`：

```java
@Component
public class SseEmitterContext {
    private static final ThreadLocal<SseEmitter> emitterHolder = new ThreadLocal<>();

    public static void setEmitter(SseEmitter emitter) {
        emitterHolder.set(emitter);
    }

    public static SseEmitter getEmitter() {
        return emitterHolder.get();
    }

    public static void clear() {
        emitterHolder.remove();
    }
}
```

### 4.2 修改 ChatStreamService

在 `handleDataAnalysisStream` 方法中：

```java
// 设置 ThreadLocal
SseEmitterContext.setEmitter(emitter);
try {
    // ... 现有逻辑
    toolResult = planningAgent.planWithTools(promptToUse);
} finally {
    // 清理 ThreadLocal
    SseEmitterContext.clear();
}
```

新增 `emitCodeExecution` 方法：

```java
public void emitCodeExecution(SseEmitter emitter, String executionId,
                               String stage, String code, String stdout,
                               String stderr, Boolean success, Long executionTime) throws IOException {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("executionId", executionId);
    data.put("stage", stage);
    if (code != null) data.put("code", code);
    if (stdout != null) data.put("stdout", stdout);
    if (stderr != null) data.put("stderr", stderr);
    if (success != null) data.put("success", success);
    if (executionTime != null) data.put("executionTime", executionTime);

    emitter.send(SseEmitter.event()
        .name("code_execution")
        .data(objectMapper.writeValueAsString(data)));
}
```

### 4.3 修改 SandboxToolsConfig

修改 `executeCodeFunction`：

```java
@Bean("executeCodeFunction")
public FunctionCallback executeCodeFunction(
        MCPSandboxService sandboxService,
        ChatStreamService chatStreamService) {

    AtomicInteger executionCounter = new AtomicInteger(0);

    return FunctionCallback.builder()
            .description("...")
            .function("execute_code", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                String code = (String) params.get("code");
                String dataJson = (String) params.getOrDefault("data_json", null);
                int timeout = params.containsKey("timeout")
                        ? ((Number) params.get("timeout")).intValue()
                        : 30;

                // 生成执行 ID
                String executionId = "exec_" + executionCounter.incrementAndGet();

                // 获取当前请求的 emitter
                SseEmitter emitter = SseEmitterContext.getEmitter();

                // 发送"执行中"事件
                if (emitter != null) {
                    try {
                        chatStreamService.emitCodeExecution(
                            emitter, executionId, "executing", code, null, null, null, null);
                    } catch (Exception e) {
                        log.warn("[CodeExecution] 发送执行中事件失败: {}", e.getMessage());
                    }
                }

                // 执行代码
                long startTime = System.currentTimeMillis();
                Map<String, Object> result = sandboxService.executeCode(code, dataJson, timeout);
                long executionTime = System.currentTimeMillis() - startTime;

                // 发送"完成"事件
                if (emitter != null) {
                    try {
                        boolean success = (Boolean) result.getOrDefault("success", false);
                        String stdout = (String) result.get("stdout");
                        String stderr = (String) result.get("stderr");

                        chatStreamService.emitCodeExecution(
                            emitter, executionId,
                            success ? "completed" : "failed",
                            code, stdout, stderr, success, executionTime);
                    } catch (Exception e) {
                        log.warn("[CodeExecution] 发送完成事件失败: {}", e.getMessage());
                    }
                }

                return result;
            })
            .inputType(Map.class)
            .build();
}
```

### 4.4 移除 prompt 限制

修改 `PlanningAgent.planWithTools` 中的 prompt，移除"最多只调用 1 次"的限制：

```java
String prompt = String.format("""
    你是一个数据分析专家，拥有以下工具：
    1. execute_code: 在安全沙盒中执行 Python 代码进行数据分析
    2. validate_code: 预检代码安全性
    3. sandbox_info: 查询沙盒环境能力

    **重要规则：**
    - 如果第一次代码执行失败，可以修正代码后再次调用 execute_code
    - 不需要调用 validate_code 和 sandbox_info，除非用户明确要求
    - 代码要简洁高效，直接输出关键结果

    用户问题：%s
    识别到的实体：%s
    数据库结构：
    %s

    请分析用户问题，如果需要执行代码来回答，请使用 execute_code 工具。
    用中文回复。
    """, question, entitiesStr, schemaInfo);
```

## 5. 前端实现

### 5.1 扩展 SSE 回调类型

修改 `frontend/lib/api/chatStream.ts`：

```typescript
export interface StreamCallbacks {
  // ... 现有回调
  onCodeExecution?: (data: {
    executionId: string;
    stage: string;
    code?: string;
    stdout?: string;
    stderr?: string;
    success?: boolean;
    executionTime?: number;
  }) => void;
}
```

在 `processSSELine` 中添加：

```typescript
case 'code_execution':
  callbacks.onCodeExecution?.(data);
  break;
```

### 5.2 创建 CodeExecutionPanel 组件

创建 `frontend/components/CodeExecution/CodeExecutionPanel.tsx`：

```typescript
interface CodeExecution {
  executionId: string;
  stage: string;
  code: string;
  stdout: string;
  stderr: string;
  success: boolean;
  executionTime: number;
}

interface CodeExecutionPanelProps {
  executions: CodeExecution[];
  isOpen: boolean;
  onClose: () => void;
}

export default function CodeExecutionPanel({
  executions,
  isOpen,
  onClose
}: CodeExecutionPanelProps) {
  if (!isOpen) return null;

  return (
    <div className="fixed right-0 top-0 h-full w-[600px] bg-background border-l border-border shadow-2xl z-50">
      {/* 标题栏 */}
      <div className="flex items-center justify-between p-4 border-b border-border">
        <h3 className="font-semibold">代码执行详情</h3>
        <button onClick={onClose}>关闭</button>
      </div>

      {/* 执行历史列表 */}
      <div className="overflow-y-auto h-[calc(100%-60px)]">
        {executions.map((exec, index) => (
          <div key={exec.executionId} className="p-4 border-b border-border">
            <div className="flex items-center justify-between mb-2">
              <span className="font-medium">第 {index + 1} 次执行</span>
              <span className={exec.success ? "text-green-500" : "text-red-500"}>
                {exec.stage === "executing" ? "执行中..." :
                 exec.success ? "成功" : "失败"}
              </span>
            </div>

            {/* 代码块 */}
            <div className="bg-muted rounded-lg p-3 mb-2">
              <pre className="text-sm overflow-x-auto">
                <code>{exec.code}</code>
              </pre>
            </div>

            {/* 输出 */}
            {exec.stdout && (
              <div className="bg-muted rounded-lg p-3 mb-2">
                <div className="text-xs text-muted-foreground mb-1">输出：</div>
                <pre className="text-sm">{exec.stdout}</pre>
              </div>
            )}

            {/* 错误 */}
            {exec.stderr && (
              <div className="bg-red-50 rounded-lg p-3 mb-2">
                <div className="text-xs text-red-600 mb-1">错误：</div>
                <pre className="text-sm text-red-700">{exec.stderr}</pre>
              </div>
            )}

            {/* 执行时间 */}
            {exec.executionTime && (
              <div className="text-xs text-muted-foreground">
                执行耗时：{exec.executionTime}ms
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

### 5.3 集成到主页面

修改 `frontend/app/page.tsx`：

```typescript
// 新增状态
const [codeExecutions, setCodeExecutions] = useState<CodeExecution[]>([]);
const [codePanelOpen, setCodePanelOpen] = useState(false);

// 在 streamChatMessage 回调中添加
onCodeExecution: (data) => {
  setCodeExecutions((prev) => {
    const existing = prev.find(e => e.executionId === data.executionId);
    if (existing) {
      // 更新现有执行记录
      return prev.map(e =>
        e.executionId === data.executionId
          ? { ...e, ...data }
          : e
      );
    } else {
      // 新增执行记录
      return [...prev, data as CodeExecution];
    }
  });

  // 自动打开代码面板
  setCodePanelOpen(true);
}

// 渲染组件
<CodeExecutionPanel
  executions={codeExecutions}
  isOpen={codePanelOpen}
  onClose={() => setCodePanelOpen(false)}
/>
```

## 6. 错误处理

### 6.1 SSE 连接断开

在 `emitCodeExecution` 中捕获异常：

```java
try {
    emitter.send(...);
} catch (IOException e) {
    log.warn("[CodeExecution] SSE 连接已断开，跳过事件发送");
}
```

### 6.2 ThreadLocal 清理

在 `ChatStreamService.handleDataAnalysisStream` 中使用 try-finally 确保清理：

```java
try {
    SseEmitterContext.setEmitter(emitter);
    // ... 业务逻辑
} finally {
    SseEmitterContext.clear();
}
```

### 6.3 代码过大

前端代码块添加滚动和折叠功能：

```typescript
<pre className="text-sm overflow-x-auto max-h-[300px] overflow-y-auto">
  <code>{exec.code}</code>
</pre>
```

## 7. 测试验证

### 7.1 单元测试

- 测试 `SseEmitterContext` 的 ThreadLocal 隔离性
- 测试 `emitCodeExecution` 的事件格式正确性

### 7.2 集成测试

1. 启动后端和 MCP Sandbox 服务
2. 前端输入需要代码执行的问题（如"分析 2023 年 10 月数据"）
3. 验证代码面板自动弹出
4. 验证显示完整代码、输出、状态
5. 验证多次工具调用时显示多条执行记录

### 7.3 验证点

- ✅ 代码面板自动弹出
- ✅ 显示完整的 Python 代码
- ✅ 显示实时 stdout 输出
- ✅ 显示执行状态（成功/失败）
- ✅ 显示错误信息（stderr）
- ✅ 显示执行耗时
- ✅ 支持多次工具调用的历史记录
- ✅ SSE 连接不超时（心跳机制保持连接）

## 8. 优势总结

1. **透明度提升**：用户可以看到 AI 的完整分析过程
2. **信任度增强**：代码和输出可见，用户可以验证结果
3. **调试友好**：出错时可以直接看到错误信息
4. **支持迭代**：允许 LLM 多次调用工具优化代码
5. **低侵入性**：不影响现有流程，只是新增事件类型
6. **可扩展性**：未来可以添加代码高亮、复制、下载等功能

## 9. 未来扩展

- 代码语法高亮（使用 Prism.js 或 highlight.js）
- 代码复制按钮
- 代码下载功能
- 执行历史持久化（保存到数据库）
- 代码编辑和重新执行功能
