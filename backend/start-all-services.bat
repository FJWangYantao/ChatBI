@echo off
chcp 65001 >nul
echo ========================================
echo ChatBI 后端服务启动脚本
echo ========================================
echo.
echo 正在启动后端及三个 Python 服务...
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
start "意图识别服务 - 8001" cmd /k "cd /d %~dp0..\self_train_model && set PYTHONIOENCODING=utf-8 && python intent_service.py"
ping 127.0.0.1 -n 3 >nul

echo [2/4] 启动实体识别服务 (端口 8002)...
start "实体识别服务 - 8002" cmd /k "cd /d %~dp0..\self_train_model && set PYTHONIOENCODING=utf-8 && python ner_service.py"
ping 127.0.0.1 -n 3 >nul

echo [3/4] 启动沙箱执行服务 (端口 8003)...
REM 沙箱服务使用虚拟环境，首次运行自动创建并安装依赖
start "沙箱服务 - 8003" cmd /k "cd /d %~dp0sandbox-service && set PYTHONIOENCODING=utf-8 && (if not exist venv python -m venv venv && call venv\Scripts\activate && pip install -r requirements.txt) && call venv\Scripts\activate && python main.py"
ping 127.0.0.1 -n 3 >nul

echo [4/4] 启动后端服务 (端口 8080)...
start "后端服务 - 8080" cmd /k "chcp 65001 >nul && cd /d %~dp0 && mvn spring-boot:run"

echo.
echo ========================================
echo 所有后端服务已在独立窗口中启动！
echo ========================================
echo 意图识别: http://localhost:8001
echo 实体识别: http://localhost:8002
echo 沙箱服务: http://localhost:8003
echo 后端 API:  http://localhost:8080/api
echo ========================================
echo.
echo 请查看各服务窗口确认启动状态
echo 关闭此窗口不会影响已启动的服务
echo.
pause
