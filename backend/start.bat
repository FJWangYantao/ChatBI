@echo off
chcp 65001 >nul
echo 启动 ChatBI 后端服务...
cd /d "%~dp0"
mvn spring-boot:run -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8
