# 代码执行可视化实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在前端显示 LLM 生成的 Python 代码、执行输出和状态，提高用户信任度和透明度

**Architecture:** 通过 ThreadLocal 在后端工具调用时访问 SseEmitter，发送新的 `code_execution` SSE 事件到前端，前端新增独立代码面板组件自动显示代码执行过程

**Tech Stack:** Spring Boot, SSE, ThreadLocal, React, TypeScript, Next.js

---

## Task 1: 创建 SseEmitterContext（后端 ThreadLocal 管理）

**Files:**
- Create: `backend/src/main/java/com/chatbi/context/SseEmitterContext.java`

**Step 1: 创建 SseEmitterContext 类**

```java
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
```

**Step 2: 编译验证**

Run: `cd backend && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add backend/src/main/java/com/chatbi/context/SseEmitterContext.java
git commit -m "feat(context): add SseEmitterContext for ThreadLocal emitter management"
```

---

## Task 2: 在 ChatStreamService 中添加 emitCodeExecution 方法

**Files:**
- Modify: `backend/src/main/java/com/chatbi/service/ChatStreamService.java`

**Step 1: 添加 emitCodeExecution 方法**

在 `ChatStreamService` 类的末尾（`emitError` 方法之后）添加：

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

**Step 2: 编译验证**

Run: `cd backend && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add backend/src/main/java/com/chatbi/service/ChatStreamService.java
git commit -m "feat(sse): add emitCodeExecution method for code execution events"
```

---

## Task 3: 修改 ChatStreamService 集成 ThreadLocal

**Files:**
- Modify: `backend/src/main/java/com/chatbi/service/ChatStreamService.java`

**Step 1: 添加 import**

在文件顶部添加：

```java
import com.chatbi.context.SseEmitterContext;
```

**Step 2: 修改 handleDataAnalysisStream 方法**

找到 `handleDataAnalysisStream` 方法中的 planWithTools 调用部分（约 234-256 行），修改为：

```java
// 规划: 尝试 Function Calling 增强（LLM 自主调用沙盒工具）
emitStatus(emitter, "planning", "正在制定分析计划...", 4, 7);

String sessionId = java.util.UUID.randomUUID().toString();
String toolResult = null;

// 设置 ThreadLocal
SseEmitterContext.setEmitter(emitter);

// 启动心跳
heartbeatManager.startHeartbeat(sessionId, (message) -> {
    try {
        emitStatus(emitter, "planning", message, 4, 7);
    } catch (Exception e) {
        log.debug("心跳发送失败: {}", e.getMessage());
    }
});

try {
    toolResult = planningAgent.planWithTools(promptToUse);
} catch (Exception e) {
    log.warn("[planWithTools] Function Calling 失败，回退到传统流程: {}", e.getMessage());
} finally {
    // 停止心跳
    heartbeatManager.stopHeartbeat(sessionId);
    // 清理 ThreadLocal
    SseEmitterContext.clear();
}

if (toolResult != null && !toolResult.isBlank()) {
    log.info("[planWithTools] LLM 自主分析完成，直接返回结果");
    try {
        emitTextDeltaFull(emitter, toolResult);
    } catch (Exception e) {
        log.warn("[planWithTools] SSE 发送失败（连接可能已超时）: {}", e.getMessage());
    }
    return toolResult;
}
```

**Step 3: 编译验证**

Run: `cd backend && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add backend/src/main/java/com/chatbi/service/ChatStreamService.java
git commit -m "feat(stream): integrate ThreadLocal for code execution tracking"
```

---

## Task 4: 修改 SandboxToolsConfig 发送代码执行事件

**Files:**
- Modify: `backend/src/main/java/com/chatbi/config/SandboxToolsConfig.java`

**Step 1: 添加 imports**

在文件顶部添加：

```java
import com.chatbi.context.SseEmitterContext;
import com.chatbi.service.ChatStreamService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.atomic.AtomicInteger;
```

**Step 2: 修改 executeCodeFunction Bean**

将现有的 `executeCodeFunction` 方法替换为：

```java
@Bean("executeCodeFunction")
public FunctionCallback executeCodeFunction(
        MCPSandboxService sandboxService,
        ChatStreamService chatStreamService) {

    AtomicInteger executionCounter = new AtomicInteger(0);

    return FunctionCallback.builder()
            .description("在安全沙盒中执行 Python 数据分析代码。数据通过 data_json 参数传入，"
                    + "会自动加载为 pandas DataFrame（变量名 df）。支持 matplotlib 绘图，"
                    + "图表以 base64 图片返回。仅允许导入: pandas, numpy, matplotlib, "
                    + "seaborn, sklearn, scipy, json, re, math, datetime, collections, "
                    + "itertools, functools, io, base64。")
            .function("execute_code", (Function<Map<String, Object>, Map<String, Object>>) params -> {
                String code = (String) params.get("code");
                String dataJson = (String) params.getOrDefault("data_json", null);
                int timeout = params.containsKey("timeout")
                        ? ((Number) params.get("timeout")).intValue()
                        : 30;

                // 生成执行 ID
                String executionId = "exec_" + executionCounter.incrementAndGet();
                log.info("[SandboxTool] execute_code called, executionId={}, code length={}",
                        executionId, code != null ? code.length() : 0);

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

**Step 3: 编译验证**

Run: `cd backend && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add backend/src/main/java/com/chatbi/config/SandboxToolsConfig.java
git commit -m "feat(sandbox): emit code execution events during tool calls"
```

---

## Task 5: 修改 PlanningAgent 移除单次调用限制

**Files:**
- Modify: `backend/src/main/java/com/chatbi/service/PlanningAgent.java`

**Step 1: 修改 prompt**

找到 `planWithTools` 方法中的 prompt（约 128-147 行），将其修改为：

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

**Step 2: 编译验证**

Run: `cd backend && mvn compile -DskipTests`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add backend/src/main/java/com/chatbi/service/PlanningAgent.java
git commit -m "feat(planning): allow multiple tool calls for code refinement"
```

---

## Task 6: 扩展前端 SSE 回调类型

**Files:**
- Modify: `frontend/lib/api/chatStream.ts`

**Step 1: 添加 onCodeExecution 回调类型**

在 `StreamCallbacks` 接口中添加（约第 14 行之后）：

```typescript
onCodeExecution?: (data: {
  executionId: string;
  stage: string;
  code?: string;
  stdout?: string;
  stderr?: string;
  success?: boolean;
  executionTime?: number;
}) => void;
```

**Step 2: 在 processSSELine 中添加处理**

在 `processSSELine` 函数的 switch 语句中添加（约第 42 行之后）：

```typescript
case 'code_execution':
  callbacks.onCodeExecution?.(data);
  break;
```

**Step 3: 验证语法**

Run: `cd frontend && npm run build`
Expected: 编译成功（可能有其他警告，但不应有 chatStream.ts 的错误）

**Step 4: Commit**

```bash
git add frontend/lib/api/chatStream.ts
git commit -m "feat(sse): add onCodeExecution callback for code execution events"
```

---

## Task 7: 创建 CodeExecutionPanel 组件

**Files:**
- Create: `frontend/components/CodeExecution/CodeExecutionPanel.tsx`
- Create: `frontend/types/code-execution.ts`

**Step 1: 创建类型定义文件**

```typescript
// frontend/types/code-execution.ts
export interface CodeExecution {
  executionId: string;
  stage: string;
  code: string;
  stdout: string;
  stderr: string;
  success: boolean;
  executionTime: number;
}
```

**Step 2: 创建 CodeExecutionPanel 组件**

```typescript
// frontend/components/CodeExecution/CodeExecutionPanel.tsx
import { CodeExecution } from "@/types/code-execution";

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
    <div className="fixed right-0 top-0 h-full w-[600px] bg-background border-l border-border shadow-2xl z-50 flex flex-col">
      {/* 标题栏 */}
      <div className="flex items-center justify-between p-4 border-b border-border bg-gradient-to-r from-muted/50 to-background">
        <h3 className="font-semibold font-display text-lg">代码执行详情</h3>
        <button
          onClick={onClose}
          className="px-3 py-1 text-sm rounded-lg hover:bg-accent/10 transition-colors"
        >
          关闭
        </button>
      </div>

      {/* 执行历史列表 */}
      <div className="overflow-y-auto flex-1 p-4 space-y-4">
        {executions.length === 0 && (
          <div className="text-center text-muted-foreground py-10">
            暂无代码执行记录
          </div>
        )}

        {executions.map((exec, index) => (
          <div
            key={exec.executionId}
            className="glass-card border border-border/50 rounded-2xl p-4 space-y-3"
          >
            {/* 执行头部 */}
            <div className="flex items-center justify-between">
              <span className="font-medium font-display">
                第 {index + 1} 次执行
              </span>
              <span
                className={`text-sm font-medium ${
                  exec.stage === "executing"
                    ? "text-blue-500"
                    : exec.success
                    ? "text-green-500"
                    : "text-red-500"
                }`}
              >
                {exec.stage === "executing"
                  ? "执行中..."
                  : exec.success
                  ? "✓ 成功"
                  : "✗ 失败"}
              </span>
            </div>

            {/* 代码块 */}
            {exec.code && (
              <div className="bg-muted/50 rounded-lg p-3 border border-border/30">
                <div className="text-xs text-muted-foreground mb-2 font-mono">
                  Python 代码：
                </div>
                <pre className="text-sm overflow-x-auto max-h-[300px] overflow-y-auto font-mono">
                  <code>{exec.code}</code>
                </pre>
              </div>
            )}

            {/* 输出 */}
            {exec.stdout && (
              <div className="bg-muted/50 rounded-lg p-3 border border-border/30">
                <div className="text-xs text-muted-foreground mb-2 font-mono">
                  输出：
                </div>
                <pre className="text-sm whitespace-pre-wrap font-mono">
                  {exec.stdout}
                </pre>
              </div>
            )}

            {/* 错误 */}
            {exec.stderr && (
              <div className="bg-red-50 dark:bg-red-950/20 rounded-lg p-3 border border-red-200 dark:border-red-800">
                <div className="text-xs text-red-600 dark:text-red-400 mb-2 font-mono">
                  错误：
                </div>
                <pre className="text-sm text-red-700 dark:text-red-300 whitespace-pre-wrap font-mono">
                  {exec.stderr}
                </pre>
              </div>
            )}

            {/* 执行时间 */}
            {exec.executionTime !== undefined && exec.executionTime > 0 && (
              <div className="text-xs text-muted-foreground font-mono">
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

**Step 3: 验证语法**

Run: `cd frontend && npm run build`
Expected: 编译成功

**Step 4: Commit**

```bash
git add frontend/components/CodeExecution/CodeExecutionPanel.tsx frontend/types/code-execution.ts
git commit -m "feat(ui): add CodeExecutionPanel component for code visualization"
```

---

## Task 8: 集成 CodeExecutionPanel 到主页面

**Files:**
- Modify: `frontend/app/page.tsx`

**Step 1: 添加 imports**

在文件顶部添加：

```typescript
import CodeExecutionPanel from "@/components/CodeExecution/CodeExecutionPanel";
import { CodeExecution } from "@/types/code-execution";
```

**Step 2: 添加状态**

在 `Home` 组件中，找到现有的 state 声明（约第 50-56 行），在其后添加：

```typescript
// 代码执行状态
const [codeExecutions, setCodeExecutions] = useState<CodeExecution[]>([]);
const [codePanelOpen, setCodePanelOpen] = useState(false);
```

**Step 3: 添加 onCodeExecution 回调**

在 `handleSendMessage` 函数的 `streamChatMessage` 调用中，找到现有的回调（约第 230-238 行），在 `onSuggestions` 之后添加：

```typescript
onCodeExecution: (data) => {
  setCodeExecutions((prev) => {
    const existing = prev.find(e => e.executionId === data.executionId);
    if (existing) {
      // 更新现有执行记录
      return prev.map(e =>
        e.executionId === data.executionId
          ? { ...e, ...data } as CodeExecution
          : e
      );
    } else {
      // 新增执行记录
      return [...prev, data as CodeExecution];
    }
  });

  // 自动打开代码面板
  if (!codePanelOpen) {
    setCodePanelOpen(true);
  }
},
```

**Step 4: 添加组件渲染**

在 `return` 语句的最后，`</ThemeProvider>` 之前添加：

```typescript
{/* 代码执行面板 */}
<CodeExecutionPanel
  executions={codeExecutions}
  isOpen={codePanelOpen}
  onClose={() => setCodePanelOpen(false)}
/>
```

**Step 5: 清理代码执行历史**

在 `handleNewConversation` 函数中添加（约第 94 行之后）：

```typescript
setCodeExecutions([]);
setCodePanelOpen(false);
```

**Step 6: 验证语法**

Run: `cd frontend && npm run build`
Expected: 编译成功

**Step 7: Commit**

```bash
git add frontend/app/page.tsx
git commit -m "feat(ui): integrate CodeExecutionPanel into main page"
```

---

## Task 9: 集成测试

**Files:**
- None (manual testing)

**Step 1: 启动 MCP Sandbox 服务**

Run: `cd backend/mcp-sandbox-server && python main.py`
Expected: 服务启动在 http://localhost:8003

**Step 2: 启动后端服务**

Run: `cd backend && mvn spring-boot:run`
Expected: 服务启动在 http://localhost:8080

**Step 3: 启动前端服务**

Run: `cd frontend && npm run dev`
Expected: 服务启动在 http://localhost:3000

**Step 4: 测试代码执行可视化**

1. 打开浏览器访问 http://localhost:3000
2. 输入需要代码执行的问题（如"分析 2023 年 10 月的销售数据"）
3. 观察右侧代码面板是否自动弹出
4. 验证显示：
   - ✅ 完整的 Python 代码
   - ✅ 执行状态（执行中 → 成功/失败）
   - ✅ stdout 输出
   - ✅ stderr 错误信息（如果有）
   - ✅ 执行耗时
5. 如果 LLM 多次调用工具，验证显示多条执行记录

**Step 5: 验证完成后停止服务**

按 Ctrl+C 停止所有服务

---

## 验证清单

- [ ] 后端编译成功
- [ ] 前端编译成功
- [ ] 代码面板自动弹出
- [ ] 显示完整 Python 代码
- [ ] 显示执行状态
- [ ] 显示 stdout 输出
- [ ] 显示 stderr 错误
- [ ] 显示执行耗时
- [ ] 支持多次工具调用历史
- [ ] SSE 连接不超时（心跳机制正常）
- [ ] 可以手动关闭代码面板

---

## 注意事项

1. **ThreadLocal 清理**：确保在 finally 块中调用 `SseEmitterContext.clear()`，避免内存泄漏
2. **SSE 异常处理**：所有 `emitCodeExecution` 调用都应该 try-catch，避免影响主流程
3. **多次工具调用**：前端使用 `executionId` 区分不同的执行记录
4. **代码过大**：前端代码块设置了 `max-h-[300px]` 和滚动条
5. **暗色模式**：组件使用了 `dark:` 前缀支持暗色模式
