# 代码执行可视化功能 - 集成测试指南

## 测试环境确认

所有必需的服务已启动：
- ✅ 前端服务：http://localhost:3000
- ✅ 后端服务：http://localhost:8080
- ✅ MCP Sandbox：http://localhost:8003

## 测试步骤

### 1. 打开应用
在浏览器中访问：http://localhost:3000

### 2. 发送数据分析请求
在聊天输入框中输入一个需要数据分析的问题，例如：
```
分析一下这些数据的趋势：[1, 5, 3, 8, 10, 7, 12]
```

或者：
```
帮我生成一个简单的柱状图，数据是：销售额 [100, 200, 150, 300]
```

### 3. 观察代码执行面板
当 LLM 调用 `execute_code` 工具时，应该看到：

#### 3.1 面板自动打开
- 右侧应该自动滑出一个代码执行面板（宽度 600px）
- 面板标题显示"代码执行"

#### 3.2 执行中状态
- 显示"第 N 次执行"
- 状态显示"执行中..."（蓝色）
- 显示正在执行的 Python 代码

#### 3.3 执行完成状态
执行完成后，面板应该更新显示：
- 状态变为"✓ 成功"（绿色）或"✗ 失败"（红色）
- 显示代码输出（stdout）
- 如果有错误，显示错误信息（stderr，红色背景）
- 显示执行耗时（毫秒）

### 4. 多次执行测试
如果 LLM 第一次代码执行失败，它可能会修正代码后再次调用。观察：
- 面板中应该显示多个执行记录
- 每个执行都有独立的执行 ID
- 按时间顺序排列（最新的在上面）

### 5. 手动关闭面板
点击面板右上角的"×"按钮，面板应该关闭。

### 6. 新对话测试
点击"新对话"按钮，代码执行历史应该被清空。

## 预期结果

✅ **成功标准**：
1. 代码执行时面板自动打开
2. 显示"执行中"状态和代码
3. 执行完成后显示输出和状态
4. 支持多次执行的历史记录
5. 可以手动关闭面板
6. 新对话时清空历史

## 技术验证点

### 后端日志检查
在后端控制台中应该看到类似的日志：
```
[SandboxTool] execute_code called, executionId=exec_1, code length=XXX
```

### 前端 Network 检查
打开浏览器开发者工具 → Network 标签：
1. 找到 `/api/chat/stream` 的 SSE 连接
2. 应该能看到 `event: code_execution` 的事件
3. 事件数据包含：executionId, stage, code, stdout, stderr, success, executionTime

### SSE 事件格式示例
```
event: code_execution
data: {"executionId":"exec_1","stage":"executing","code":"import pandas as pd\n..."}

event: code_execution
data: {"executionId":"exec_1","stage":"completed","code":"...","stdout":"...","success":true,"executionTime":1234}
```

## 故障排查

### 面板没有打开
- 检查浏览器控制台是否有 JavaScript 错误
- 确认 SSE 连接是否建立
- 检查 `onCodeExecution` 回调是否被调用

### 没有收到代码执行事件
- 检查后端日志，确认 `SseEmitterContext.getEmitter()` 返回非 null
- 确认 `emitCodeExecution` 方法被调用
- 检查 SSE 连接是否正常

### 循环依赖错误
如果后端启动失败并提示循环依赖，确认：
- `SandboxToolsConfig` 使用 `ApplicationContext` 而不是直接注入 `ChatStreamService`
- 代码中使用 `applicationContext.getBean(ChatStreamService.class)` 延迟获取

## 测试完成
完成以上测试后，代码执行可视化功能集成测试通过！
