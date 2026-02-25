"""
NER (命名实体识别) FastAPI 服务
提供 REST API 接口进行中文命名实体识别

基于 BERT+CRF 模型, 从用户自然语言问题中抽取实体

运行: python ner_api.py

端点:
    POST /ner/predict  - 命名实体识别
    GET  /health       - 健康检查
"""

import os
import sys
import logging
from typing import List, Optional
from contextlib import asynccontextmanager
from datetime import date

# 添加 self_train_model 到路径以导入 time_expression_enhancer
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))

import torch
import torch.nn as nn
import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, Field
from transformers import BertModel, BertTokenizerFast
from torchcrf import CRF

# 设置 HF 镜像 (国内用户)
os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ============ 配置 ============
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, "..", "..", "self_train_model", "best_ner_model.pt")

CONFIG = {
    "MODEL_PATH": os.path.abspath(MODEL_PATH),
    "MODEL_NAME": "hfl/chinese-bert-wwm-ext",
    "DEVICE": "cuda" if torch.cuda.is_available() else "cpu",
    "MAX_LENGTH": 128,
    "HOST": "0.0.0.0",
    "PORT": 8002,
}


# ============ 标签定义 ============
LABEL_LIST = [
    "O",
    "B-TABLE", "I-TABLE",
    "B-COLUMN", "I-COLUMN",
    "B-VALUE", "I-VALUE",
    "B-TIME", "I-TIME",
    "B-AGG", "I-AGG",
    "B-OP", "I-OP",
    "B-KW", "I-KW",
    # === 新增实体类型 ===
    "B-LOC", "I-LOC",          # 地理位置
    "B-ORG", "I-ORG",          # 组织/公司
    "B-FILTER", "I-FILTER",    # 过滤条件
]
ID2LABEL = {i: label for i, label in enumerate(LABEL_LIST)}

# NER 实体类型到 Java 后端类型的映射
NER_TYPE_TO_BACKEND_TYPE = {
    "TABLE": "TABLE",
    "COLUMN": "COLUMN",
    "VALUE": "VALUE",
    "TIME": "TIME_RANGE",
    "AGG": "AGGREGATION",
    "OP": "OPERATOR",
    "KW": "VALUE",  # 关键词映射为 VALUE 类型
    # === 新增映射 ===
    "LOC": "VALUE",     # 地理位置映射为 VALUE
    "ORG": "VALUE",     # 组织映射为 VALUE
    "FILTER": "OPERATOR",  # 过滤条件映射为 OPERATOR
}


# ============ 模型定义 (与训练代码保持一致) ============
class BertCRFNER(nn.Module):
    """BERT + CRF 序列标注模型"""

    def __init__(self, model_name, num_labels, dropout=0.1):
        super().__init__()
        self.num_labels = num_labels
        self.bert = BertModel.from_pretrained(model_name)
        self.dropout = nn.Dropout(dropout)
        self.classifier = nn.Linear(self.bert.config.hidden_size, num_labels)
        self.crf = CRF(num_labels, batch_first=True)

    def forward(self, input_ids, attention_mask, labels=None):
        outputs = self.bert(input_ids=input_ids, attention_mask=attention_mask)
        sequence_output = outputs.last_hidden_state
        sequence_output = self.dropout(sequence_output)
        emissions = self.classifier(sequence_output)

        if labels is not None:
            crf_labels = labels.clone()
            valid_mask = (attention_mask == 1) & (labels != -100)
            crf_labels[crf_labels == -100] = 0
            loss = -self.crf(emissions, crf_labels, mask=valid_mask, reduction='mean')
            return loss
        else:
            mask = attention_mask.bool()
            predictions = self.crf.decode(emissions, mask=mask)
            return predictions


# ============ 全局模型实例 ============
model = None
tokenizer = None
device = None


# ============ 生命周期管理 ============
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理: 启动时加载模型, 关闭时释放资源"""
    global model, tokenizer, device

    logger.info("=" * 60)
    logger.info("NER 命名实体识别服务启动中...")
    logger.info("=" * 60)

    device = torch.device(CONFIG["DEVICE"])
    logger.info(f"使用设备: {device}")

    try:
        # 加载 Tokenizer
        logger.info(f"加载 Tokenizer: {CONFIG['MODEL_NAME']}")
        tokenizer = BertTokenizerFast.from_pretrained(CONFIG["MODEL_NAME"])
        logger.info("[OK] Tokenizer 加载成功")

        # 加载模型
        logger.info(f"加载 NER 模型: {CONFIG['MODEL_PATH']}")

        if not os.path.exists(CONFIG['MODEL_PATH']):
            logger.error(f"[ERROR] 模型文件不存在: {CONFIG['MODEL_PATH']}")
            logger.error("请先运行训练脚本: cd self_train_model && python train_ner_model.py")
            raise FileNotFoundError(f"Model file not found: {CONFIG['MODEL_PATH']}")

        checkpoint = torch.load(CONFIG['MODEL_PATH'], map_location=device, weights_only=False)

        model_config = checkpoint.get('config', {})
        num_labels = model_config.get('num_labels', len(LABEL_LIST))

        # 如果 checkpoint 中有自定义标签列表, 使用它
        saved_id2label = model_config.get('id2label')
        if saved_id2label:
            global ID2LABEL
            ID2LABEL = {int(k): v for k, v in saved_id2label.items()}
            logger.info(f"使用模型中保存的标签集: {num_labels} 个标签")

        model = BertCRFNER(
            model_name=CONFIG['MODEL_NAME'],
            num_labels=num_labels,
        ).to(device)

        model.load_state_dict(checkpoint['model_state_dict'])
        model.eval()

        logger.info("[OK] NER 模型加载成功")
        if 'f1' in checkpoint:
            logger.info(f"  最佳 F1: {checkpoint['f1']:.4f}")

        logger.info("=" * 60)
        logger.info(f"NER 服务启动完成, 监听 {CONFIG['HOST']}:{CONFIG['PORT']}")
        logger.info("=" * 60)

    except Exception as e:
        logger.error(f"[ERROR] NER 模型加载失败: {e}")
        raise

    yield

    # 关闭时清理资源
    logger.info("NER 服务关闭中...")
    del model
    del tokenizer
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    logger.info("[OK] NER 服务已关闭")


# ============ FastAPI 应用 ============
app = FastAPI(
    title="NER Service",
    description="中文命名实体识别微服务 - 基于 BERT+CRF",
    version="1.0.0",
    lifespan=lifespan,
)


# ============ 422 错误调试 ============
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """捕获 422 验证错误，记录详细信息用于调试"""
    body = None
    try:
        body = await request.body()
        body_str = body.decode('utf-8')[:200]  # 只取前200字符
    except Exception:
        body_str = "<无法读取>"
    
    logger.error(f"[422 调试] 验证错误!")
    logger.error(f"  请求体前200字符: {body_str}")
    logger.error(f"  请求体总长度: {len(body) if body else 'unknown'} bytes")
    logger.error(f"  错误详情: {exc.errors()}")
    
    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()},
    )


# ============ 请求/响应模型 ============
class EntityItem(BaseModel):
    """单个实体"""
    text: str = Field(..., description="实体文本")
    type: str = Field(..., description="实体类型 (TABLE, COLUMN, VALUE, TIME_RANGE, AGGREGATION, OPERATOR)")
    start_pos: int = Field(..., description="起始位置 (字符级)")
    end_pos: int = Field(..., description="结束位置 (字符级)")
    confidence: float = Field(1.0, description="置信度")


class NERPredictRequest(BaseModel):
    """NER 预测请求"""
    text: str = Field(..., description="待识别的文本", min_length=1, max_length=5000)


class NERPredictResponse(BaseModel):
    """NER 预测响应"""
    original_text: str = Field(..., description="原始文本")
    entities: List[EntityItem] = Field(default_factory=list, description="识别到的实体列表")


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str
    model_loaded: bool
    device: str


# ============ 后处理规则 ============
def post_process_entities(text, entities):
    """
    对 NER 结果进行后处理，修复常见的识别不完整问题
    
    Examples:
        - "产品" + 后面有"表" -> 修正为 "产品表"
        - "10" + 前面有"前"、后面有"个" -> 修正为 "前10个"
        - "销售" + 后面有"额" -> 修正为 "销售额"
    """
    if not entities:
        return entities
    
    processed = []
    i = 0
    while i < len(entities):
        entity = entities[i]
        text_lower = text.lower()
        
        # 规则1: "产品" 后面有"表" -> 修正为 "产品表"
        if entity["type"] == "TABLE" and entity["text"] in ["产品", "出货"]:
            end_pos = entity["end"]
            if end_pos < len(text) and text[end_pos] in ["表", "库"]:
                entity["text"] = entity["text"] + text[end_pos]
                entity["end"] = end_pos + 1
                logger.debug(f"后处理: '{entity['text'][:-1]}' -> '{entity['text']}'")
        
        # 规则2: 数字前有"前"、后有"个" -> 修正为 "前N个"
        if entity["type"] == "VALUE" and entity["text"].isdigit():
            start_pos = entity["start"]
            end_pos = entity["end"]
            
            # 检查前缀 "前"、"最"、"后"、"第"
            prefix = ""
            if start_pos > 0:
                prefix_char = text[start_pos - 1]
                if prefix_char in ["前", "最", "后", "第", "约", "大约"]:
                    prefix = prefix_char
                    start_pos -= 1
            
            # 检查后缀 "个"、"名"、"条"、"行"
            suffix = ""
            if end_pos < len(text):
                suffix_char = text[end_pos]
                if suffix_char in ["个", "名", "条", "行", "项", "只", "家"]:
                    suffix = suffix_char
                    end_pos += 1
            
            if prefix or suffix:
                entity["text"] = prefix + entity["text"] + suffix
                entity["start"] = start_pos
                entity["end"] = end_pos
                logger.debug(f"后处理: 修正数量表达 -> '{entity['text']}'")
        
        # 规则3: "销售" 后面有"额" -> 修正为 "销售额"
        if entity["type"] == "COLUMN" and entity["text"] in ["销售", "出货", "生产"]:
            end_pos = entity["end"]
            if end_pos < len(text) and text[end_pos] in ["额", "量", "数"]:
                entity["text"] = entity["text"] + text[end_pos]
                entity["end"] = end_pos + 1
                logger.debug(f"后处理: '{entity['text'][:-1]}' -> '{entity['text']}'")
        
        # 规则4: 合并相邻的同类型实体 (如果中间只有"和"、"及"、"或")
        if i + 2 < len(entities):
            next_entity = entities[i + 1]
            next_next_entity = entities[i + 2]
            
            # 如果当前实体和下下个实体是同类型，中间是连接词
            if (entity["type"] == next_next_entity["type"] and
                next_entity["text"] in ["和", "及", "或", "、"]):
                
                # 检查中间的间距是否合理
                if (entity["end"] + 1 >= next_entity["start"] and 
                    next_entity["end"] + 1 >= next_next_entity["start"]):
                    
                    combined_text = text[entity["start"]:next_next_entity["end"]]
                    entity["text"] = combined_text
                    entity["end"] = next_next_entity["end"]
                    
                    # 跳过中间的两个实体
                    i += 2
                    logger.debug(f"后处理: 合并实体 -> '{combined_text}'")
        
        processed.append(entity)
        i += 1
    
    return processed


# ============ 核心推理函数 ============
def decode_bio_to_entities(text, chars, tag_ids, word_ids):
    """
    将 BIO 标签序列解码为实体列表。

    Args:
        text: 原始文本
        chars: 字符列表
        tag_ids: 预测的标签 ID 序列
        word_ids: token 到原始字符的映射

    Returns:
        entities: 实体列表
    """
    entities = []
    current_entity = None

    for i, (tag_id, word_id) in enumerate(zip(tag_ids, word_ids)):
        if word_id is None:
            # Special token ([CLS], [SEP], [PAD])
            if current_entity:
                entities.append(current_entity)
                current_entity = None
            continue

        label = ID2LABEL.get(tag_id, "O")

        if label.startswith("B-"):
            # 新实体开始
            if current_entity:
                entities.append(current_entity)
            entity_type = label[2:]
            char_idx = word_id
            current_entity = {
                "type": entity_type,
                "start": char_idx,
                "end": char_idx + 1,
                "text": chars[char_idx] if char_idx < len(chars) else "",
            }

        elif label.startswith("I-") and current_entity:
            # 实体延续
            entity_type = label[2:]
            if entity_type == current_entity["type"]:
                char_idx = word_id
                current_entity["end"] = char_idx + 1
                # 从原始文本中提取实体文本 (处理可能的不连续)
                current_entity["text"] = text[current_entity["start"]:current_entity["end"]]
            else:
                # 类型不匹配, 结束当前实体
                entities.append(current_entity)
                current_entity = None

        else:
            # O 标签或其他: 结束当前实体
            if current_entity:
                entities.append(current_entity)
                current_entity = None

    if current_entity:
        entities.append(current_entity)

    return entities


def predict_ner(text: str) -> dict:
    """
    对单个文本进行 NER 预测。

    Args:
        text: 用户输入文本

    Returns:
        dict with original_text and entities list
    """
    if model is None or tokenizer is None:
        raise RuntimeError("模型未加载")

    chars = list(text)

    # Tokenize
    encoding = tokenizer(
        chars,
        is_split_into_words=True,
        max_length=CONFIG["MAX_LENGTH"],
        padding='max_length',
        truncation=True,
        return_tensors='pt',
    )

    word_ids = encoding.word_ids()

    input_ids = encoding['input_ids'].to(device)
    attention_mask = encoding['attention_mask'].to(device)

    # 推理
    with torch.no_grad():
        predictions = model(input_ids, attention_mask)

    tag_ids = predictions[0]  # 取 batch 中的第一条 (也是唯一一条)

    # BIO 解码
    raw_entities = decode_bio_to_entities(text, chars, tag_ids, word_ids)
    
    # 后处理: 修复常见的识别不完整问题
    processed_entities = post_process_entities(text, raw_entities)

    # 转换为后端兼容的实体类型
    entity_items = []
    for ent in processed_entities:
        backend_type = NER_TYPE_TO_BACKEND_TYPE.get(ent["type"], ent["type"])
        entity_items.append(EntityItem(
            text=ent["text"],
            type=backend_type,
            start_pos=ent["start"],
            end_pos=ent["end"],
            confidence=1.0,
        ))

    return {
        "original_text": text,
        "entities": entity_items,
    }


# ============ API 路由 ============
@app.get("/", response_model=dict)
async def root():
    """根路径"""
    return {
        "service": "NER Service",
        "version": "1.0.0",
        "endpoints": {
            "POST /ner/predict": "命名实体识别",
            "GET /health": "健康检查",
        }
    }


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查"""
    return HealthResponse(
        status="healthy" if model is not None else "unhealthy",
        model_loaded=model is not None,
        device=str(device) if device else "unknown",
    )


# ============ 时间表达式解析 API ============
class TimeParseRequest(BaseModel):
    """时间表达式解析请求"""
    expression: str = Field(..., description="时间表达式，如 2024.3-2025.1, FY23, 前两个Q")
    reference_date: Optional[str] = Field(None, description="参考日期 YYYY-MM-DD，用于相对时间")


class TimeParseResponse(BaseModel):
    """时间表达式解析响应"""
    success: bool = Field(..., description="是否解析成功")
    start_date: Optional[str] = Field(None, description="开始日期 YYYY-MM-DD")
    end_date: Optional[str] = Field(None, description="结束日期 YYYY-MM-DD")
    sql_condition: Optional[str] = Field(None, description="SQL WHERE 条件")
    raw_expression: str = Field("", description="原始表达式")


@app.post("/time/parse", response_model=TimeParseResponse)
async def parse_time_expression(request: TimeParseRequest):
    """解析时间表达式，返回日期范围和 SQL 条件"""
    try:
        from self_train_model.time_expression_enhancer import parse_time_expression, TimeExpressionParser
        ref_date = None
        if request.reference_date:
            ref_date = date.fromisoformat(request.reference_date)
        parser = TimeExpressionParser()
        result = parser.parse(request.expression, ref_date)
        if result:
            return TimeParseResponse(
                success=True,
                start_date=result.start_date.isoformat(),
                end_date=result.end_date.isoformat(),
                sql_condition=parser.to_sql_condition(result),
                raw_expression=result.raw_expression or request.expression,
            )
        return TimeParseResponse(
            success=False,
            raw_expression=request.expression,
        )
    except Exception as e:
        logger.error(f"时间表达式解析失败: {e}")
        return TimeParseResponse(
            success=False,
            raw_expression=request.expression,
        )


@app.post("/ner/predict", response_model=NERPredictResponse)
async def ner_predict(request: NERPredictRequest):
    """命名实体识别"""
    try:
        text_len = len(request.text)
        if text_len > 200:
            logger.warning(f"[NER] 收到超长文本 ({text_len} 字符), 前100字符: {request.text[:100]}...")
        result = predict_ner(request.text)
        return NERPredictResponse(**result)
    except Exception as e:
        logger.error(f"NER 预测失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ============ 主函数 ============
def main():
    """启动 NER 服务"""
    uvicorn.run(
        "ner_api:app",
        host=CONFIG["HOST"],
        port=CONFIG["PORT"],
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
