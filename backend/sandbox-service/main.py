from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
import asyncio
import logging
import uvicorn
import executor

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("sandbox-service")

app = FastAPI(title="ChatBI Sandbox Service", version="1.0.0")

# 允许跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ExecuteRequest(BaseModel):
    code: str
    data_json: str = None  # JSON 格式的数据
    timeout: int = 30      # 默认超时 30 秒

class ExecuteResponse(BaseModel):
    success: bool
    stdout: str
    stderr: str
    images: List[str]      # Base64 编码的图片列表

class PreviewRequest(BaseModel):
    code: str

class PreviewResponse(BaseModel):
    valid: bool
    errors: List[str]

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.post("/execute", response_model=ExecuteResponse)
async def execute_code(request: ExecuteRequest):
    logger.info(f"Received execution request. Code length: {len(request.code)}")
    try:
        result = await asyncio.to_thread(
            executor.execute_code,
            request.code,
            request.data_json,
            request.timeout
        )
        return ExecuteResponse(
            success=result.get("success", False),
            stdout=result.get("stdout", ""),
            stderr=result.get("stderr", ""),
            images=result.get("images", [])
        )
    except Exception as e:
        logger.error(f"Execution failed: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/preview", response_model=PreviewResponse)
async def preview_code(request: PreviewRequest):
    valid, errors = executor.validate_code(request.code)
    return PreviewResponse(valid=valid, errors=errors)


# ===== MCP Tool Endpoints =====

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


class ToolValidateRequest(BaseModel):
    code: str

@app.post("/tools/validate_code")
async def tool_validate_code(request: ToolValidateRequest):
    """MCP Tool: validate_code - 预检代码安全性"""
    valid, errors = executor.validate_code(request.code)
    return {"valid": valid, "errors": errors}


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


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8003, reload=True)
