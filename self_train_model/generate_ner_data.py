"""
NER (命名实体识别) BIO 标注训练数据生成脚本
复用意图识别的模板和实体库，在填充模板时同步生成字符级 BIO 标注

运行: python generate_ner_data.py

输出格式 (每条样本):
{
    "text": "查询上个月产品的总销售额",
    "chars": ["查", "询", "上", "个", "月", "产", "品", "的", "总", "销", "售", "额"],
    "labels": ["O", "O", "B-TIME", "I-TIME", "I-TIME", "B-TABLE", "I-TABLE", "O", "B-AGG", "B-COLUMN", "I-COLUMN", "I-COLUMN"]
}
"""

import os
import json
import re
import random
from collections import Counter

# 从意图识别数据生成脚本中复用模板和实体库
from generate_full_data import (
    DATA_QUERY_TEMPLATES,
    HYBRID_TEMPLATES,
    DATA_OPERATION_TEMPLATES,
    GENERAL_CHAT_TEMPLATES,
    ENTITIES as BASE_ENTITIES,
)


# ============ 配置 ============
class Config:
    SAMPLES_PER_TEMPLATE_GROUP = 80   # 每种查询子类型的样本数
    SAMPLES_HYBRID = 100              # 混合查询样本数
    SAMPLES_PER_OPERATION = 50        # 每种数据操作的样本数
    SAMPLES_GENERAL_CHAT = 150        # 普通对话样本数 (无实体负样本)
    OUTPUT_PATH = "./ner_training_data.json"


# ============ 扩展实体库 (增加多样性 + 真实场景) ============
ENTITIES = {**BASE_ENTITIES}

# 表名 - 添加真实业务表名
ENTITIES["entity"] = list(set(
    BASE_ENTITIES["entity"] + [
        "员工", "供应商", "门店", "品类", "仓库", "渠道",
        "出货表", "库存表", "订单明细", "销售记录", "产品信息"
    ]
))
ENTITIES["entity1"] = list(set(
    BASE_ENTITIES["entity1"] + ["门店", "品类", "渠道", "出货表"]
))
ENTITIES["entity2"] = list(set(
    BASE_ENTITIES["entity2"] + ["员工", "供应商", "渠道", "库存表"]
))

# 指标 - 添加真实业务指标（包含复合指标）
ENTITIES["metric"] = list(set(
    BASE_ENTITIES["metric"] + [
        "毛利率", "净利润", "GMV", "访问量", "订单量", "退货率", "库存量",
        "出货量", "出货", "库存", "在途量", "良品率", "周转率", "发货量",
        "入库量", "出库量", "剩余量", "占用率", "达成率",
        # === 复合指标（避免被拆分）===
        "出货比例", "库存占比", "销售占比", "市场份额", "增长率",
        "完成率", "达标率", "良品率", "合格率", "通过率"
    ]
))

# 时间范围 - 添加更多格式（包含财年、复合时间、跨年、相对时间）
ENTITIES["time_range"] = list(set(
    BASE_ENTITIES["time_range"] + [
        "上半年", "下半年", "第一季度", "第二季度", "最近30天", "最近一年",
        "2023年3月", "2024年1月", "2023年", "本季度", "上季度", "过去两年",
        # === 财年格式 ===
        "FY23", "FY24", "FY2324", "FY23/24", "FY24/25", "Q1FY24", "Q2FY23",
        "财年23", "23财年", "24财年", "2023财年", "FY23年Q3", "FY24年Q1",
        # === 复合时间格式（年份+季度）===
        "2024年Q1", "2024年Q2", "2023年Q3", "2023年Q4",
        "2024年第一季度", "2023年第四季度", "2024年一季度",
        # === 跨年时间范围 ===
        "2023年3月到2024年4月", "2023年3月到2024年12月", "2022年1月到2024年12月",
        "2024年3月到2025年1月", "2024.3 - 2025.1", "2024.2-25.2",
        # === 相对时间 ===
        "前两个Q", "前两个季度", "过去两年零五个月", "上市后到现在",
        "SS后到现在", "SS后的三个月内", "上个季度", "今年4月之后",
        "截至到2024年5月", "截至到当前", "到目前为止"
    ]
))

# ============ 产品系列名称（易与组织混淆，需在 product_code 前定义）============
PRODUCT_SERIES_NAMES = [
    "Ultraslim", "UltraBook", "ThinkPad", "MacBook", "iPad", "iPhone",
    "Galaxy", "Surface", "Pavilion", "Inspiron", "Latitude", "Precision",
    "ZenBook", "VivoBook", "IdeaPad", "Legion", "ROG", "TUF",
    "ProBook", "EliteBook", "Spectre", "Envy", "Omen", "XPS", "Yoga"
]

# 新增：产品编号/型号（真实场景，基于错误分析扩充）
ENTITIES["product_code"] = [
    "S3 15", "S3 20", "S3 80", "S3 IRH", "iPhone 14", "MacBook Pro",
    "A001", "SKU-12345", "型号X100", "编号A01", "产品S3", "货号M201",
    "M3芯片", "14Pro", "3080Ti", "RTX4090", "15寸", "13寸", "64GB", "256G",
    "ThinkPad", "Surface Pro", "Galaxy S23", "Pavilion", "Inspiron",
    # === ChatBI业务产品（基于错误分析）===
    "YGPro 7", "YG Pro 7", "IP Pro5", "IPS3", "IPS5", "IPS5 16IRL8",
    "IPS5 14IMH9", "IPS3 15IAH8", "LOQ 15IRX9", "LOQ 15IAX9I",
    "IPPro5 14IRH8", "IPPro5 16IRH8", "IdeaPad 1 15IGL7", "C990",
    "Yoga Pro 9 Gen 90", "Yoga Slim 7", "Legion Pro", "Ideapad",
    "LOQ", "IdeaPad", "消费NB", "NB",
    # === 添加产品系列名称（避免误判为ORG）===
] + PRODUCT_SERIES_NAMES

# 新增：地理位置（包含业务术语）
ENTITIES["location"] = [
    "美国", "中国", "欧洲", "亚洲", "日本", "韩国", "印度",
    "华东", "华北", "华南", "华中", "西南", "东北",
    "上海", "北京", "深圳", "广州", "杭州", "成都",
    "USA", "China", "Europe", "Asia",
    # === 业务术语 ===
    "geo", "region", "area", "各个地区", "各geo", "各区域"
]

# 新增：组织/公司
ENTITIES["organization"] = [
    "COMPAL", "Foxconn", "富士康", "Dell", "HP", "Lenovo",
    "ASUS", "Acer", "MSI", "Apple", "Samsung",
    "Huawei", "华为", "Xiaomi", "小米", "OPPO", "VIVO",
    "供应商A", "客户B", "总部", "分公司", "工厂"
]

# 新增：过滤条件词
ENTITIES["filter_word"] = [
    "从", "到", "在", "属于", "来自", "去往", "向"
]

# 新增：上市/Listing相关（SS = Start of Sales/上市）
ENTITIES["listing_term"] = [
    "SS", "上市", "上市后", "SS后", "从SS", "SS到",
    "SS后到现在", "SS后的三个月内", "从SS到目前"
]


# ============ 占位符 -> NER 实体类型映射 ============
PLACEHOLDER_TYPE_MAP = {
    "time_range": "TIME",
    "time_range1": "TIME",
    "time_range2": "TIME",
    "entity": "TABLE",
    "entity1": "TABLE",
    "entity2": "TABLE",
    "metric": "COLUMN",
    "value": "VALUE",
    "keyword": "KW",
    "top_n": "VALUE",
    "entity_id": "VALUE",
    "product_code": "KW",  # 产品编号映射为关键词
    # === 新增映射 ===
    "location": "LOC",     # 地理位置
    "organization": "ORG", # 组织/公司
    "filter_word": "FILTER",  # 过滤条件
    "listing_term": "KW",  # 上市/SS相关（业务术语）
    # change_direction 不标注 (非数据库实体)
}


# ============ 聚合函数关键词 (按长度降序, 优先匹配长词) ============
AGG_KEYWORDS = sorted([
    "平均水平", "平均值", "平均数",
    "最大最小值", "最大值", "最小值",
    "总计", "总和", "总额", "总共", "合计", "汇总",
    "平均", "均值",
    "最高", "最低", "极值", "峰值",
    "总数", "个数",
    "同比环比",
    "总",
], key=len, reverse=True)


# ============ 比较操作关键词 ============
OP_KEYWORDS = sorted([
    "不低于", "不高于", "不等于",
    "大于", "小于", "等于",
    "超过", "高于", "低于",
    "至少", "最多",
], key=len, reverse=True)


# ============ 产品编号检测模式 ============
PRODUCT_CODE_PATTERNS = sorted([
    r'[A-Z]+\s+\d+',           # S3 15, iPhone 14
    r'[A-Z]\d+\s*[A-Z]*',      # S3, M3芯片
    r'\d+[A-Z]+',              # 14Pro, 3080Ti
    r'SKU-\d+',                # SKU-12345
    r'型号[A-Z0-9]+',          # 型号X100
    r'[A-Z]\d{3,}',            # A001, M201
    r'\d+寸',                  # 15寸, 13寸
    r'\d+G[B]?',               # 64GB, 256G
], key=len, reverse=True)

# ============ 过滤条件关键词 ============
FILTER_KEYWORDS = sorted([
    "从", "到", "在", "属于", "来自", "去往", "向"
], key=len, reverse=True)

# ============ 上市/SS业务术语关键词 ============
LISTING_KEYWORDS = sorted([
    "SS后到现在", "SS后的三个月内", "从SS到目前", "上市后到现在",
    "SS后", "上市后", "从SS", "SS到", "SS", "上市"
], key=len, reverse=True)


# ============ 标签集定义 ============
LABEL_SET = {
    "O",
    "B-TABLE", "I-TABLE",
    "B-COLUMN", "I-COLUMN",
    "B-VALUE", "I-VALUE",
    "B-TIME", "I-TIME",
    "B-AGG", "I-AGG",
    "B-OP", "I-OP",
    "B-KW", "I-KW",
    # === 新增标签 ===
    "B-LOC", "I-LOC",
    "B-ORG", "I-ORG",
    "B-FILTER", "I-FILTER",
}


# ============ 核心函数 ============

def fill_template_with_tracking(template, entities):
    """
    填充模板的同时跟踪每个实体在最终文本中的位置和类型。

    Args:
        template: 包含 {placeholder} 的模板字符串
        entities: 实体库字典

    Returns:
        text: 填充后的文本
        entity_spans: [(start, end, entity_type, entity_text), ...]
    """
    entity_spans = []
    placeholder_pattern = re.compile(r'\{(\w+)\}')

    result = ""
    last_end = 0

    for match in placeholder_pattern.finditer(template):
        key = match.group(1)
        placeholder_start = match.start()
        placeholder_end = match.end()

        # 添加占位符之前的文本
        result += template[last_end:placeholder_start]

        # 填充占位符
        if key in entities:
            value = random.choice(entities[key])
            entity_type = PLACEHOLDER_TYPE_MAP.get(key)

            if entity_type:
                entity_start = len(result)
                entity_end = entity_start + len(value)
                entity_spans.append((entity_start, entity_end, entity_type, value))

            result += value
        else:
            # 无法识别的占位符原样保留
            result += match.group(0)

        last_end = placeholder_end

    result += template[last_end:]

    return result, entity_spans


def find_keyword_spans(text, keywords, entity_type, existing_spans):
    """
    在文本中查找关键词，返回不与已有实体重叠的位置列表。
    关键词按长度降序排列，优先匹配长词以避免子串冲突。

    Args:
        text: 填充后的完整文本
        keywords: 关键词列表 (已按长度降序排列)
        entity_type: 要标注的实体类型
        existing_spans: 已有的实体位置列表

    Returns:
        new_spans: 新发现的关键词位置列表
    """
    occupied = set()
    for start, end, _, _ in existing_spans:
        for i in range(start, end):
            occupied.add(i)

    new_spans = []
    for keyword in keywords:
        search_start = 0
        while True:
            pos = text.find(keyword, search_start)
            if pos == -1:
                break
            end = pos + len(keyword)
            # 检查是否与已有实体重叠
            if not any(i in occupied for i in range(pos, end)):
                new_spans.append((pos, end, entity_type, keyword))
                for i in range(pos, end):
                    occupied.add(i)
            search_start = pos + 1

    return new_spans


def spans_to_bio_tags(text, entity_spans):
    """
    将实体位置列表转换为字符级 BIO 标签序列。

    Args:
        text: 原始文本
        entity_spans: [(start, end, entity_type, entity_text), ...]

    Returns:
        chars: 字符列表
        tags: BIO 标签列表
    """
    chars = list(text)
    tags = ["O"] * len(chars)

    # 按起始位置排序
    sorted_spans = sorted(entity_spans, key=lambda x: x[0])

    for start, end, entity_type, _ in sorted_spans:
        if start < len(chars) and end <= len(chars) and start < end:
            tags[start] = f"B-{entity_type}"
            for i in range(start + 1, end):
                tags[i] = f"I-{entity_type}"

    return chars, tags


def find_product_code_spans(text, existing_spans):
    """
    检测产品编号（字母+数字组合）
    """
    import re
    occupied = set()
    for start, end, _, _ in existing_spans:
        for i in range(start, end):
            occupied.add(i)
    
    new_spans = []
    for pattern in PRODUCT_CODE_PATTERNS:
        for match in re.finditer(pattern, text):
            pos = match.start()
            end = match.end()
            if not any(i in occupied for i in range(pos, end)):
                new_spans.append((pos, end, "KW", match.group()))
                for i in range(pos, end):
                    occupied.add(i)
    
    return new_spans


def generate_ner_sample(template, entities):
    """
    从单个模板生成一条 NER BIO 标注样本。

    流程:
    1. 填充模板占位符并跟踪实体位置 (TABLE, COLUMN, VALUE, TIME, KW, LOC, ORG, FILTER)
    2. 在非实体区域检测聚合函数关键词 (AGG)
    3. 在非实体区域检测比较操作关键词 (OP)
    4. 在非实体区域检测产品编号 (KW)
    5. 在非实体区域检测过滤条件 (FILTER)
    6. 合并所有实体并生成 BIO 标签
    """
    # 1. 填充模板并跟踪实体位置
    text, entity_spans = fill_template_with_tracking(template, entities)

    # 2. 在非实体区域检测聚合函数关键词
    agg_spans = find_keyword_spans(text, AGG_KEYWORDS, "AGG", entity_spans)

    # 3. 在非实体区域检测比较操作关键词
    all_spans_so_far = entity_spans + agg_spans
    op_spans = find_keyword_spans(text, OP_KEYWORDS, "OP", all_spans_so_far)

    # 4. 在非实体区域检测产品编号
    all_spans_so_far = entity_spans + agg_spans + op_spans
    product_code_spans = find_product_code_spans(text, all_spans_so_far)

    # 5. 在非实体区域检测过滤条件
    all_spans_so_far = entity_spans + agg_spans + op_spans + product_code_spans
    filter_spans = find_keyword_spans(text, FILTER_KEYWORDS, "FILTER", all_spans_so_far)

    # 5b. 在非实体区域检测上市/SS业务术语
    all_spans_so_far = entity_spans + agg_spans + op_spans + product_code_spans + filter_spans
    listing_spans = find_keyword_spans(text, LISTING_KEYWORDS, "KW", all_spans_so_far)

    # 6. 合并所有实体位置
    all_spans = entity_spans + agg_spans + op_spans + product_code_spans + filter_spans + listing_spans

    # 7. 生成 BIO 标签
    chars, tags = spans_to_bio_tags(text, all_spans)

    return {
        "text": text,
        "chars": chars,
        "labels": tags,
    }


# ============ 数据生成函数 ============

def generate_data_query_ner_data(samples_per_group):
    """生成 DATA_QUERY 类型的 NER 标注数据"""
    data = []
    for sub_type, templates in DATA_QUERY_TEMPLATES.items():
        for _ in range(samples_per_group):
            template = random.choice(templates)
            sample = generate_ner_sample(template, ENTITIES)
            sample["source"] = f"DATA_QUERY/{sub_type}"
            data.append(sample)
    return data


def generate_hybrid_ner_data(samples):
    """生成 HYBRID 类型的 NER 标注数据"""
    data = []
    for _ in range(samples):
        template = random.choice(HYBRID_TEMPLATES)
        sample = generate_ner_sample(template, ENTITIES)
        sample["source"] = "HYBRID"
        data.append(sample)
    return data


def generate_operation_ner_data(samples_per_op):
    """生成 DATA_OPERATION 类型的 NER 标注数据"""
    data = []
    for sub_type, templates in DATA_OPERATION_TEMPLATES.items():
        for _ in range(samples_per_op):
            template = random.choice(templates)
            sample = generate_ner_sample(template, ENTITIES)
            sample["source"] = f"DATA_OPERATION/{sub_type}"
            data.append(sample)
    return data


def generate_chat_ner_data(samples):
    """生成无实体的负样本 (GENERAL_CHAT)"""
    data = []
    for _ in range(samples):
        text = random.choice(GENERAL_CHAT_TEMPLATES)
        # 添加一些随机变化
        if random.random() > 0.5:
            text = text + "～"
        if random.random() > 0.7:
            text = text + "！"

        chars = list(text)
        tags = ["O"] * len(chars)

        data.append({
            "text": text,
            "chars": chars,
            "labels": tags,
            "source": "GENERAL_CHAT",
        })
    return data


def generate_real_scenario_data(samples=150):
    """生成真实场景数据（基于用户实际问题）"""
    data = []
    for _ in range(samples):
        template = random.choice(REAL_SCENARIO_TEMPLATES)
        sample = generate_ner_sample(template, ENTITIES)
        sample["source"] = "REAL_SCENARIO"
        data.append(sample)
    return data


def generate_complex_query_data(samples=200):
    """生成复杂查询数据（包含地理位置、组织、过滤条件）"""
    data = []
    for _ in range(samples):
        template = random.choice(COMPLEX_QUERY_TEMPLATES)
        sample = generate_ner_sample(template, ENTITIES)
        sample["source"] = "COMPLEX_QUERY"
        data.append(sample)
    return data


def generate_error_based_ner_data(samples=300):
    """生成基于错误分析的针对性训练数据（时间、财年、SS、产品系列）"""
    data = []
    for _ in range(samples):
        template = random.choice(ERROR_BASED_TEMPLATES)
        try:
            sample = generate_ner_sample(template, ENTITIES)
            sample["source"] = "ERROR_BASED"
            data.append(sample)
        except (KeyError, TypeError):
            continue
    return data


# ============ 数据增强: 同义词替换 ============

# 聚合函数同义词组
AGG_SYNONYM_GROUPS = [
    ["总", "总计", "合计", "累计", "总和", "汇总"],
    ["平均", "均值", "平均值", "平均数"],
    ["最大", "最高", "峰值"],
    ["最小", "最低"],
]

# 比较操作同义词组
OP_SYNONYM_GROUPS = [
    ["大于", "超过", "高于", "多于"],
    ["小于", "低于", "不足", "少于"],
]

# 聚合函数增强模板 (直接包含聚合词)
AGG_AUGMENT_TEMPLATES = [
    "{agg_word}{entity}的{metric}",
    "{entity}的{metric}的{agg_word}",
    "计算{entity}的{metric}{agg_word}",
    "查询{time_range}{entity}的{metric}{agg_word}",
    "{time_range}{entity}的{metric}{agg_word}是多少",
]

# 比较操作增强模板
OP_AUGMENT_TEMPLATES = [
    "查询{metric}{op_word}{value}的{entity}",
    "{metric}{op_word}{value}的{entity}有哪些",
    "找出{metric}{op_word}{value}的{entity}",
    "筛选{metric}{op_word}{value}的{entity}",
]

# 聚合词实体库
AGG_WORDS = [
    "总计", "合计", "累计", "汇总", "求和", "总和", "总额",
    "平均", "均值", "平均值",
    "最大值", "最小值", "最高", "最低",
]

# 比较操作词实体库
OP_WORDS = [
    "大于", "超过", "高于", "多于",
    "小于", "低于", "不足", "少于",
]


# ============ 真实场景模板（基于用户实际问题）============
REAL_SCENARIO_TEMPLATES = [
    # 产品编号 + 指标查询
    "{time_range}{product_code}{metric}了多少",
    "{time_range}{product_code}都{metric}了多少",
    "{product_code}在{time_range}的{metric}是多少",
    "查询{product_code}的{metric}",
    "{product_code}{metric}多少",
    
    # 出货/库存类查询
    "{time_range}{entity}出了多少货",
    "{time_range}{product_code}出货量",
    "{product_code}的库存还有多少",
    "查看{product_code}的在途量",
    
    # 多产品对比
    "{product_code}和{product_code}的{metric}对比",
    "对比{product_code}与{product_code}",
    
    # 详细查询
    "{product_code}的详细{metric}数据",
    "显示{product_code}各项指标",
]


# ============ 复杂查询模板（包含地理位置、组织）============
COMPLEX_QUERY_TEMPLATES = [
    # 地理位置相关
    "{time_range}{location}的{metric}",
    "{location}{entity}的{metric}统计",
    "从{location}到{location}的{metric}",
    "{time_range}{location}的{entity}{metric}是多少",
    
    # 组织相关
    "从{organization}出货的{metric}",
    "{organization}的{entity}{metric}",
    "{time_range}{organization}的{metric}统计",
    "{organization}和{organization}的{metric}对比",
    
    # 复杂过滤（地理+组织）
    "{time_range}从{organization}出货{location}的{entity}{metric}",
    "从{organization}向{location}出货的{product_code}{metric}",
    "{location}的{organization}{metric}统计",
    "{time_range}从{organization}到{location}的{metric}",
    
    # 过滤条件
    "从{organization}出货的{entity}",
    "到{location}的{entity}{metric}",
    "在{location}的{entity}",
    "{entity}从{organization}到{location}",
    
    # === 业务术语场景 ===
    "{time_range}各个geo的{metric}",
    "{time_range}各geo{metric}如何",
    "各region的{entity}{metric}统计",
    "各area的{metric}是多少",
    "{time_range}各个区域{metric}对比",
    "{entity}在各geo的{metric}",
    
    # === 产品系列+复合时间场景 ===
    "{product_code}在{time_range}各个{location}的{metric}",
    "{product_code}的{product_code}在{time_range}各{location}{metric}",
    "{time_range}{product_code}各{location}的{metric}",
]

# ============ 错误分析驱动的增强模板 ============
# 基于增强sql.xlsx中48个错误样本的针对性训练（避免复杂组合导致的标注冲突）
ERROR_BASED_TEMPLATES = [
    # 跨年时间范围
    "{time_range}{product_code}{metric}",
    "{product_code}{time_range}{metric}",
    "{time_range}{product_code}出货出了多少",
    "2023年3月到2024年4月{product_code}出货出了多少",
    "2024年3月到2025年1月{product_code}{metric}",
    # 财年相关
    "FY23/24年Q3的每个产品的出货数据",
    "FY23年{product_code}的出货量",
    "2024年第一季度所有NB产品的出货量多少",
    "FY24上半年所有产品的出货量一共多少",
    # SS/上市相关（使用简短listing_term避免重叠）
    "{product_code}上市后到现在的出货量",
    "{product_code}从上市后的三个月内共出货多少",
    # 前两个Q
    "2024年前两个Q一共出了多少13寸的量",
    "2024年前两个Q{product_code}和Intel分别出了多少",
    # 产品系列
    "Ideapad FY23全年总出货量",
    "LOQ在FY23全年的出货量",
    "IPS3 15IAH8在2023年全年的出货量",
    # 多条件组合（简化）
    "IPPro5 14IRH8和16IRH8从上市后的三个月内共出货多少",
]


def generate_augmented_agg_data(samples=100):
    """生成聚合函数增强样本"""
    data = []
    for _ in range(samples):
        template = random.choice(AGG_AUGMENT_TEMPLATES)
        agg_word = random.choice(AGG_WORDS)

        # 构建增强实体库
        aug_entities = {**ENTITIES, "agg_word": [agg_word]}

        # 使用自定义的占位符类型映射
        entity_spans = []
        placeholder_pattern = re.compile(r'\{(\w+)\}')

        result = ""
        last_end = 0

        for match in placeholder_pattern.finditer(template):
            key = match.group(1)
            placeholder_start = match.start()
            placeholder_end = match.end()

            result += template[last_end:placeholder_start]

            if key in aug_entities:
                value = random.choice(aug_entities[key])

                # 确定实体类型
                if key == "agg_word":
                    etype = "AGG"
                else:
                    etype = PLACEHOLDER_TYPE_MAP.get(key)

                if etype:
                    entity_start = len(result)
                    entity_end = entity_start + len(value)
                    entity_spans.append((entity_start, entity_end, etype, value))

                result += value
            else:
                result += match.group(0)

            last_end = placeholder_end

        result += template[last_end:]

        chars, tags = spans_to_bio_tags(result, entity_spans)
        data.append({
            "text": result,
            "chars": chars,
            "labels": tags,
            "source": "AUGMENT_AGG",
        })
    return data


def generate_augmented_op_data(samples=80):
    """生成比较操作增强样本"""
    data = []
    for _ in range(samples):
        template = random.choice(OP_AUGMENT_TEMPLATES)
        op_word = random.choice(OP_WORDS)

        aug_entities = {**ENTITIES, "op_word": [op_word]}

        entity_spans = []
        placeholder_pattern = re.compile(r'\{(\w+)\}')

        result = ""
        last_end = 0

        for match in placeholder_pattern.finditer(template):
            key = match.group(1)
            placeholder_start = match.start()
            placeholder_end = match.end()

            result += template[last_end:placeholder_start]

            if key in aug_entities:
                value = random.choice(aug_entities[key])

                if key == "op_word":
                    etype = "OP"
                else:
                    etype = PLACEHOLDER_TYPE_MAP.get(key)

                if etype:
                    entity_start = len(result)
                    entity_end = entity_start + len(value)
                    entity_spans.append((entity_start, entity_end, etype, value))

                result += value
            else:
                result += match.group(0)

            last_end = placeholder_end

        result += template[last_end:]

        chars, tags = spans_to_bio_tags(result, entity_spans)
        data.append({
            "text": result,
            "chars": chars,
            "labels": tags,
            "source": "AUGMENT_OP",
        })
    return data


# ============ 验证函数 ============

def validate_sample(sample):
    """验证样本的 chars 和 labels 长度一致, 标签合法"""
    errors = []

    if len(sample["chars"]) != len(sample["labels"]):
        errors.append(
            f"长度不一致: chars={len(sample['chars'])}, "
            f"labels={len(sample['labels'])}, text={sample['text']}"
        )

    if len(sample["text"]) != len(sample["chars"]):
        errors.append(
            f"文本长度不一致: text_len={len(sample['text'])}, "
            f"chars_len={len(sample['chars'])}"
        )

    for label in sample["labels"]:
        if label not in LABEL_SET:
            errors.append(f"未知标签: {label}")

    # 检查 BIO 一致性: I-X 之前必须是 B-X 或 I-X
    prev_label = "O"
    for i, label in enumerate(sample["labels"]):
        if label.startswith("I-"):
            entity_type = label[2:]
            if not (prev_label == f"B-{entity_type}" or prev_label == f"I-{entity_type}"):
                errors.append(
                    f"BIO 不一致: 位置 {i}, 标签 {label}, "
                    f"前一标签 {prev_label}"
                )
        prev_label = label

    return errors


def print_sample_detail(sample):
    """打印样本详情，包括实体信息"""
    print(f"  文本: {sample['text']}")

    # 提取实体
    entities = []
    current_entity = None
    for i, (char, label) in enumerate(zip(sample['chars'], sample['labels'])):
        if label.startswith("B-"):
            if current_entity:
                entities.append(current_entity)
            current_entity = {"type": label[2:], "text": char, "start": i}
        elif label.startswith("I-") and current_entity:
            current_entity["text"] += char
        else:
            if current_entity:
                entities.append(current_entity)
                current_entity = None
    if current_entity:
        entities.append(current_entity)

    if entities:
        for ent in entities:
            print(f"    [{ent['type']:8s}] {ent['text']}")
    else:
        print(f"    (无实体)")


# ============ 主函数 ============

def generate_all_ner_data():
    """生成所有 NER 训练数据"""
    print("=" * 60)
    print("生成 NER BIO 标注训练数据")
    print("=" * 60)

    all_data = []

    # 0. 加载手动标注的真实样本（如果存在）
    manual_samples_path = "./manual_ner_samples.json"
    if os.path.exists(manual_samples_path):
        print(f"\n加载手动标注样本: {manual_samples_path}")
        try:
            with open(manual_samples_path, 'r', encoding='utf-8') as f:
                manual_samples = json.load(f)
            print(f"  [OK] 手动标注: {len(manual_samples)} 条（高质量）")
            # 复制多份以提高权重
            for _ in range(3):
                all_data.extend(manual_samples)
        except Exception as e:
            print(f"  [WARN] 加载失败: {e}")
    else:
        print(f"\n未找到手动标注样本文件: {manual_samples_path}")

    # 1. DATA_QUERY
    print(f"\n生成 DATA_QUERY NER 数据...")
    dq_data = generate_data_query_ner_data(Config.SAMPLES_PER_TEMPLATE_GROUP)
    print(f"  [OK] DATA_QUERY: {len(dq_data)} 条")
    all_data.extend(dq_data)

    # 2. HYBRID
    print(f"生成 HYBRID NER 数据...")
    hybrid_data = generate_hybrid_ner_data(Config.SAMPLES_HYBRID)
    print(f"  [OK] HYBRID: {len(hybrid_data)} 条")
    all_data.extend(hybrid_data)

    # 3. DATA_OPERATION
    print(f"生成 DATA_OPERATION NER 数据...")
    op_data = generate_operation_ner_data(Config.SAMPLES_PER_OPERATION)
    print(f"  [OK] DATA_OPERATION: {len(op_data)} 条")
    all_data.extend(op_data)

    # 4. GENERAL_CHAT (负样本)
    print(f"生成 GENERAL_CHAT NER 数据 (负样本)...")
    chat_data = generate_chat_ner_data(Config.SAMPLES_GENERAL_CHAT)
    print(f"  [OK] GENERAL_CHAT: {len(chat_data)} 条")
    all_data.extend(chat_data)

    # 5. 聚合函数增强
    print(f"生成聚合函数增强数据...")
    agg_aug_data = generate_augmented_agg_data(100)
    print(f"  [OK] AGG 增强: {len(agg_aug_data)} 条")
    all_data.extend(agg_aug_data)

    # 6. 比较操作增强
    print(f"生成比较操作增强数据...")
    op_aug_data = generate_augmented_op_data(80)
    print(f"  [OK] OP 增强: {len(op_aug_data)} 条")
    all_data.extend(op_aug_data)

    # 7. 真实场景数据（基于用户实际问题）
    print(f"生成真实场景数据...")
    real_scenario_data = generate_real_scenario_data(150)
    print(f"  [OK] 真实场景: {len(real_scenario_data)} 条")
    all_data.extend(real_scenario_data)

    # 8. 复杂查询数据（地理位置、组织、过滤条件）
    print(f"生成复杂查询数据...")
    complex_query_data = generate_complex_query_data(200)
    print(f"  [OK] 复杂查询: {len(complex_query_data)} 条")
    all_data.extend(complex_query_data)

    # 9. 错误分析驱动的针对性数据（时间、财年、SS、跨年）
    print(f"生成错误分析驱动数据...")
    error_based_data = generate_error_based_ner_data(300)
    print(f"  [OK] 错误分析驱动: {len(error_based_data)} 条")
    all_data.extend(error_based_data)

    # 验证所有样本
    print(f"\n验证数据...")
    error_count = 0
    for i, sample in enumerate(all_data):
        errors = validate_sample(sample)
        if errors:
            error_count += 1
            print(f"  [ERROR] 样本 {i} ({sample.get('source', '?')}):")
            for err in errors:
                print(f"    - {err}")
            if error_count <= 3:
                print_sample_detail(sample)

    if error_count == 0:
        print(f"  [OK] 全部 {len(all_data)} 条验证通过")
    else:
        print(f"  [WARN] {error_count} 条样本存在问题")

    # 打乱数据
    random.shuffle(all_data)

    # 统计
    label_counts = Counter()
    entity_type_counts = Counter()
    source_counts = Counter()

    for sample in all_data:
        source_counts[sample.get("source", "unknown")] += 1
        for label in sample["labels"]:
            label_counts[label] += 1
            if label.startswith("B-"):
                entity_type_counts[label[2:]] += 1

    print(f"\n{'=' * 60}")
    print(f"总计生成: {len(all_data)} 条")
    print(f"{'=' * 60}")

    print(f"\n数据来源分布:")
    for source, count in sorted(source_counts.items()):
        print(f"  {source}: {count}")

    print(f"\n标签分布:")
    for label, count in sorted(label_counts.items()):
        pct = count / sum(label_counts.values()) * 100
        print(f"  {label:12s}: {count:6d} ({pct:.1f}%)")

    print(f"\n实体类型分布 (按 B- 标签计数):")
    for etype, count in sorted(entity_type_counts.items(), key=lambda x: -x[1]):
        print(f"  {etype:8s}: {count}")

    # 保存 (去掉 source 字段, 训练不需要)
    output_data = []
    for sample in all_data:
        output_data.append({
            "text": sample["text"],
            "chars": sample["chars"],
            "labels": sample["labels"],
        })

    print(f"\n保存到: {Config.OUTPUT_PATH}")
    with open(Config.OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)
    print("[OK] 保存完成")

    # 显示样本示例
    print(f"\n样本示例:")
    print("-" * 60)
    shown = 0
    for sample in all_data:
        # 展示有实体的样本
        if any(l != "O" for l in sample["labels"]):
            print_sample_detail(sample)
            print()
            shown += 1
            if shown >= 8:
                break

    # 也展示几个无实体样本
    print("负样本示例:")
    shown = 0
    for sample in all_data:
        if all(l == "O" for l in sample["labels"]):
            print_sample_detail(sample)
            shown += 1
            if shown >= 3:
                break

    return all_data


if __name__ == "__main__":
    random.seed(42)
    generate_all_ner_data()
    print("\n" + "=" * 60)
    print("NER 训练数据生成完成！")
    print("=" * 60)
