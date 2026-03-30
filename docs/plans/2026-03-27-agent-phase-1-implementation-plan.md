# Agent Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 落地 Agent 公共层的第一批基础代码，先定义统一协议、边界和最小过渡实现，在不改变现有业务行为的前提下为后续迁移 `PlanningAgent` 和其他 Agent 做准备。

**Architecture:** 第一阶段只建设公共层，不改动现有主链路编排。新增 `agent` 包下的协议层、上下文层、工具层、事件层和 LLM 层最小骨架，通过接口、值对象和过渡适配器把边界先固定，再在后续阶段逐步让旧代码接入这些公共层。

**Tech Stack:** Java 17, Spring Boot 3.3, Spring AI, JUnit 5, Mockito, Maven

---

## 实施范围

本阶段只做以下事情：

- 新建 `backend/src/main/java/com/chatbi/agent/**` 下的第一批公共层类型。
- 为公共层补最小单元测试。
- 提供过渡适配器，但不切换现有主流程。
- 保持 `ChatController`、`ChatStreamService`、`PlanningAgent` 的对外行为不变。

本阶段不做以下事情：

- 不迁移 `PlanningAgent` 主循环。
- 不修改前端 SSE 协议。
- 不拆 `SandboxToolsConfig`。
- 不改现有路由逻辑。
- 不提交 commit。仓库规则要求未经允许不提交。

## 退出条件

完成本阶段时应满足：

- `com.chatbi.agent` 包下已经有可编译的公共层基础对象和接口。
- 工具注册、事件发布、LLM 网关存在最小实现与测试。
- 老代码仍可继续工作，且没有切到新公共层主路径。
- 后续阶段可以基于这些公共层开始迁移 `PlanningAgent`。

### Task 1: 建立公共层目录与核心动作模型

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/model/ActionType.java`
- Create: `backend/src/main/java/com/chatbi/agent/model/SceneType.java`
- Create: `backend/src/main/java/com/chatbi/agent/model/ExecutionStatus.java`
- Create: `backend/src/main/java/com/chatbi/agent/model/NextAction.java`
- Create: `backend/src/main/java/com/chatbi/agent/model/ActionResult.java`
- Test: `backend/src/test/java/com/chatbi/agent/model/NextActionTest.java`
- Test: `backend/src/test/java/com/chatbi/agent/model/ActionResultTest.java`

**Step 1: 写失败测试，固定动作协议**

```java
package com.chatbi.agent.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NextActionTest {

    @Test
    void shouldCreateToolActionWithStructuredFields() {
        NextAction action = NextAction.toolCall(
                "query_database",
                Map.of("sql", "select 1"),
                "need raw data",
                "query result"
        );

        assertThat(action.getActionType()).isEqualTo(ActionType.CALL_TOOL);
        assertThat(action.getTarget()).isEqualTo("query_database");
        assertThat(action.getInput()).containsEntry("sql", "select 1");
        assertThat(action.getReason()).isEqualTo("need raw data");
        assertThat(action.getExpectedOutput()).isEqualTo("query result");
    }
}
```

```java
package com.chatbi.agent.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionResultTest {

    @Test
    void shouldCreateSuccessfulResultWithArtifacts() {
        ActionResult result = ActionResult.success(
                "tool_result",
                Map.of("rows", 3),
                Map.of("dataRefId", "ref_1")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResultType()).isEqualTo("tool_result");
        assertThat(result.getOutput()).containsEntry("rows", 3);
        assertThat(result.getArtifacts()).containsEntry("dataRefId", "ref_1");
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=NextActionTest,ActionResultTest test`

Expected: FAIL，提示模型类不存在。

**Step 3: 写最小实现**

实现要求：

- `ActionType` 定义 `CALL_TOOL`、`CALL_AGENT`、`RESPOND`、`STOP`
- `SceneType` 定义 `ANALYSIS`、`KNOWLEDGE`、`WORKFLOW`、`HYBRID`、`GENERAL_CHAT`
- `ExecutionStatus` 定义 `PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`
- `NextAction` 提供静态工厂：`toolCall(...)`、`agentCall(...)`、`respond(...)`、`stop(...)`
- `ActionResult` 提供静态工厂：`success(...)`、`failure(...)`

最小实现示例：

```java
package com.chatbi.agent.model;

import lombok.Getter;

import java.util.Map;

@Getter
public class NextAction {
    private final ActionType actionType;
    private final String target;
    private final Map<String, Object> input;
    private final String reason;
    private final String expectedOutput;

    private NextAction(ActionType actionType, String target, Map<String, Object> input,
                       String reason, String expectedOutput) {
        this.actionType = actionType;
        this.target = target;
        this.input = input;
        this.reason = reason;
        this.expectedOutput = expectedOutput;
    }

    public static NextAction toolCall(String target, Map<String, Object> input,
                                      String reason, String expectedOutput) {
        return new NextAction(ActionType.CALL_TOOL, target, input, reason, expectedOutput);
    }
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=NextActionTest,ActionResultTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 记录这批模型类是公共协议，不允许引入 Spring 依赖。

### Task 2: 建立上下文层与执行状态模型

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/context/CancelToken.java`
- Create: `backend/src/main/java/com/chatbi/agent/context/ExecutionTrace.java`
- Create: `backend/src/main/java/com/chatbi/agent/context/ExecutionState.java`
- Create: `backend/src/main/java/com/chatbi/agent/context/AgentContext.java`
- Test: `backend/src/test/java/com/chatbi/agent/context/ExecutionStateTest.java`
- Test: `backend/src/test/java/com/chatbi/agent/context/AgentContextTest.java`

**Step 1: 写失败测试，固定上下文边界**

```java
package com.chatbi.agent.context;

import com.chatbi.agent.model.ExecutionStatus;
import com.chatbi.agent.model.SceneType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTest {

    @Test
    void shouldKeepRequestScopedMetadata() {
        CancelToken cancelToken = new CancelToken();
        AgentContext context = AgentContext.builder()
                .requestId("req-1")
                .conversationId("conv-1")
                .userMessage("分析最近销售趋势")
                .sceneType(SceneType.ANALYSIS)
                .cancelToken(cancelToken)
                .build();

        assertThat(context.getRequestId()).isEqualTo("req-1");
        assertThat(context.getSceneType()).isEqualTo(SceneType.ANALYSIS);
        assertThat(context.getCancelToken()).isSameAs(cancelToken);
    }
}
```

```java
package com.chatbi.agent.context;

import com.chatbi.agent.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStateTest {

    @Test
    void shouldTrackStatusAndBudgets() {
        ExecutionState state = ExecutionState.initial();
        state.setStatus(ExecutionStatus.RUNNING);
        state.setToolBudget(6);

        assertThat(state.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(state.getToolBudget()).isEqualTo(6);
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentContextTest,ExecutionStateTest test`

Expected: FAIL，提示上下文类不存在。

**Step 3: 写最小实现**

实现要求：

- `CancelToken` 提供 `cancel()` / `isCancelled()`
- `ExecutionTrace` 先只存 `List<String> entries`
- `ExecutionState` 提供 `initial()` 工厂方法和最小可变字段
- `AgentContext` 使用 builder，承载请求级只读信息

最小实现示例：

```java
package com.chatbi.agent.context;

import java.util.concurrent.atomic.AtomicBoolean;

public class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentContextTest,ExecutionStateTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 确认 `AgentContext` 不引入 `SseEmitter`、`ThreadLocal`、`ChatClient`。

### Task 3: 建立 Agent 协议接口与最小编排接口

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/api/AgentNode.java`
- Create: `backend/src/main/java/com/chatbi/agent/api/AgentRouter.java`
- Create: `backend/src/main/java/com/chatbi/agent/api/AgentOrchestrator.java`
- Create: `backend/src/main/java/com/chatbi/agent/orchestrator/ActionDispatcher.java`
- Create: `backend/src/main/java/com/chatbi/agent/orchestrator/ActionGuard.java`
- Test: `backend/src/test/java/com/chatbi/agent/api/AgentContractsTest.java`

**Step 1: 写失败测试，固定公共接口签名**

```java
package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.SceneType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContractsTest {

    @Test
    void shouldAllowNodeToDeclareSceneSupport() {
        AgentNode node = new AgentNode() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public boolean supports(SceneType sceneType) {
                return sceneType == SceneType.ANALYSIS;
            }

            @Override
            public ActionResult execute(AgentContext context, ExecutionState state, Map<String, Object> input) {
                return ActionResult.success("demo", Map.of(), Map.of());
            }
        };

        assertThat(node.supports(SceneType.ANALYSIS)).isTrue();
        assertThat(node.supports(SceneType.KNOWLEDGE)).isFalse();
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentContractsTest test`

Expected: FAIL，提示接口不存在。

**Step 3: 写最小实现**

实现要求：

- `AgentNode` 只定义单节点能力接口
- `AgentRouter` 只定义 `SceneType route(AgentContext context)`
- `AgentOrchestrator` 只定义 `ActionResult execute(AgentContext context)`
- `ActionDispatcher` 和 `ActionGuard` 只定义接口，不提供默认实现

最小实现示例：

```java
package com.chatbi.agent.api;

import com.chatbi.agent.context.AgentContext;
import com.chatbi.agent.context.ExecutionState;
import com.chatbi.agent.model.ActionResult;
import com.chatbi.agent.model.SceneType;

import java.util.Map;

public interface AgentNode {
    String name();
    boolean supports(SceneType sceneType);
    ActionResult execute(AgentContext context, ExecutionState state, Map<String, Object> input);
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentContractsTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 确认这些接口只依赖 `agent.context` 和 `agent.model`。

### Task 4: 建立 Tool 公共层与内存注册表实现

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/tool/ToolDefinition.java`
- Create: `backend/src/main/java/com/chatbi/agent/tool/ToolResult.java`
- Create: `backend/src/main/java/com/chatbi/agent/tool/ToolRegistry.java`
- Create: `backend/src/main/java/com/chatbi/agent/tool/ToolExecutor.java`
- Create: `backend/src/main/java/com/chatbi/agent/tool/InMemoryToolRegistry.java`
- Test: `backend/src/test/java/com/chatbi/agent/tool/InMemoryToolRegistryTest.java`

**Step 1: 写失败测试，固定工具注册行为**

```java
package com.chatbi.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryToolRegistryTest {

    @Test
    void shouldRegisterAndFindToolDefinition() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ToolDefinition tool = ToolDefinition.builder()
                .name("query_database")
                .description("query db")
                .timeout(Duration.ofSeconds(30))
                .build();

        registry.register(tool);

        assertThat(registry.find("query_database")).contains(tool);
        assertThat(registry.find("missing")).isEmpty();
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=InMemoryToolRegistryTest test`

Expected: FAIL，提示工具类不存在。

**Step 3: 写最小实现**

实现要求：

- `ToolDefinition` 只描述元数据，不直接绑定 Spring Bean
- `ToolRegistry` 提供 `register`、`find`、`list`
- `InMemoryToolRegistry` 用 `ConcurrentHashMap`
- `ToolExecutor` 先只定义接口，不做默认实现
- `ToolResult` 支持 `success(...)` 和 `failure(...)`

最小实现示例：

```java
package com.chatbi.agent.tool;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    void register(ToolDefinition definition);
    Optional<ToolDefinition> find(String name);
    Collection<ToolDefinition> list();
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=InMemoryToolRegistryTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 确认注册表不依赖 `FunctionCallback` 和 `SandboxToolsConfig`。

### Task 5: 建立 Event 公共层与空实现发布器

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/event/AgentEventPublisher.java`
- Create: `backend/src/main/java/com/chatbi/agent/event/NoopAgentEventPublisher.java`
- Create: `backend/src/main/java/com/chatbi/agent/event/AgentEvent.java`
- Test: `backend/src/test/java/com/chatbi/agent/event/NoopAgentEventPublisherTest.java`

**Step 1: 写失败测试，固定事件发布协议**

```java
package com.chatbi.agent.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoopAgentEventPublisherTest {

    @Test
    void shouldAcceptEventsWithoutSideEffects() {
        AgentEventPublisher publisher = new NoopAgentEventPublisher();
        AgentEvent event = AgentEvent.status("planning", "thinking");

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=NoopAgentEventPublisherTest test`

Expected: FAIL，提示事件类不存在。

**Step 3: 写最小实现**

实现要求：

- `AgentEvent` 先支持 `status`、`textDelta`、`done` 三种工厂方法
- `AgentEventPublisher` 只定义 `publish(AgentEvent event)`
- `NoopAgentEventPublisher` 空实现，便于公共层先独立落地

最小实现示例：

```java
package com.chatbi.agent.event;

public interface AgentEventPublisher {
    void publish(AgentEvent event);
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=NoopAgentEventPublisherTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 确认事件层不依赖 `SseEmitter`。

### Task 6: 建立 LLM 公共层接口与过渡适配器

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/llm/LLMRequest.java`
- Create: `backend/src/main/java/com/chatbi/agent/llm/LLMResponse.java`
- Create: `backend/src/main/java/com/chatbi/agent/llm/LLMGateway.java`
- Create: `backend/src/main/java/com/chatbi/agent/llm/ModelSelector.java`
- Create: `backend/src/main/java/com/chatbi/agent/llm/FactoryBackedLLMGateway.java`
- Test: `backend/src/test/java/com/chatbi/agent/llm/FactoryBackedLLMGatewayTest.java`

**Step 1: 写失败测试，固定过渡适配器边界**

```java
package com.chatbi.agent.llm;

import com.chatbi.factory.DynamicChatClientFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class FactoryBackedLLMGatewayTest {

    @Test
    void shouldDelegateClientCreationToExistingFactory() {
        DynamicChatClientFactory factory = Mockito.mock(DynamicChatClientFactory.class);
        ChatClient chatClient = Mockito.mock(ChatClient.class);
        when(factory.createChatClient("planning")).thenReturn(chatClient);

        FactoryBackedLLMGateway gateway = new FactoryBackedLLMGateway(factory);

        assertThat(gateway.getChatClient("planning")).isSameAs(chatClient);
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=FactoryBackedLLMGatewayTest test`

Expected: FAIL，提示 LLM 公共层类不存在。

**Step 3: 写最小实现**

实现要求：

- `LLMGateway` 暂时只暴露最小方法：`ChatClient getChatClient(String agentName)`
- `FactoryBackedLLMGateway` 只是过渡适配器，内部委托 `DynamicChatClientFactory`
- `LLMRequest`、`LLMResponse` 先作为预留值对象，不提前塞复杂逻辑
- `ModelSelector` 先只定义接口，暂不实现策略

最小实现示例：

```java
package com.chatbi.agent.llm;

import org.springframework.ai.chat.client.ChatClient;

public interface LLMGateway {
    ChatClient getChatClient(String agentName);
}
```

```java
package com.chatbi.agent.llm;

import com.chatbi.factory.DynamicChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;

public class FactoryBackedLLMGateway implements LLMGateway {
    private final DynamicChatClientFactory factory;

    public FactoryBackedLLMGateway(DynamicChatClientFactory factory) {
        this.factory = factory;
    }

    @Override
    public ChatClient getChatClient(String agentName) {
        return factory.createChatClient(agentName);
    }
}
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=FactoryBackedLLMGatewayTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 确认这是过渡实现，不允许在第一阶段引入 streaming/fallback 复杂逻辑。

### Task 7: 增加包级架构说明与阶段边界说明

**Files:**
- Create: `backend/src/main/java/com/chatbi/agent/package-info.java`
- Create: `backend/src/test/java/com/chatbi/agent/AgentPackageStructureSmokeTest.java`
- Modify: `docs/plans/2026-03-27-agent-architecture-design.md`

**Step 1: 写失败测试，固定包结构存在**

```java
package com.chatbi.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPackageStructureSmokeTest {

    @Test
    void shouldKeepAgentRootPackagePresent() {
        Package agentPackage = Package.getPackage("com.chatbi.agent");
        assertThat(agentPackage).isNotNull();
    }
}
```

**Step 2: 运行测试确认失败**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentPackageStructureSmokeTest test`

Expected: FAIL 或空包不可见。

**Step 3: 写最小实现**

实现要求：

- `package-info.java` 用注释明确第一阶段的依赖边界
- 在设计文档里补一段“Phase 1 只落公共层，不迁移业务行为”的实施说明

`package-info.java` 示例：

```java
/**
 * Agent 公共层根包。
 *
 * 第一阶段只允许放协议、上下文、工具、事件、LLM 最小公共对象，
 * 不允许放具体业务编排和旧链路迁移逻辑。
 */
package com.chatbi.agent;
```

**Step 4: 运行测试确认通过**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=AgentPackageStructureSmokeTest test`

Expected: PASS

**Step 5: 检查点**

- 不提交 commit。
- 设计文档与代码边界保持一致。

### Task 8: 运行第一阶段回归测试并确认未触碰旧链路

**Files:**
- Verify only: `backend/src/test/java/com/chatbi/service/PlanningAgentSummaryFormatPolicyTest.java`
- Verify only: `backend/src/test/java/com/chatbi/service/StreamingTextChunkerTest.java`
- Verify only: `backend/src/test/java/com/chatbi/agent/**`

**Step 1: 运行新增公共层测试**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=NextActionTest,ActionResultTest,AgentContextTest,ExecutionStateTest,AgentContractsTest,InMemoryToolRegistryTest,NoopAgentEventPublisherTest,FactoryBackedLLMGatewayTest,AgentPackageStructureSmokeTest test`

Expected: PASS

**Step 2: 运行现有后端烟雾测试**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -Dtest=PlanningAgentSummaryFormatPolicyTest,StreamingTextChunkerTest test`

Expected: PASS

**Step 3: 运行一次编译检查**

Run: `mvn "-Dmaven.repo.local=C:\Users\WYT\Desktop\ChatBI\backend\.m2" -DskipTests compile`

Expected: BUILD SUCCESS

**Step 4: 检查差异范围**

Run: `git diff --stat`

Expected: 只出现 `backend/src/main/java/com/chatbi/agent/**`、`backend/src/test/java/com/chatbi/agent/**` 和设计文档相关改动。

**Step 5: 检查点**

- 不提交 commit。
- 记录第一阶段完成状态，准备进入第二阶段：让 `PlanningAgent` 开始委托公共层。

## 第二阶段入口条件

只有在以下条件满足后，才进入第二阶段：

- 第一阶段新增测试全部通过。
- 现有后端烟雾测试通过。
- `agent` 公共层没有反向依赖旧业务类。
- 团队确认第一阶段公共边界没有明显缺项。

## 第二阶段预告

第二阶段再做这些：

- 引入 `PlanningAgent` 对 `LLMGateway`、`ToolRegistry`、`ToolExecutor` 的委托。
- 把 Prompt 构建、消息裁剪、状态映射等从 `PlanningAgent` 拆成可复用组件。
- 保留外部入口不变，开始“保留外壳，逐步掏空”迁移。
