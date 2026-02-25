# Sandbox MCP 服务设计文档

## 背景

ChatBI 项目中的 sandbox-service（端口 8003）是一个 Python FastAPI 服务，提供安全的代码执行环境。当前由 Java 后端的 CodeAgent 通过 REST API 程序化调用。

目标：将 sandbox 包装为 MCP 风格的 tool 服务，让 PlanningAgent 通过 LLM Function Calling 自主决定何时调用沙盒。

## 方案选择

采用方案 A：REST 风格 MCP，与现有 mcp-knowledge-server 模式一致。

理由：
- 改动最小，复用现有架构
- 和 knowledge server 风格统一
- PlanningAgent 只需改 prompt + 注册 function
- 未来可平滑升级为标准 MCP 协议

## 架构设计

### 调用流程

```
PlanningAgent(LLM + Function Calling)
  → LLM 看到可用 tools（execute_code, validate_code, sandbox_info）
  → LLM 自主决定是否调用
  → Spring AI Function 拦截调用
  → MCPSandboxService 发 HTTP 请求
  → sandbox-service /tools/* 端点
  → 返回结果给 LLM 继续推理
```

### 与现有 CodeAgent 的关系

现有流程（PlanningAgent 生成 plan → CodeAgent 程序化调用沙盒）保持不变。
新的 Function Calling 路径是并行能力，两条路径共存。

## MCP Tool 定义

### Tool 1: execute_code
- 描述：在安全沙盒中执行 Python 数据分析代码
- 参数：
  - code (string, 必填): Python 代码
  - data_json (string, 可选): JSON 数据，加载为 pandas DataFrame (df)
  - timeout (int, 默认30): 超时秒数
- 返回：success, stdout, stderr, images[]

### Tool 2: validate_code
- 描述：预检代码安全性，不实际执行
- 参数：code (string, 必填)
- 返回：valid (bool), errors (string[])

### Tool 3: sandbox_info
- 描述：查询沙盒环境信息
- 参数：无
- 返回：allowed_modules[], allowed_builtins[], timeout_limit, status

## 需要改动的文件

### Python 端
1. `backend/sandbox-service/main.py` — 新增 /tools/* 端点

### Java 端
2. `backend/src/.../service/MCPSandboxService.java`（新建）— sandbox tool 客户端
3. `backend/src/main/resources/application.yml` — 添加 mcp.sandbox 配置
4. `backend/src/.../service/SandboxTools.java`（新建）— Spring AI Function 定义
5. `backend/src/.../service/PlanningAgent.java` — 改造为支持 Function Calling
