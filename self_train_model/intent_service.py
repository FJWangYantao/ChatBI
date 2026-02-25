"""
意图识别 Flask 服务
端口: 8001
"""
import os
import sys
import time

# 设置镜像
os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'

# Python 3.14 + PyTorch 兼容性修复:
# PyTorch 在 import 时调用 platform.machine() -> WMI 查询
# 在 Python 3.14 上这个查询偶尔超时导致 KeyboardInterrupt
# 解决方案: 重试 import，并清理 sys.modules 中残留的半初始化模块
MAX_RETRIES = 5
for _attempt in range(MAX_RETRIES):
    try:
        import torch
        import torch.nn as nn
        from transformers import BertModel, BertTokenizer
        print(f"PyTorch {torch.__version__} 加载成功")
        break
    except KeyboardInterrupt:
        # 清理 sys.modules 中残留的半初始化 torch/transformers 模块
        # 否则重试时 Python 会返回损坏的缓存模块
        _broken = [k for k in sys.modules if k == 'torch' or k.startswith('torch.') or k.startswith('transformers')]
        for k in _broken:
            del sys.modules[k]
        if _attempt < MAX_RETRIES - 1:
            print(f"PyTorch 加载被中断 (WMI 超时), 重试 ({_attempt + 1}/{MAX_RETRIES})...")
            time.sleep(1)
        else:
            print(f"PyTorch 加载失败, 已重试 {MAX_RETRIES} 次")
            raise

from flask import Flask, request, jsonify

app = Flask(__name__)

# ============ 模型定义 ============
class IntentClassifier(nn.Module):
    def __init__(self, model_name, num_categories, num_subtypes, dropout=0.1):
        super().__init__()
        self.bert = BertModel.from_pretrained(model_name)
        self.dropout = nn.Dropout(dropout)
        hidden_size = self.bert.config.hidden_size

        self.category_classifier = nn.Sequential(
            nn.Linear(hidden_size, 256),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(256, num_categories)
        )

        self.subtype_classifier = nn.Sequential(
            nn.Linear(hidden_size, 256),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(256, num_subtypes)
        )

    def forward(self, input_ids, attention_mask):
        outputs = self.bert(input_ids=input_ids, attention_mask=attention_mask)
        pooled_output = outputs.pooler_output
        pooled_output = self.dropout(pooled_output)

        category_logits = self.category_classifier(pooled_output)
        subtype_logits = self.subtype_classifier(pooled_output)

        return {
            'category_logits': category_logits,
            'subtype_logits': subtype_logits
        }


# ============ 全局变量 ============
MODEL = None
TOKENIZER = None
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

CATEGORY_MAP = {
    0: "DATA_QUERY",
    1: "GENERAL_CHAT",
    2: "HYBRID",
    3: "DATA_OPERATION"
}

SUBTYPE_MAP = {
    # DATA_QUERY subtypes (15)
    0: "AGGREGATION_SUM", 1: "AGGREGATION_COUNT", 2: "AGGREGATION_AVG",
    3: "AGGREGATION_MAX_MIN", 4: "DETAIL_LIST", 5: "DETAIL_SINGLE",
    6: "DETAIL_SEARCH", 7: "TREND_ANALYSIS", 8: "COMPARISON_ANALYSIS",
    9: "RANKING_ANALYSIS", 10: "DISTRIBUTION_ANALYSIS",
    11: "JOIN_QUERY", 12: "SUB_QUERY", 13: "METADATA_QUERY",
    14: "ROOT_CAUSE_ANALYSIS",
    # DATA_OPERATION subtypes (4)
    15: "CREATE_OPERATION", 16: "UPDATE_OPERATION",
    17: "DELETE_OPERATION", 18: "EXPORT_OPERATION",
    # Other subtypes
    19: "HYBRID_QUERY", 20: "CHAT",
}


def load_model():
    """加载模型"""
    global MODEL, TOKENIZER
    
    model_path = "./best_intent_model.pt"
    model_name = "hfl/chinese-bert-wwm-ext"
    
    print(f"加载模型: {model_path}")
    
    if not os.path.exists(model_path):
        print(f"[警告] 模型文件不存在: {model_path}")
        print("请先训练模型或提供模型文件")
        return False
    
    try:
        # 加载 tokenizer
        TOKENIZER = BertTokenizer.from_pretrained(model_name)
        
        # 创建模型
        MODEL = IntentClassifier(
            model_name=model_name,
            num_categories=4,
            num_subtypes=21,
            dropout=0.1
        ).to(DEVICE)
        
        # 加载权重
        checkpoint = torch.load(model_path, map_location=DEVICE, weights_only=False)
        MODEL.load_state_dict(checkpoint['model_state_dict'])
        MODEL.eval()
        
        print(f"[成功] 模型加载完成 (设备: {DEVICE})")
        return True
    except Exception as e:
        print(f"[错误] 模型加载失败: {e}")
        return False


@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({
        "status": "ok",
        "model_loaded": MODEL is not None,
        "device": DEVICE
    })


@app.route('/predict', methods=['POST'])
def predict():
    """意图识别预测"""
    if MODEL is None:
        return jsonify({"error": "模型未加载"}), 500
    
    try:
        data = request.get_json()
        text = data.get('text', '')
        
        if not text:
            return jsonify({"error": "文本不能为空"}), 400
        
        # Tokenize
        encoding = TOKENIZER(
            text,
            max_length=128,
            padding='max_length',
            truncation=True,
            return_tensors='pt'
        )
        
        input_ids = encoding['input_ids'].to(DEVICE)
        attention_mask = encoding['attention_mask'].to(DEVICE)
        
        # 预测
        with torch.no_grad():
            outputs = MODEL(input_ids, attention_mask)
        
        # 获取预测结果
        category_id = outputs['category_logits'].argmax(dim=1).item()
        subtype_id = outputs['subtype_logits'].argmax(dim=1).item()
        
        category_probs = torch.softmax(outputs['category_logits'], dim=1)[0]
        subtype_probs = torch.softmax(outputs['subtype_logits'], dim=1)[0]
        
        return jsonify({
            "category": CATEGORY_MAP[category_id],
            "category_confidence": float(category_probs[category_id]),
            "subtype": SUBTYPE_MAP.get(subtype_id, "UNKNOWN_QUERY"),
            "subtype_confidence": float(subtype_probs[subtype_id])
        })
    
    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    print("=" * 60)
    print("意图识别服务启动中...")
    print("=" * 60)
    
    if load_model():
        print("\n服务启动成功！")
        print("访问地址: http://localhost:8001")
        print("=" * 60)
        app.run(host='0.0.0.0', port=8001, debug=False)
    else:
        print("\n[错误] 服务启动失败：模型加载失败")
