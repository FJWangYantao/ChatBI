# Agent 公共层与动态编排重构设计

## 1. 背景

当前项目的 Agent 体系已经从单一分析流程演变为多能力混合系统，主要问题集中在以下几点：

- `PlanningAgent` 同时承担了 Prompt 组装、LLM 调用、流式解析、Tool Calling 循环、工具执行、状态输出、上下文裁剪等多种职责。
- `ChatStreamService` 同时承担请求入口、会话编排、Agent 路由、SSE 事件发送、流程状态管理等职责。
- 不同 Agent 普遍直接依赖 `DynamicChatClientFactory`、`ThreadLocal`、SSE 回调和工具 Bean，边界不清晰。
- Tool 和 Agent 的执行协议不统一，后续接入工作流类、知识库类、外部系统协同类 Agent 时，复杂度会持续上升。

如果继续在现有结构上叠加功能，会出现以下风险：

- 可维护性持续下降，大类越来越难改。
- 新增 Agent 需要复制大量基础逻辑。
- 流式输出、异常处理、超时控制、取消机制难以统一。
- 线上问题难以回放、定位和治理。

## 2. 目标

本次重构的目标是把当前 Agent 体系升级为一个“受约束的全动态编排平台”，同时满足以下要求：

- 提升可维护性：编排、能力、基础设施分层清晰。
- 提升可扩展性：后续可以接入分析类、知识类、工作流类、外部系统协同类 Agent。
- 提升稳定性：统一 LLM 调用、工具执行、事件输出、预算控制和错误处理。
- 保持现有功能可迁移：在重构过程中尽量不破坏现有分析链路。

## 3. 非目标

本阶段不追求以下事项：

- 不一次性重写所有 Agent。
- 不立即替换现有所有 SSE 协议。
- 不在第一阶段引入完整图编排引擎。
- 不把所有服务一次性迁出 `service` 包。
- 不要求第一阶段就完成所有旧逻辑删除。

## 4. 总体方案

采用“受约束的全动态编排”方案：

- LLM 负责决定下一步做什么。
- 后端只允许 LLM 在已注册的 Agent、Tool、Policy 范围内决策。
- 所有动作必须先转成结构化 `NextAction`。
- 所有动作必须经过 Guard 校验后才能执行。
- 编排主循环统一放在 Orchestrator，不允许具体 Agent 各自维护全局循环。

该方案的核心原则为：

> LLM 负责决策，后端负责约束和执行。

## 5. 分层设计

### 5.1 接入层

职责：

- 接收 HTTP / SSE 请求。
- 创建请求级上下文。
- 调用路由层和编排层。
- 负责会话生命周期接入。

当前对应：

- `ChatController`
- `ChatStreamService`

约束：

- 接入层不直接实现 Agent 编排逻辑。
- 接入层不直接处理 Tool Calling 循环。

### 5.2 路由层

职责：

- 判断请求属于哪个场景。
- 为请求选择合适的编排策略。

建议对象：

- `AgentRouter`
- `SceneType`

典型场景：

- `ANALYSIS`
- `KNOWLEDGE`
- `WORKFLOW`
- `HYBRID`
- `GENERAL_CHAT`

约束：

- 路由层只做分类，不负责具体执行。

### 5.3 编排层

职责：

- 驱动主循环。
- 请求 LLM 生成下一步动作。
- 校验动作是否合法。
- 调度 Tool / Agent / Workflow 执行。
- 更新执行状态。
- 决定停止条件。

建议对象：

- `AgentOrchestrator`
- `ActionDispatcher`
- `ActionGuard`
- `ExecutionState`

约束：

- 全局循环只能存在于编排层。
- 具体 Agent 不允许直接管理主循环。

### 5.4 Agent 能力层

职责：

- 封装单一能力节点。
- 接收结构化输入。
- 返回结构化结果。

建议对象：

- `AgentNode`
- `ActionResult`

典型节点：

- 分析类：`PlanNode`、`SqlNode`、`CodeExecutionNode`、`ReportNode`
- 知识类：`SchemaRetrieveNode`、`DocumentRetrieveNode`、`KnowledgeAnswerNode`
- 工作流类：`WorkflowPlanNode`、`ExternalDispatchNode`、`ApprovalNode`
- 会话类：`ClarificationNode`、`SuggestionNode`、`GeneralChatNode`

约束：

- Agent 只关心能力，不关心主流程。
- Agent 不直接操作 SSE。
- Agent 不直接维护工具注册表。
- Agent 不直接创建模型客户端。

### 5.5 LLM 公共层

职责：

- 统一模型访问。
- 统一流式和非流式调用。
- 统一 provider 差异。
- 统一 fallback、重试、超时和错误包装。

建议对象：

- `LLMGateway`
- `LLMRequest`
- `LLMResponse`
- `ModelSelector`

约束：

- 具体 Agent 不直接依赖 `DynamicChatClientFactory`。
- LLM 公共层是所有 Agent 的唯一模型访问入口。

### 5.6 Tool 公共层

职责：

- 统一注册工具。
- 统一参数协议。
- 统一工具执行和结果标准化。
- 统一危险动作治理和预算控制。

建议对象：

- `ToolDefinition`
- `ToolRegistry`
- `ToolExecutor`
- `ToolResult`

约束：

- Tool 不直接掌控编排流程。
- Agent 不自己维护 `toolMap`。

### 5.7 Event / Context 公共层

职责：

- 统一事件发布。
- 统一请求级上下文传递。
- 统一取消、超时、trace 信息。

建议对象：

- `AgentContext`
- `AgentEventPublisher`
- `CancelToken`
- `ExecutionTrace`

约束：

- 业务 Agent 不直接依赖 `SseEmitter`。
- 尽量收敛 `ThreadLocal` 的使用范围。

### 5.8 Adapter 层

职责：

- 封装数据库、MCP、Sandbox、外部系统等访问细节。
- 隔离外部协议和业务编排。

建议对象：

- `DatabaseAdapter`
- `SandboxAdapter`
- `McpKnowledgeAdapter`
- `HttpSystemAdapter`

约束：

- 编排层和 Agent 不直接承担第三方接入细节。

## 6. 核心对象设计

### 6.1 AgentContext

用途：承载请求级上下文。

建议字段：

- `requestId`
- `conversationId`
- `userMessage`
- `sceneType`
- `llmConfig`
- `eventPublisher`
- `cancelToken`
- `metadata`

设计约束：

- 以只读信息为主。
- 不在业务代码中扩散 ThreadLocal 依赖。

### 6.2 ExecutionState

用途：承载运行过程状态。

建议字段：

- `currentStep`
- `stepHistory`
- `variables`
- `artifacts`
- `toolBudget`
- `tokenBudget`
- `retryCount`
- `status`

设计约束：

- 属于编排层，不属于某个具体 Agent。
- 需要支持后续回放和恢复。

### 6.3 NextAction

用途：作为 LLM 决策输出的统一协议。

建议字段：

- `actionType`
- `target`
- `input`
- `reason`
- `expectedOutput`
- `priority`

建议动作类型：

- `CALL_TOOL`
- `CALL_AGENT`
- `RESPOND`
- `STOP`

### 6.4 ActionResult

用途：统一动作执行返回。

建议字段：

- `success`
- `resultType`
- `output`
- `artifacts`
- `error`
- `metrics`

### 6.5 AgentNode

用途：统一能力节点接口。

建议方法：

- `String name()`
- `boolean supports(SceneType sceneType)`
- `ActionResult execute(AgentContext context, ExecutionState state, Map<String, Object> input)`

### 6.6 ToolDefinition

用途：统一定义工具能力和约束。

建议字段：

- `name`
- `description`
- `inputSchema`
- `outputSchema`
- `timeout`
- `permissions`

## 7. 依赖规则

允许依赖：

- `controller -> service`
- `service -> router / orchestrator`
- `orchestrator -> agent api / llm / tool / event`
- `agent node -> agent api / llm / tool / adapter / event`
- `tool -> adapter`

禁止依赖：

- `agent -> SseEmitter`
- `agent -> DynamicChatClientFactory`
- `agent -> ThreadLocal`
- `agent -> toolMap`
- `tool -> orchestrator`
- `adapter -> orchestrator`

关键原则：

- 流程控制只在编排层。
- 外部系统访问只在 adapter 层。
- 事件输出只走 event 公共层。

## 8. 目录结构建议

```text
backend/src/main/java/com/chatbi/
  agent/
    api/
      AgentNode.java
      AgentRouter.java
      AgentOrchestrator.java
      KnowledgeProvider.java
      WorkflowAdapter.java

    context/
      AgentContext.java
      ExecutionState.java
      CancelToken.java
      ExecutionTrace.java

    model/
      NextAction.java
      ActionResult.java
      ActionType.java
      SceneType.java
      ExecutionStatus.java

    router/
      DefaultAgentRouter.java

    orchestrator/
      DefaultAgentOrchestrator.java
      ActionDispatcher.java
      ActionGuard.java

    llm/
      LLMGateway.java
      LLMRequest.java
      LLMResponse.java
      ModelSelector.java

    event/
      AgentEventPublisher.java
      SseAgentEventPublisher.java

    tool/
      ToolDefinition.java
      ToolRegistry.java
      ToolExecutor.java
      ToolResult.java
      tools/
        QueryDatabaseTool.java
        ExecuteCodeTool.java
        FixCodeTool.java
        ValidateCodeTool.java
        DispatchParallelTasksTool.java

    node/
      analysis/
        PlanNode.java
        SqlNode.java
        CodeExecutionNode.java
        ReportNode.java

      knowledge/
        SchemaRetrieveNode.java
        DocumentRetrieveNode.java
        KnowledgeAnswerNode.java

      workflow/
        WorkflowPlanNode.java
        ExternalDispatchNode.java
        ApprovalNode.java

      conversation/
        ClarificationNode.java
        SuggestionNode.java
        GeneralChatNode.java

    adapter/
      knowledge/
        McpKnowledgeAdapter.java

      external/
        SandboxAdapter.java
        DatabaseAdapter.java
        HttpSystemAdapter.java
```

## 9. 与现有代码的映射关系

### 9.1 PlanningAgent

现状问题：

- 同时承担编排、模型访问、工具执行、消息裁剪、状态映射、Prompt 构建等职责。

未来拆分方向：

- `PlanNode`
- `DefaultAgentOrchestrator`
- `LLMGateway`
- `ToolRegistry`
- `ToolExecutor`

迁移原则：

- 先保留外壳。
- 逐步把内部能力委托给公共层。
- 最终退化为 facade，或被 `PlanNode` 替代。

### 9.2 ChatStreamService

现状问题：

- 同时承担入口、意图路由、状态输出、SSE、分析流程调度等职责。

未来职责：

- 创建 `AgentContext`
- 调用 `AgentRouter`
- 调用 `AgentOrchestrator`
- 管理会话生命周期

### 9.3 DynamicChatClientFactory

未来定位：

- 逐步收敛到 `LLMGateway` 或 `ModelSelector`
- 不再作为各 Agent 直接依赖的入口

### 9.4 SandboxToolsConfig

未来定位：

- 逐步把工具定义拆到 `agent/tool/tools`
- 把外部系统细节迁到 `adapter`

## 10. 迁移策略

采用“保留外壳，逐步掏空”的迁移方式，不做一次性重写。

### 阶段 A：先定义公共层

目标：

- 明确公共对象、目录、依赖规则和职责边界。

产物：

- 本设计文档
- 一组基础接口和模型

### 阶段 B：落地最小公共层

目标：

- 先抽出最容易复用、风险最低的公共能力。

优先落地：

- `LLMGateway`
- `ToolRegistry`
- `ToolExecutor`
- `AgentEventPublisher`
- `AgentContext`

### 阶段 C：PlanningAgent 接入公共层

目标：

- 保持对外入口不变。
- 把 `PlanningAgent` 内部能力迁移到公共层。

原则：

- 先委托，不重写主流程。
- 先兼容，再替换。

### 阶段 D：引入 Orchestrator

目标：

- 把主循环和动作调度从 `PlanningAgent` 中剥离出来。

### 阶段 E：节点化改造

目标：

- 把现有 Agent 改造为标准 `AgentNode`。

优先迁移：

- `PlanningAgent`
- `Text2SQLAgent`
- `ReportAgent`
- `ClarificationAgent`
- `SuggestionAgent`

### 阶段 F：灰度替换旧链路

目标：

- 在不影响现有功能的情况下逐步切换到新编排架构。

建议：

- 使用配置开关控制新旧链路。
- 保留 fallback。

## 11. 稳定性设计

为避免动态编排失控，需要在公共层建立以下防护：

- `ActionGuard`：校验动作是否合法。
- `BudgetGuard`：限制工具调用次数、Token 消耗和执行时长。
- `LoopGuard`：限制循环轮数，防止死循环。
- `TimeoutPolicy`：限制单次动作超时。
- `RetryPolicy`：限制重试次数和适用范围。
- `ExecutionTrace`：记录每轮动作输入输出，便于排查。

## 12. 测试策略

本次重构测试分为三层：

### 12.1 公共层单元测试

覆盖对象：

- `ToolRegistry`
- `ToolExecutor`
- `LLMGateway`
- `ActionGuard`
- `AgentEventPublisher`

### 12.2 编排层流程测试

覆盖场景：

- 无 Tool 的直接响应
- 单轮 Tool 调用
- 多轮 Tool Calling
- 非法动作拒绝
- 超预算中止
- 客户端取消中止

### 12.3 旧链路烟雾测试

覆盖对象：

- `PlanningAgent`
- `ChatStreamService`

目标：

- 保证重构过程对外行为基本不变。

## 13. 第一批落地建议

第一批建议只做下面这些，不宜扩得太大：

- 新建 `agent/api`、`agent/model`、`agent/context`
- 新建 `agent/tool`
- 新建 `agent/llm`
- 新建 `agent/event`
- 暂时不改 Controller 层协议
- 暂时不改前端事件消费协议
- 暂时不删旧 Agent

## 14. 结论

本次重构不应以“拆大类”为起点，而应以“定义公共层”为起点。

正确顺序应为：

1. 先定义公共层的边界、对象、依赖规则。
2. 再落地最小公共层。
3. 再让 `PlanningAgent` 成为第一个接入公共层的旧模块。
4. 最后引入统一编排器并逐步节点化。

这样可以在不破坏现有功能的前提下，把项目从“面向单一分析流程的 Agent 实现”升级为“面向多类型 Agent 扩展的平台架构”。
