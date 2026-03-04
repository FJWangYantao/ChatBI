@echo off
chcp 65001 >nul

SET PROXY_HOST=127.0.0.1
SET PROXY_PORT=7890

echo ==========================================
echo ChatBI Backend starting...
echo Proxy: %PROXY_HOST%:%PROXY_PORT%
echo ==========================================

mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dhttp.proxyHost=%PROXY_HOST% -Dhttp.proxyPort=%PROXY_PORT% -Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT% -Dhttp.nonProxyHosts=localhost|127.0.0.1|*.local"

if errorlevel 1 (
    echo.
    echo ==========================================
    echo Startup failed! Check error above.
    echo ==========================================
    pause
)
