@echo off
chcp 65001 >nul
echo ========================================
echo 停止所有 ChatBI 后端服务
echo ========================================
echo.

echo 正在查找并关闭服务...

REM 关闭占用 8001 端口的进程（意图识别）
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8001') do (
    echo 关闭意图识别服务 (PID: %%a)
    taskkill /F /PID %%a >nul 2>&1
)

REM 关闭占用 8002 端口的进程（实体识别）
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8002') do (
    echo 关闭实体识别服务 (PID: %%a)
    taskkill /F /PID %%a >nul 2>&1
)

REM 关闭占用 8003 端口的进程（沙箱服务）
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8003') do (
    echo 关闭沙箱服务 (PID: %%a)
    taskkill /F /PID %%a >nul 2>&1
)

REM 关闭占用 8080 端口的进程（后端）
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080') do (
    echo 关闭后端服务 (PID: %%a)
    taskkill /F /PID %%a >nul 2>&1
)

echo.
echo ========================================
echo 所有后端服务已停止
echo ========================================
pause
