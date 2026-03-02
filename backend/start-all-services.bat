@echo off
chcp 65001 >nul
echo ========================================
echo ChatBI 后端服务启动脚本
echo ========================================
echo.
echo 正在启动后端及四个 Python 服务...
echo.

cd /d "%~dp0"

REM 检查 Python 是否安装
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Python，请先安装 Python 3.8+
    pause
    exit /b 1
)

echo [1/4] 启动意图识别服务 (端口 8001)...
start /B "" cmd /c "cd /d %~dp0..\self_train_model && set PYTHONIOENCODING=utf-8 && python intent_service.py > nul 2>&1"
timeout /t 3 /nobreak >nul

echo [2/4] 启动实体识别服务 (端口 8002)...
start /B "" cmd /c "cd /d %~dp0..\self_train_model && set PYTHONIOENCODING=utf-8 && python ner_service.py > nul 2>&1"
timeout /t 3 /nobreak >nul

echo [3/4] 启动沙箱执行服务 (端口 8003)...
REM 沙箱服务使用虚拟环境，首次运行自动创建并安装依赖
start /B "" cmd /c "cd /d %~dp0sandbox-service && set PYTHONIOENCODING=utf-8 && (if not exist venv python -m venv venv && call venv\Scripts\activate && pip install -r requirements.txt) && call venv\Scripts\activate && python main.py > nul 2>&1"
timeout /t 3 /nobreak >nul

echo [4/5] 启动 MCP 知识库服务 (端口 8004)...
start /B "" cmd /c "cd /d %~dp0mcp-knowledge-server && set PYTHONIOENCODING=utf-8 && (if not exist venv python -m venv venv && call venv\Scripts\activate && pip install -r requirements.txt) && call venv\Scripts\activate && python server.py > nul 2>&1"
timeout /t 3 /nobreak >nul

REM ===== 代理配置 =====
set PROXY_HOST=127.0.0.1
set PROXY_PORT=7890

echo [5/5] 启动后端服务 (端口 8080)...
start "后端服务 - 8080" cmd /k "chcp 65001 >nul && cd /d %~dp0 && mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT% -Dhttp.nonProxyHosts=localhost^|127.0.0.1""

echo.
echo ========================================
echo 所有后端服务已在独立窗口中启动！
echo ========================================
echo 意图识别: http://localhost:8001
echo 实体识别: http://localhost:8002
echo 沙箱服务: http://localhost:8003
echo 知识库:   http://localhost:8004
echo 后端 API:  http://localhost:8080/api
echo ========================================
echo.
echo 请查看各服务窗口确认启动状态
echo 关闭此窗口不会影响已启动的服务
echo.
pause
