# Sandbox MCP Service Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wrap the existing sandbox-service as MCP-style tools so PlanningAgent can invoke code execution via LLM Function Calling.

**Architecture:** Add `/tools/*` REST endpoints to the Python sandbox-service, create a Java client (`MCPSandboxService`), register Spring AI `FunctionCallback` beans, and wire them into PlanningAgent's ChatClient so the LLM can autonomously decide when to execute code.

**Tech Stack:** Python FastAPI, Java 17, Spring Boot 3.3.0, Spring AI 1.0.0-M4 (OpenAI-compatible with DeepSeek)

---

### Task 1: Add MCP tool endpoints to Python sandbox-service

**Files:**
- Modify: `backend/sandbox-service/main.py`
- Reference: `backend/sandbox-service/executor.py` (read-only)
- Reference: `backend/sandbox-service/security_config.py` (read-only)

**Step 1: Add `/tools/execute_code` endpoint**

Add after the existing `/preview` endpoint in `main.py`:

```python
class ToolExecuteRequest(BaseModel):
    code: str
    data_json: str = None
    timeout: int = 30

@app.post("/tools/execute_code")
async def tool_execute_code(request: ToolExecuteRequest):
    """MCP Tool: execute_code - 在安全沙盒中执行 Python 数据分析代码"""
    logger.info(f"[MCP Tool] execute_code called. Code length: {len(request.code)}")
    try:
        result = await asyncio.to_thread(
            executor.execute_code,
            request.code,
            request.data_json,
            request.timeout
        )
        return {
            "success": result.get("success", False),
            "stdout": result.get("stdout", ""),
            "stderr": result.get("stderr", ""),
            "images": result.get("images", [])
        }
    except Exception as e:
        logger.error(f"[MCP Tool] execute_code failed: {str(e)}")
        return {"success": False, "stdout": "", "stderr": str(e), "images": []}
```

**Step 2: Add `/tools/validate_code` endpoint**

```python
class ToolValidateRequest(BaseModel):
    code: str

@app.post("/tools/validate_code")
async def tool_validate_code(request: ToolValidateRequest):
    """MCP Tool: validate_code - 预检代码安全性"""
    valid, errors = executor.validate_code(request.code)
    return {"valid": valid, "errors": errors}
```

**Step 3: Add `/tools/sandbox_info` endpoint**

```python
from security_config import SAFE_MODULES, SAFE_BUILTINS

@app.get("/tools/sandbox_info")
async def tool_sandbox_info():
    """MCP Tool: sandbox_info - 查询沙盒环境信息"""
    return {
        "status": "running",
        "allowed_modules": sorted(list(SAFE_MODULES)),
        "allowed_builtins": sorted(list(SAFE_BUILTINS)),
        "default_timeout": 30,
        "max_timeout": 120,
        "features": ["pandas_dataframe_input", "matplotlib_chart_output", "base64_image_capture"]
    }
```

**Step 4: Verify endpoints work**

Run: `curl http://localhost:8003/tools/sandbox_info`
Expected: JSON with allowed_modules, status="running"

Run: `curl -X POST http://localhost:8003/tools/validate_code -H "Content-Type: application/json" -d '{"code": "print(1+1)"}'`
Expected: `{"valid": true, "errors": []}`

Run: `curl -X POST http://localhost:8003/tools/execute_code -H "Content-Type: application/json" -d '{"code": "print(1+1)"}'`
Expected: `{"success": true, "stdout": "2\n", "stderr": "", "images": []}`

**Step 5: Commit**

```bash
git add backend/sandbox-service/main.py
git commit -m "feat(sandbox): add MCP tool endpoints for execute_code, validate_code, sandbox_info"
```

---

### Task 2: Add MCP sandbox config to application.yml

**Files:**
- Modify: `backend/src/main/resources/application.yml:117-124` (after existing mcp.knowledge section)

**Step 1: Add sandbox config under existing mcp section**

Add after line 124 (`timeout: ${MCP_KNOWLEDGE_TIMEOUT:2000}`):

```yaml
  sandbox:
    server:
      url: ${MCP_SANDBOX_SERVER_URL:http://localhost:8003}
    enabled: ${MCP_SANDBOX_ENABLED:true}
    timeout: ${MCP_SANDBOX_TIMEOUT:35000}
```

This goes inside the existing `mcp:` block, as a sibling to `knowledge:`.

**Step 2: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "feat(config): add MCP sandbox service configuration"
```

---

### Task 3: Create MCPSandboxService Java client

**Files:**
- Create: `backend/src/main/java/com/chatbi/service/MCPSandboxService.java`
- Reference: `backend/src/main/java/com/chatbi/service/MCPKnowledgeService.java` (pattern to follow)

**Step 1: Create MCPSandboxService.java**

Follow the same pattern as `MCPKnowledgeService.java`:

```java
package com.chatbi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MCPSandboxService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mcp.sandbox.server.url:http://localhost:8003}")
    private String sandboxServerUrl;

    @Value("${mcp.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    public MCPSandboxService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 Python 代码
     */
    public Map<String, Object> executeCode(String code, String dataJson, int timeout) {
        if (!sandboxEnabled) {
            return Map.of("success", false, "stderr", "MCP Sandbox 服务未启用");
        }

        try {
            String url = sandboxServerUrl + "/tools/execute_code";

            Map<String, Object> request = new HashMap<>();
            request.put("code", code);
            if (dataJson != null) request.put("data_json", dataJson);
            request.put("timeout", timeout);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            log.info("[MCPSandbox] Executing code, length={}", code.length());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("success", false, "stderr", "Sandbox 服务返回异常");

        } catch (Exception e) {
            log.error("[MCPSandbox] 执行代码失败: {}", e.getMessage());
            return Map.of("success", false, "stderr", "Sandbox 连接失败: " + e.getMessage());
        }
    }

    /**
     * 预检代码安全性
     */
    public Map<String, Object> validateCode(String code) {
        if (!sandboxEnabled) {
            return Map.of("valid", false, "errors", List.of("MCP Sandbox 服务未启用"));
        }

        try {
            String url = sandboxServerUrl + "/tools/validate_code";
            Map<String, String> request = Map.of("code", code);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("valid", false, "errors", List.of("验证请求失败"));

        } catch (Exception e) {
            log.error("[MCPSandbox] 代码验证失败: {}", e.getMessage());
            return Map.of("valid", false, "errors", List.of(e.getMessage()));
        }
    }

    /**
     * 获取沙盒环境信息
     */
    public Map<String, Object> getSandboxInfo() {
        if (!sandboxEnabled) {
            return Map.of("status", "disabled");
        }

        try {
            String url = sandboxServerUrl + "/tools/sandbox_info";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return Map.of("status", "unavailable");

        } catch (Exception e) {
            log.error("[MCPSandbox] 获取沙盒信息失败: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 健康检查
     */
    public boolean isHealthy() {
        if (!sandboxEnabled) return false;
        try {
            String url = sandboxServerUrl + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/chatbi/service/MCPSandboxService.java
git commit -m "feat(sandbox): add MCPSandboxService Java client"
```

---

### Task 4: Create Spring AI Function callbacks for sandbox tools

**Files:**
- Create: `backend/src/main/java/com/chatbi/config/SandboxToolsConfig.java`
- Reference: Spring AI FunctionCallback API

**Step 1: Create SandboxToolsConfig.java**

This registers the sandbox tools as Spring AI `FunctionCallback` beans so they can be used with ChatClient's `.functions()` method.

```java
package com.chatbi.config;

import com.chatbi.service.MCPSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.Function;

@Slf4j
@Configuration
public class SandboxToolsConfig {

    @Bean("executeCodeFunction")
    public FunctionCallback executeCodeFunction(MCPSandboxService sandboxService) {
        return FunctionCallbackWrapper.builder(
                (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    String code = (String) params.get("code");
                    String dataJson = (String) params.getOrDefault("data_json", null);
                    int timeout = params.containsKey("timeout")
                            ? ((Number) params.get("timeout")).intValue()
                            : 30;
                    log.info("[SandboxTool] execute_code called, code length={}", code != null ? code.length() : 0);
                    return sandboxService.executeCode(code, dataJson, timeout);
                })
                .withName("execute_code")
                .withDescription("在安全沙盒中执行 Python 数据分析代码。数据通过 data_json 参数传入，会自动加载为 pandas DataFrame（变量名 df）。支持 matplotlib 绘图，图表以 base64 图片返回。仅允许导入: pandas, numpy, matplotlib, seaborn, sklearn, scipy, json, re, math, datetime, collections, itertools, functools, io, base64。")
                .withInputType(Map.class)
                .build();
    }

    @Bean("validateCodeFunction")
    public FunctionCallback validateCodeFunction(MCPSandboxService sandboxService) {
        return FunctionCallbackWrapper.builder(
                (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    String code = (String) params.get("code");
                    return sandboxService.validateCode(code);
                })
                .withName("validate_code")
                .withDescription("预检 Python 代码的安全性，不实际执行。返回代码是否通过安全检查以及具体的错误列表。在执行代码前可以先调用此工具检查。")
                .withInputType(Map.class)
                .build();
    }

    @Bean("sandboxInfoFunction")
    public FunctionCallback sandboxInfoFunction(MCPSandboxService sandboxService) {
        return FunctionCallbackWrapper.builder(
                (Function<Map<String, Object>, Map<String, Object>>) params -> {
                    return sandboxService.getSandboxInfo();
                })
                .withName("sandbox_info")
                .withDescription("查询沙盒环境信息，包括可用的 Python 模块列表、内置函数白名单、超时限制等。在生成代码前可以调用此工具了解沙盒的能力边界。")
                .withInputType(Map.class)
                .build();
    }
}
```

**Important note:** The Spring AI `FunctionCallbackWrapper` API may differ slightly between versions. The project uses `spring-ai-openai` 1.0.0-M4. If the `FunctionCallbackWrapper.builder()` API doesn't match, check the actual Spring AI version's API and adjust. An alternative approach is to use `@Description` annotated Java functions or implement `FunctionCallback` directly.

**Step 2: Commit**

```bash
git add backend/src/main/java/com/chatbi/config/SandboxToolsConfig.java
git commit -m "feat(sandbox): register sandbox tools as Spring AI FunctionCallbacks"
```

---

### Task 5: Wire Function Calling into PlanningAgent

**Files:**
- Modify: `backend/src/main/java/com/chatbi/service/PlanningAgent.java`

**Step 1: Add FunctionCallback injection**

Add to constructor parameters and fields:

```java
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Qualifier;

// Add field
private final FunctionCallback executeCodeFunction;
private final FunctionCallback validateCodeFunction;
private final FunctionCallback sandboxInfoFunction;

// Update constructor
public PlanningAgent(ChatClient.Builder chatClientBuilder,
                     ModelOptionsProvider modelOptions,
                     NERService nerService,
                     ReadSchemaStructureService schemaService,
                     @Qualifier("executeCodeFunction") FunctionCallback executeCodeFunction,
                     @Qualifier("validateCodeFunction") FunctionCallback validateCodeFunction,
                     @Qualifier("sandboxInfoFunction") FunctionCallback sandboxInfoFunction) {
    this.chatClient = chatClientBuilder.build();
    this.modelOptions = modelOptions;
    this.nerService = nerService;
    this.schemaService = schemaService;
    this.executeCodeFunction = executeCodeFunction;
    this.validateCodeFunction = validateCodeFunction;
    this.sandboxInfoFunction = sandboxInfoFunction;
    this.objectMapper = new ObjectMapper();
}
```

**Step 2: Add a new method for function-calling-enabled planning**

Add a new method `planWithTools` that uses Function Calling. Keep the existing `plan()` method unchanged for backward compatibility:

```java
/**
 * 使用 Function Calling 的增强规划
 * LLM 可以自主决定是否调用沙盒执行代码
 */
public String planWithTools(String question) {
    log.info("[PlanningAgent] Planning with tools for: {}", question);

    String schemaInfo = schemaService.getDatabaseSchema().getFormattedForAI();
    NERResponse nerResponse = nerService.extractEntities(question);
    String entitiesStr = nerResponse.getEntities().stream()
            .map(e -> e.getText() + "(" + e.getType() + ")")
            .collect(Collectors.joining(", "));

    String prompt = String.format("""
        你是一个数据分析规划专家，拥有以下工具：
        1. execute_code: 在安全沙盒中执行 Python 代码进行数据分析
        2. validate_code: 预检代码安全性
        3. sandbox_info: 查询沙盒环境能力

        用户问题：%s
        识别到的实体：%s
        数据库结构：
        %s

        请分析用户问题，如果需要执行代码来回答，请使用 execute_code 工具。
        如果不确定代码是否安全，先用 validate_code 检查。
        """, question, entitiesStr, schemaInfo);

    String response = chatClient.prompt()
            .options(modelOptions.getOptions("planning"))
            .user(prompt)
            .functions(executeCodeFunction, validateCodeFunction, sandboxInfoFunction)
            .call()
            .content();

    log.info("[PlanningAgent] Tool-enabled planning complete");
    return response;
}
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/chatbi/service/PlanningAgent.java
git commit -m "feat(planning): add planWithTools method with sandbox Function Calling"
```

---

### Task 6: Integration test - end to end verification

**Step 1: Start sandbox-service**

Ensure sandbox-service is running on port 8003. Verify the new endpoints:

```bash
curl http://localhost:8003/tools/sandbox_info
curl -X POST http://localhost:8003/tools/validate_code -H "Content-Type: application/json" -d '{"code": "import pandas as pd\nprint(pd.__version__)"}'
curl -X POST http://localhost:8003/tools/execute_code -H "Content-Type: application/json" -d '{"code": "print(2+2)"}'
```

**Step 2: Start Java backend**

Build and start the Spring Boot application. Check logs for:
- `MCPSandboxService` bean creation
- `SandboxToolsConfig` function registration
- No startup errors related to new beans

```bash
cd backend && mvn spring-boot:run
```

**Step 3: Test Function Calling via PlanningAgent**

Call the `planWithTools` endpoint (or test via the existing chat flow if wired up). Verify:
- LLM receives the tool descriptions
- LLM can choose to call `execute_code`
- Results are returned correctly

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat(sandbox-mcp): complete MCP sandbox service integration"
```

---

## Notes

- **Backward compatibility**: The existing `plan()` method and `CodeAgent` flow are untouched. `planWithTools()` is additive.
- **Spring AI FunctionCallback API**: The exact API depends on Spring AI 1.0.0-M4. If `FunctionCallbackWrapper.builder()` doesn't exist, use `FunctionCallbackWrapper.builder(Function)` or implement `FunctionCallback` interface directly. Check `spring-ai-core` source for the exact API.
- **ChatService integration**: Task 5 only adds `planWithTools()` to PlanningAgent. To actually use it in the main flow, ChatService's `handleDataAnalysisWithAgents()` would need to call `planWithTools()` instead of or in addition to `plan()`. This is left as a follow-up decision for the user.
- **Security**: The sandbox already has AST-based validation and process isolation. The MCP layer doesn't change the security model.
