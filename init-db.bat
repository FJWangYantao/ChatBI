@echo off
echo ====================================
echo ChatBI 对话管理数据库初始化脚本
echo ====================================
echo.

set DB_HOST=localhost
set DB_PORT=3306
set DB_USER=root
set DB_PASSWORD=123456

echo 正在连接到 MySQL...
echo.

mysql -h %DB_HOST% -P %DB_PORT% -u %DB_USER% -p%DB_PASSWORD% < backend\src\main\resources\sql\init-conversations-db.sql

if %errorlevel% equ 0 (
    echo.
    echo ====================================
    echo 数据库初始化成功！
    echo ====================================
    echo.
    echo 数据库名称: chatbi_conversations
    echo 已创建表:
    echo   - conversations (对话表)
    echo   - messages (消息表)
    echo   - query_history (查询历史表)
    echo.
) else (
    echo.
    echo ====================================
    echo 数据库初始化失败！
    echo ====================================
    echo.
    echo 请检查:
    echo 1. MySQL 是否已启动
    echo 2. 用户名和密码是否正确
    echo 3. 端口 %DB_PORT% 是否可访问
    echo.
)

pause
