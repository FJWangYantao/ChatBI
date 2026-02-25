"""
NER 实体识别 Flask 服务
端口: 8002
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
        from transformers import BertTokenizerFast
        from ner_model import BertCRFNER
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

# ============ 全局变量 ============
MODEL = None
TOKENIZER = None
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
ID2LABEL = {}


def load_model():
    """加载模型"""
    global MODEL, TOKENIZER, ID2LABEL
    
    model_path = "./best_ner_model.pt"
    model_name = "hfl/chinese-bert-wwm-ext"
    
    print(f"加载模型: {model_path}")
    
    if not os.path.exists(model_path):
        print(f"[警告] 模型文件不存在: {model_path}")
        print("请先训练模型或提供模型文件")
        return False
    
    try:
        # 加载 checkpoint
        checkpoint = torch.load(model_path, map_location=DEVICE, weights_only=False)
        config = checkpoint['config']
        
        # 加载 tokenizer
        TOKENIZER = BertTokenizerFast.from_pretrained(model_name)
        
        # 创建模型
        MODEL = BertCRFNER(
            model_name=model_name,
            num_labels=config['num_labels'],
            dropout=0.1
        ).to(DEVICE)
        
        # 加载权重
        MODEL.load_state_dict(checkpoint['model_state_dict'])
        MODEL.eval()
        
        # 加载标签映射
        ID2LABEL = config['id2label']
        
        print(f"[成功] 模型加载完成 (设备: {DEVICE})")
        print(f"标签数: {len(ID2LABEL)}")
        return True
    except Exception as e:
        print(f"[错误] 模型加载失败: {e}")
        import traceback
        traceback.print_exc()
        return False


@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({
        "status": "ok",
        "model_loaded": MODEL is not None,
        "device": DEVICE,
        "num_labels": len(ID2LABEL)
    })


@app.route('/ner/predict', methods=['POST'])
def predict():
    """NER 预测"""
    if MODEL is None:
        return jsonify({"error": "模型未加载"}), 500
    
    try:
        data = request.get_json()
        text = data.get('text', '')
        
        if not text:
            return jsonify({"error": "文本不能为空"}), 400
        
        # 将文本转为字符列表
        chars = list(text)
        
        # Tokenize
        encoding = TOKENIZER(
            chars,
            is_split_into_words=True,
            max_length=128,
            padding='max_length',
            truncation=True,
            return_tensors='pt'
        )
        
        input_ids = encoding['input_ids'].to(DEVICE)
        attention_mask = encoding['attention_mask'].to(DEVICE)
        
        # 预测
        with torch.no_grad():
            predictions = MODEL(input_ids, attention_mask)
        
        # 解析实体
        pred_seq = predictions[0]  # 第一个样本
        word_ids = encoding.word_ids()
        
        entities = []
        current_entity = None
        
        for i, (pred_id, word_id) in enumerate(zip(pred_seq, word_ids)):
            if word_id is None:
                continue
            
            if word_id >= len(chars):
                continue
            
            label = ID2LABEL.get(str(pred_id), "O")
            
            if label.startswith("B-"):
                # 保存之前的实体
                if current_entity:
                    entities.append(current_entity)
                
                # 开始新实体
                entity_type = label[2:]
                current_entity = {
                    "text": chars[word_id],
                    "type": entity_type,
                    "start": word_id,
                    "end": word_id + 1
                }
            elif label.startswith("I-") and current_entity:
                # 继续当前实体
                entity_type = label[2:]
                if entity_type == current_entity["type"]:
                    current_entity["text"] += chars[word_id]
                    current_entity["end"] = word_id + 1
            else:
                # O 标签，结束当前实体
                if current_entity:
                    entities.append(current_entity)
                    current_entity = None
        
        # 添加最后一个实体
        if current_entity:
            entities.append(current_entity)
        
        return jsonify({
            "text": text,
            "entities": entities
        })
    
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    print("=" * 60)
    print("NER 实体识别服务启动中...")
    print("=" * 60)
    
    if load_model():
        print("\n服务启动成功！")
        print("访问地址: http://localhost:8002")
        print("=" * 60)
        app.run(host='0.0.0.0', port=8002, debug=False)
    else:
        print("\n[错误] 服务启动失败：模型加载失败")
