@echo off
REM Intent Recognition Service Startup Script
REM 意图识别服务启动脚本

echo ============================================
echo Intent Recognition Service
echo ============================================
echo.

REM 设置项目根目录
cd /d "%~dp0"
set PROJECT_ROOT=%cd%

echo Project Root: %PROJECT_ROOT%
echo.

REM 检查 Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Please install Python 3.8+
    pause
    exit /b 1
)

echo [OK] Python found
echo.

REM 检查模型文件
set MODEL_PATH=%PROJECT_ROOT%\..\..\self_train_model\best_intent_model.pt
if not exist "%MODEL_PATH%" (
    echo [ERROR] Model file not found: %MODEL_PATH%
    echo Please train the model first.
    pause
    exit /b 1
)

echo [OK] Model file found
echo.

REM 安装依赖
echo Checking dependencies...
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
if errorlevel 1 (
    echo [WARNING] Dependency installation had issues, trying to continue...
)

echo.
echo ============================================
echo Starting Intent Recognition Service...
echo ============================================
echo.

REM 启动服务
python intent_api.py

pause
