@echo off
REM ============================================
REM ChatBI 后端启动脚本（带环境变量）
REM ============================================

echo ============================================
echo ChatBI Backend Startup
echo ============================================
echo.

REM 设置环境变量
set DEEPSEEK_API_KEY=你的DeepSeek_API_Key

REM 数据库配置（可选，默认值已在 application.yml 中设置）
set DB_HOST=localhost
set DB_PORT=3306
set DB_USERNAME=root
set DB_PASSWORD=123456

REM 数据库名称
set DB_NAME=northwind
set CONV_DB_NAME=chatbi_conversations
set CONFIG_DB_NAME=chatbi_config

echo [INFO] Environment variables set:
echo   DEEPSEEK_API_KEY: %DEEPSEEK_API_KEY:~0,10%...
echo   DB_HOST: %DB_HOST%
echo   DB_NAME: %DB_NAME%
echo.

REM 切换到 backend 目录
cd /d "%~dp0"

echo [INFO] Starting Spring Boot application...
echo.

REM ===== 代理配置 =====
set PROXY_HOST=127.0.0.1
set PROXY_PORT=7890

REM 启动 Spring Boot
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT% -Dhttp.nonProxyHosts=localhost|127.0.0.1"

pause
