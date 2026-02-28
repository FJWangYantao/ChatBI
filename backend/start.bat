@echo off
chcp 65001 >nul
echo 启动 ChatBI 后端服务...
cd /d "%~dp0"

REM ===== 代理配置 =====
set PROXY_HOST=127.0.0.1
set PROXY_PORT=7890

mvn spring-boot:run -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8 "-Dspring-boot.run.jvmArguments=-Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT% -Dhttp.nonProxyHosts=localhost|127.0.0.1"
