@echo off
echo ========================================
echo  NER 命名实体识别服务启动
echo ========================================
echo.

REM 安装依赖
echo 安装依赖...
pip install fastapi uvicorn torch transformers pytorch-crf -q

echo.
echo 启动 NER 服务 (端口 8002)...
echo.

python ner_api.py
