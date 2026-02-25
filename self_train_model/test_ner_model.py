"""
NER 模型测试脚本
直接加载训练好的模型，测试中文实体识别效果

运行: python test_ner_model.py
"""

import torch
from ner_model import BertCRFNER
from transformers import BertTokenizerFast

# ============ 配置 ============
MODEL_PATH = "./best_ner_model.pt"
MODEL_NAME = "hfl/chinese-bert-wwm-ext"
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

# 标签定义
ID2LABEL = {
    0: "O",
    1: "B-TABLE", 2: "I-TABLE",
    3: "B-COLUMN", 4: "I-COLUMN",
    5: "B-VALUE", 6: "I-VALUE",
    7: "B-TIME", 8: "I-TIME",
    9: "B-AGG", 10: "I-AGG",
    11: "B-OP", 12: "I-OP",
    13: "B-KW", 14: "I-KW",
    # === 新增实体类型 ===
    15: "B-LOC", 16: "I-LOC",        # 地理位置
    17: "B-ORG", 18: "I-ORG",        # 组织/公司
    19: "B-FILTER", 20: "I-FILTER",  # 过滤条件
}

# 实体类型中文名
ENTITY_TYPE_CN = {
    "TABLE": "表名",
    "COLUMN": "字段/指标",
    "VALUE": "数值",
    "TIME": "时间范围",
    "AGG": "聚合函数",
    "OP": "比较操作",
    "KW": "关键词",
    # === 新增 ===
    "LOC": "地理位置",
    "ORG": "组织/公司",
    "FILTER": "过滤条件",
}


# ============ 模型加载 ============
def load_model():
    """加载训练好的模型"""
    print("加载模型...")
    
    try:
        checkpoint = torch.load(MODEL_PATH, map_location=DEVICE, weights_only=False)
        model = BertCRFNER(MODEL_NAME, num_labels=len(ID2LABEL)).to(DEVICE)
        model.load_state_dict(checkpoint['model_state_dict'])
        model.eval()
        print(f"✓ 模型加载成功 (F1: {checkpoint.get('f1', 'N/A'):.4f})" if 'f1' in checkpoint else "✓ 模型加载成功")
    except FileNotFoundError:
        print(f"✗ 模型文件不存在: {MODEL_PATH}")
        print("请先运行: python train_ner_model.py")
        return None
    except Exception as e:
        print(f"✗ 模型加载失败: {e}")
        return None
    
    return model


def load_tokenizer():
    """加载分词器"""
    print("加载分词器...")
    try:
        tokenizer = BertTokenizerFast.from_pretrained(MODEL_NAME)
        print("✓ 分词器加载成功")
        return tokenizer
    except Exception as e:
        print(f"✗ 分词器加载失败: {e}")
        return None


# ============ 推理函数 ============
def predict(text, model, tokenizer):
    """
    预测文本中的实体

    Args:
        text: 输入文本
        model: NER 模型
        tokenizer: 分词器

    Returns:
        entities: 实体列表 [{"type": "TABLE", "text": "产品", "pos": 2}, ...]
    """
    chars = list(text)

    # Tokenize
    encoding = tokenizer(
        chars,
        is_split_into_words=True,
        max_length=128,
        padding='max_length',
        truncation=True,
        return_tensors='pt',
    )

    input_ids = encoding['input_ids'].to(DEVICE)
    attention_mask = encoding['attention_mask'].to(DEVICE)
    word_ids = encoding.word_ids()

    # 推理
    with torch.no_grad():
        predictions = model(input_ids, attention_mask)

    tag_ids = predictions[0]

    # ===== BIO 解码 =====
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
            current_entity = {
                "type": entity_type,
                "text": chars[word_id],
                "pos": word_id,
                "confidence": 1.0,
            }

        elif label.startswith("I-") and current_entity:
            # 实体延续
            entity_type = label[2:]
            if entity_type == current_entity["type"]:
                current_entity["text"] += chars[word_id]
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

    # ===== 后处理：修正常见错误 =====
    entities = post_process_entities(entities, text)

    return entities


# ============ 后处理函数 ============
def post_process_entities(entities, text):
    """
    后处理：修正常见错误
    
    1. 扩展被截断的英文单词 (如 COM -> COMPAL)
    2. 合并相邻的同类实体 (如 "出" + "货" -> "出货")
    """
    if not entities:
        return entities
    
    improved = []
    skip_next = False
    
    for i, ent in enumerate(entities):
        if skip_next:
            skip_next = False
            continue
        
        # === 修正1: 扩展被截断的英文单词 ===
        if ent["type"] in ["KW", "ORG"] and ent["pos"] + len(ent["text"]) < len(text):
            end_pos = ent["pos"] + len(ent["text"])
            # 如果下一个字符是英文字母或数字，尝试扩展
            while end_pos < len(text) and (text[end_pos].isalnum() or text[end_pos] in [' ', '-', '_']):
                # 避免扩展到中文字符
                if '\u4e00' <= text[end_pos] <= '\u9fff':
                    break
                end_pos += 1
            
            if end_pos > ent["pos"] + len(ent["text"]):
                ent["text"] = text[ent["pos"]:end_pos].rstrip()
        
        # === 修正2: 合并相邻的同类实体 ===
        if i < len(entities) - 1:
            next_ent = entities[i + 1]
            # 如果相邻且类型相同，且位置连续
            if (ent["type"] == next_ent["type"] and 
                next_ent["pos"] <= ent["pos"] + len(ent["text"]) + 1):  # 允许1个字符的间隔
                # 合并实体
                ent["text"] = text[ent["pos"]:next_ent["pos"] + len(next_ent["text"])]
                skip_next = True
        
        improved.append(ent)
    
    return improved


# ============ 显示函数 ============
def print_result(question, entities):
    """格式化打印结果"""
    print(f"\n问题: {question}")
    
    if entities:
        print("  识别到的实体:")
        for i, ent in enumerate(entities, 1):
            entity_type_cn = ENTITY_TYPE_CN.get(ent["type"], ent["type"])
            # 使用固定宽度避免中文字符对齐问题
            print(f"    {i}. 类型: {entity_type_cn:6s}  文本: {ent['text']:15s}  位置: {ent['pos']}")
    else:
        print("  (未识别到实体)")


# ============ 主函数 ============
def main():
    print("=" * 70)
    print(" NER 模型测试 - 中文命名实体识别")
    print("=" * 70)

    # 加载模型和分词器
    model = load_model()
    if model is None:
        return

    tokenizer = load_tokenizer()
    if tokenizer is None:
        return

    print(f"设备: {DEVICE}\n")

    # ===== 测试用例 =====
    test_questions = [
        # === 基础测试 ===
        # 表名识别
        "查询产品表的所有数据",
        "显示用户的详细信息",
        
        # 字段识别
        "查看销售额和利润的分布",
        "统计客单价的平均值",
        
        # 时间范围
        "上个月的销售数据怎么样",
        "本周产品销售额",
        
        # 聚合函数
        "计算订单的总数",
        "求最高销售额",
        
        # === 复杂查询 ===
        "查询上个月产品的总销售额",
        "统计2024年用户按地区的平均客单价",
        "找出销售额大于1000的订单",
        "显示地区按销售额排名前5的产品",
        "分析销售额下降的原因",
        
        # === 新增：产品编号测试 ===
        "2023年3月S3 15出货出了多少？",
        "iPhone 14本周销售额",
        "MacBook Pro库存还有多少",
        
        # === 新增：地理位置测试 ===
        "美国的销售额统计",
        "华东地区产品销量",
        "从上海到北京的物流成本",
        
        # === 新增：组织/公司测试 ===
        "COMPAL的出货量",
        "Foxconn和Dell的对比",
        "Apple供应商的出货记录",
        
        # === 新增：复杂组合测试（关键案例）===
        "过去两年从COMPAL出货美国的产品出货量及占比",
        "Lenovo ThinkPad在美国本月销售额",
        "从Foxconn到欧洲的出货统计",
    ]

    # 执行预测
    print("\n" + "=" * 70)
    print(" 测试结果")
    print("=" * 70)

    for question in test_questions:
        entities = predict(question, model, tokenizer)
        print_result(question, entities)

    # ===== 交互式模式 =====
    print("\n" + "=" * 70)
    print(" 交互式测试 (输入 'quit' 退出)")
    print("=" * 70)

    while True:
        try:
            user_input = input("\n输入问题: ").strip()
            if user_input.lower() in ['quit', 'exit', 'q']:
                print("退出测试。")
                break
            if not user_input:
                continue

            entities = predict(user_input, model, tokenizer)
            print_result(user_input, entities)

        except KeyboardInterrupt:
            print("\n\n退出测试。")
            break
        except Exception as e:
            print(f"错误: {e}")


if __name__ == "__main__":
    main()
