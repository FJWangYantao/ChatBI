"""
意图识别 FastAPI 服务
提供 REST API 接口进行意图识别
"""

import os
import asyncio
import logging
from typing import List, Optional
from contextlib import asynccontextmanager

import torch
import uvicorn
from fastapi import FastAPI, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
from transformers import BertModel, BertTokenizer

# 设置 HF 镜像（国内用户）
os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ============ 配置 ============
import sys
import os

# 获取脚本所在目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# 模型文件在项目根目录的 self_train_model 下
MODEL_PATH = os.path.join(SCRIPT_DIR, "..", "..", "self_train_model", "best_intent_model.pt")

CONFIG = {
    "MODEL_PATH": os.path.abspath(MODEL_PATH),
    "MODEL_NAME": "hfl/chinese-bert-wwm-ext",
    "DEVICE": "cuda" if torch.cuda.is_available() else "cpu",
    "MAX_LENGTH": 128,
    "HOST": "0.0.0.0",
    "PORT": 8001
}

# ============ 模型定义 ============
class IntentClassifier(torch.nn.Module):
    """意图识别模型"""
    def __init__(self, model_name, num_categories=4, num_subtypes=15, dropout=0.1):
        super().__init__()
        self.bert = BertModel.from_pretrained(model_name)
        self.dropout = torch.nn.Dropout(dropout)
        hidden_size = self.bert.config.hidden_size

        self.category_classifier = torch.nn.Sequential(
            torch.nn.Linear(hidden_size, 256),
            torch.nn.ReLU(),
            torch.nn.Dropout(dropout),
            torch.nn.Linear(256, num_categories)
        )

        self.subtype_classifier = torch.nn.Sequential(
            torch.nn.Linear(hidden_size, 256),
            torch.nn.ReLU(),
            torch.nn.Dropout(dropout),
            torch.nn.Linear(256, num_subtypes)
        )

    def forward(self, input_ids, attention_mask):
        outputs = self.bert(input_ids=input_ids, attention_mask=attention_mask)
        pooled_output = outputs.pooler_output
        pooled_output = self.dropout(pooled_output)
        category_logits = self.category_classifier(pooled_output)
        subtype_logits = self.subtype_classifier(pooled_output)
        return category_logits, subtype_logits


# ============ 全局模型实例 ============
model = None
tokenizer = None
device = None

# 分类映射 — 必须与训练脚本 (train_intent_model.py) 中的 category2id / subtype2id 一致
ID2CATEGORY = {
    0: "DATA_QUERY",
    1: "GENERAL_CHAT",
    2: "HYBRID",
    3: "DATA_OPERATION"
}

ID2SUBTYPE = {
    0: "AGGREGATION_SUM",
    1: "AGGREGATION_COUNT",
    2: "AGGREGATION_AVG",
    3: "AGGREGATION_MAX_MIN",
    4: "DETAIL_LIST",
    5: "DETAIL_SINGLE",
    6: "DETAIL_SEARCH",
    7: "TREND_ANALYSIS",
    8: "COMPARISON_ANALYSIS",
    9: "RANKING_ANALYSIS",
    10: "DISTRIBUTION_ANALYSIS",
    11: "JOIN_QUERY",
    12: "SUB_QUERY",
    13: "METADATA_QUERY",
    14: "ROOT_CAUSE_ANALYSIS",
    15: "CREATE_OPERATION",
    16: "UPDATE_OPERATION",
    17: "DELETE_OPERATION",
    18: "EXPORT_OPERATION",
    19: "HYBRID_QUERY",
    20: "CHAT",
}

# 默认回退值（模型输出超出已知映射时使用）
_DEFAULT_CATEGORY = "GENERAL_CHAT"
_DEFAULT_SUBTYPE = "CHAT"


# ============ 生命周期管理 ============
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    global model, tokenizer, device

    # 启动时加载模型
    logger.info("=" * 60)
    logger.info("意图识别服务启动中...")
    logger.info("=" * 60)

    device = torch.device(CONFIG["DEVICE"])
    logger.info(f"使用设备: {device}")

    try:
        # 加载 Tokenizer
        logger.info(f"加载 Tokenizer: {CONFIG['MODEL_NAME']}")
        tokenizer = BertTokenizer.from_pretrained(CONFIG["MODEL_NAME"])
        logger.info("[OK] Tokenizer 加载成功")

        # 加载模型
        logger.info(f"加载模型: {CONFIG['MODEL_PATH']}")
        checkpoint = torch.load(CONFIG['MODEL_PATH'], map_location=device)

        model = IntentClassifier(
            model_name=CONFIG['MODEL_NAME'],
            num_categories=checkpoint['config'].get('num_categories', 4),
            num_subtypes=checkpoint['config'].get('num_subtypes', 15)
        ).to(device)

        model.load_state_dict(checkpoint['model_state_dict'], strict=False)
        model.eval()

        logger.info("[OK] 模型加载成功")
        if 'category_f1' in checkpoint:
            logger.info(f"  最佳 F1: {checkpoint['category_f1']:.4f}")

        logger.info("=" * 60)
        logger.info(f"服务启动完成，监听 {CONFIG['HOST']}:{CONFIG['PORT']}")
        logger.info("=" * 60)

    except Exception as e:
        logger.error(f"[ERROR] 模型加载失败: {e}")
        raise

    yield

    # 关闭时清理资源
    logger.info("意图识别服务关闭中...")
    del model
    del tokenizer
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    logger.info("[OK] 服务已关闭")


# ============ FastAPI 应用 ============
app = FastAPI(
    title="Intent Recognition Service",
    description="意图识别微服务 - 基于 BERT 的中文意图分类",
    version="1.0.0",
    lifespan=lifespan
)


# ============ 请求/响应模型 ============
class PredictRequest(BaseModel):
    """预测请求"""
    text: str = Field(..., description="待识别的文本", min_length=1, max_length=500)
    return_probs: bool = Field(False, description="是否返回概率分布")


class PredictResponse(BaseModel):
    """预测响应"""
    text: str
    category: str
    category_cn: str
    category_confidence: float
    subtype: str
    subtype_cn: str
    subtype_confidence: float
    category_probs: Optional[dict] = None
    subtype_probs: Optional[dict] = None


class BatchPredictRequest(BaseModel):
    """批量预测请求"""
    texts: List[str] = Field(..., description="待识别的文本列表", min_length=1, max_length=50)
    return_probs: bool = Field(False, description="是否返回概率分布")


class BatchPredictResponse(BaseModel):
    """批量预测响应"""
    results: List[PredictResponse]


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str
    model_loaded: bool
    device: str


# 中文描述映射
CATEGORY_CN = {
    "DATA_QUERY": "数据查询",
    "GENERAL_CHAT": "普通对话",
    "HYBRID": "混合查询",
    "DATA_OPERATION": "数据操作",
    "CHAT": "普通对话"
}

SUBTYPE_CN = {
    "AGGREGATION_SUM": "求和统计",
    "AGGREGATION_COUNT": "计数统计",
    "AGGREGATION_AVG": "平均值统计",
    "AGGREGATION_MAX_MIN": "最大最小值",
    "DETAIL_LIST": "明细列表",
    "DETAIL_SINGLE": "单条明细",
    "DETAIL_SEARCH": "明细搜索",
    "TREND_ANALYSIS": "趋势分析",
    "COMPARISON_ANALYSIS": "对比分析",
    "RANKING_ANALYSIS": "排名分析",
    "DISTRIBUTION_ANALYSIS": "分布分析",
    "JOIN_QUERY": "关联查询",
    "SUB_QUERY": "子查询",
    "METADATA_QUERY": "元数据查询",
    "ROOT_CAUSE_ANALYSIS": "根因分析",
    "CREATE_OPERATION": "创建操作",
    "UPDATE_OPERATION": "更新操作",
    "DELETE_OPERATION": "删除操作",
    "EXPORT_OPERATION": "导出操作",
    "HYBRID_QUERY": "混合查询",
    "CHAT": "普通对话"
}


# ============ 核心推理函数 ============
def predict_single(text: str, return_probs: bool = False) -> dict:
    """预测单个文本"""
    if model is None or tokenizer is None:
        raise RuntimeError("模型未加载")

    # Tokenize
    encoding = tokenizer(
        text,
        max_length=CONFIG["MAX_LENGTH"],
        padding='max_length',
        truncation=True,
        return_tensors='pt'
    )

    input_ids = encoding['input_ids'].to(device)
    attention_mask = encoding['attention_mask'].to(device)

    # 预测
    with torch.no_grad():
        category_logits, subtype_logits = model(input_ids, attention_mask)

    # 获取预测结果
    category_probs = torch.softmax(category_logits, dim=1)[0]
    subtype_probs = torch.softmax(subtype_logits, dim=1)[0]

    category_id = category_probs.argmax().item()
    subtype_id = subtype_probs.argmax().item()
    category_conf = category_probs[category_id].item()
    subtype_conf = subtype_probs[subtype_id].item()

    category_name = ID2CATEGORY.get(category_id, _DEFAULT_CATEGORY)
    subtype_name = ID2SUBTYPE.get(subtype_id, _DEFAULT_SUBTYPE)

    if category_id not in ID2CATEGORY:
        logger.warning(f"未知 category_id={category_id}, 回退为 {_DEFAULT_CATEGORY}")
    if subtype_id not in ID2SUBTYPE:
        logger.warning(f"未知 subtype_id={subtype_id}, 回退为 {_DEFAULT_SUBTYPE}")

    result = {
        'text': text,
        'category': category_name,
        'category_cn': CATEGORY_CN.get(category_name, category_name),
        'category_confidence': float(category_conf),
        'subtype': subtype_name,
        'subtype_cn': SUBTYPE_CN.get(subtype_name, subtype_name),
        'subtype_confidence': float(subtype_conf)
    }

    if return_probs:
        result['category_probs'] = {
            ID2CATEGORY.get(i, f"UNKNOWN_{i}"): float(prob)
            for i, prob in enumerate(category_probs)
        }
        result['subtype_probs'] = {
            ID2SUBTYPE.get(i, f"UNKNOWN_{i}"): float(prob)
            for i, prob in enumerate(subtype_probs)
        }

    return result


# ============ API 路由 ============
@app.get("/", response_model=dict)
async def root():
    """根路径"""
    return {
        "service": "Intent Recognition Service",
        "version": "1.0.0",
        "endpoints": {
            "POST /predict": "单文本意图识别",
            "POST /predict/batch": "批量意图识别",
            "GET /health": "健康检查"
        }
    }


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查"""
    return HealthResponse(
        status="healthy" if model is not None else "unhealthy",
        model_loaded=model is not None,
        device=str(device) if device else "unknown"
    )


@app.post("/predict", response_model=PredictResponse)
async def predict(request: PredictRequest) -> PredictResponse:
    """单文本意图识别"""
    try:
        result = predict_single(request.text, request.return_probs)
        return PredictResponse(**result)
    except Exception as e:
        logger.error(f"预测失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/predict/batch", response_model=BatchPredictResponse)
async def predict_batch(request: BatchPredictRequest) -> BatchPredictResponse:
    """批量意图识别"""
    try:
        results = []
        for text in request.texts:
            result = predict_single(text, request.return_probs)
            results.append(PredictResponse(**result))
        return BatchPredictResponse(results=results)
    except Exception as e:
        logger.error(f"批量预测失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ============ 主函数 ============
def main():
    """启动服务"""
    uvicorn.run(
        "intent_api:app",
        host=CONFIG["HOST"],
        port=CONFIG["PORT"],
        reload=False,
        log_level="info"
    )


if __name__ == "__main__":
    main()
