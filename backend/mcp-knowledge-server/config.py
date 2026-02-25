"""
配置文件
"""
import os
from pathlib import Path

# 项目根目录
BASE_DIR = Path(__file__).parent

# 数据库配置
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{BASE_DIR}/data/knowledge.db")

# 服务配置
HOST = os.getenv("MCP_HOST", "0.0.0.0")
PORT = int(os.getenv("MCP_PORT", "8004"))

# 初始数据文件
INITIAL_DATA_FILE = BASE_DIR / "data" / "initial_terms.json"

# 日志配置
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
