@echo off
cd /d %~dp0

if not exist venv (
    echo Creating virtual environment...
    python -m venv venv
)

call venv\Scripts\activate

echo Installing dependencies...
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

echo Starting Sandbox Service on port 8003...
uvicorn main:app --host 0.0.0.0 --port 8003 --reload
pause
