#!/bin/bash

# ChatBI Backend 启动脚本（带代理配置）
# 用于在中国地区访问 Gemini 等国际 AI 模型

# 代理配置
PROXY_HOST="127.0.0.1"
PROXY_PORT="7890"

# 设置 JVM 代理参数
export JAVA_OPTS="-Dhttp.proxyHost=${PROXY_HOST} \
-Dhttp.proxyPort=${PROXY_PORT} \
-Dhttps.proxyHost=${PROXY_HOST} \
-Dhttps.proxyPort=${PROXY_PORT} \
-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.local"

echo "=========================================="
echo "ChatBI Backend 启动中..."
echo "代理配置: ${PROXY_HOST}:${PROXY_PORT}"
echo "=========================================="

# 启动 Spring Boot 应用
mvn spring-boot:run -Dspring-boot.run.jvmArguments="${JAVA_OPTS}"
