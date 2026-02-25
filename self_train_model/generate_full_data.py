"""
完整的意图识别训练数据生成脚本
运行: python generate_full_data.py
"""

import json
import random
from collections import Counter
from itertools import product

# ============ 配置 ============
class Config:
    SAMPLES_PER_INTENT = 50      # 每种查询子类型的样本数
    SAMPLES_HYBRID = 80           # 混合查询样本数
    SAMPLES_PER_OPERATION = 40    # 每种数据操作的样本数
    SAMPLES_GENERAL_CHAT = 150    # 普通对话样本数
    OUTPUT_PATH = "./synthetic_training_full.json"

# ============ DATA_QUERY 查询子类型模板 ============
DATA_QUERY_TEMPLATES = {
    # ============ 聚合查询类 ============
    "AGGREGATION_SUM": [
        "查询{time_range}{entity}的总{metric}",
        "统计{time_range}{entity}的{metric}总和",
        "{time_range}{entity}的{metric}总计是多少",
        "计算{time_range}{entity}的{metric}总额",
        "{time_range}{entity}的{metric}一共多少",
        "汇总{time_range}{entity}的{metric}",
        "{entity}的{metric}总共有多少",
        "算一下{time_range}{entity}的{metric}总计"
    ],

    "AGGREGATION_COUNT": [
        "统计{entity}的数量",
        "{time_range}有多少个{entity}",
        "查询{entity}的总数",
        "{entity}的个数",
        "统计{time_range}{entity}的总数",
        "{entity}一共有多少",
        "数一下{entity}有多少",
        "{entity}总共多少个"
    ],

    "AGGREGATION_AVG": [
        "计算{entity}的平均{metric}",
        "{time_range}{entity}的{metric}平均值是多少",
        "统计{entity}的平均{metric}",
        "{entity}的{metric}均值",
        "查询{entity}的{metric}平均水平",
        "{entity}的{metric}平均是多少",
        "算一下{entity}的{metric}平均数"
    ],

    "AGGREGATION_MAX_MIN": [
        "查询{entity}的{metric}最大值",
        "{time_range}{entity}的{metric}最小值",
        "{entity}的{metric}最高和最低",
        "找出{metric}的极值",
        "{metric}的最大最小值",
        "查询{entity}{metric}的峰值",
        "{entity}的{metric}最高是多少",
        "{entity}的{metric}最低是多少"
    ],

    # ============ 明细查询类 ============
    "DETAIL_LIST": [
        "列出所有{entity}",
        "显示{entity}的详细信息",
        "查询{entity}列表",
        "展示所有{entity}的资料",
        "查看全部{entity}",
        "显示{entity}清单",
        "查看{entity}的全部信息",
        "把{entity}都列出来"
    ],

    "DETAIL_SINGLE": [
        "查询{entity}ID为{entity_id}的详细信息",
        "显示{entity}{entity_id}的资料",
        "{entity}编号{entity_id}的具体信息",
        "查看{entity}{entity_id}详情",
        "{entity}{entity_id}的信息",
        "查一下{entity}{entity_id}",
        "{entity}{entity_id}的详细资料"
    ],

    "DETAIL_SEARCH": [
        "搜索包含{keyword}的{entity}",
        "查找{keyword}相关的{entity}",
        "检索{keyword}的{entity}信息",
        "找出含有{keyword}的{entity}",
        "搜索{keyword}",
        "查找{keyword}",
        "搜一下{keyword}",
        "{keyword}的{entity}有哪些"
    ],

    # ============ 分析查询类 ============
    "TREND_ANALYSIS": [
        "分析{time_range}{metric}的变化趋势",
        "展示{metric}的走势",
        "{metric}在{time_range}的趋势如何",
        "查看{metric}的变化曲线",
        "{metric}的趋势分析",
        "显示{metric}的上升下降趋势",
        "{metric}的变化情况",
        "{metric}走势怎么样"
    ],

    "COMPARISON_ANALYSIS": [
        "对比{time_range1}和{time_range2}的{metric}",
        "比较{entity1}和{entity2}的{metric}",
        "{metric}的同比环比",
        "{entity}与{entity2}的{metric}对比",
        "比较{time_range}和{time_range2}的{metric}差异",
        "{metric}的对比分析",
        "{entity1}比{entity2}的{metric}怎么样",
        "{time_range1}和{time_range2}{metric}差多少"
    ],

    "RANKING_ANALYSIS": [
        "查询{metric}最高的{top_n}个{entity}",
        "{metric}排名前{top_n}的{entity}",
        "列出{metric}最大的{top_n}个{entity}",
        "{metric}top{top_n}的{entity}是哪些",
        "显示{metric}排行榜前{top_n}",
        "{metric}最高的{top_n}名",
        "{metric}前{top_n}名",
        "按{metric}排名前{top_n}"
    ],

    "DISTRIBUTION_ANALYSIS": [
        "分析{entity}的{metric}分布",
        "统计{metric}的分布情况",
        "{metric}在{entity}中的分布",
        "查看{entity}按{metric}的分布",
        "{metric}的分布特征",
        "{entity}的{metric}分布图",
        "{metric}都分布在哪些范围",
        "{entity}的{metric}分布如何"
    ],

    # ============ 关联查询类 ============
    "JOIN_QUERY": [
        "查询{entity}和{entity2}的关联数据",
        "显示{entity}及其对应的{entity2}",
        "{entity}关联{entity2}的信息",
        "联合查询{entity}和{entity2}",
        "查询{entity}对应的{entity2}列表",
        "{entity}与{entity2}的关系",
        "{entity}对应的{entity2}有哪些",
        "查看{entity}关联的{entity2}"
    ],

    "SUB_QUERY": [
        "查询{metric}大于{value}的{entity}",
        "找出{metric}排名前{top_n}%的{entity}",
        "筛选{metric}高于平均值的{entity}",
        "查找{metric}在指定范围的{entity}",
        "显示{metric}超过{value}的{entity}",
        "{metric}大于{value}的{entity}有哪些",
        "{metric}超过{value}的{entity}",
        "{metric}排名靠前的{entity}"
    ],

    # ============ 元数据查询 ============
    "METADATA_QUERY": [
        "显示{entity}的字段信息",
        "查询{entity}的表结构",
        "{entity}有哪些列",
        "查看{entity}的schema",
        "{entity}的字段定义",
        "查询{entity}的结构信息",
        "{entity}包含哪些字段",
        "列出{entity}的所有列",
        "{entity}表有什么字段",
        "看看{entity}表的列",
        "查看{entity}表的字段",
        "{entity}表包含什么列",
        "{entity}表的列信息",
        "显示{entity}表的schema",
        "{entity}的表结构是什么",
        "{entity}表有哪些属性",
        "查询{entity}表的列名",
        "{entity}表字段有哪些",
        "看看{entity}有哪些列",
        "{entity}表的字段列表"
    ],

    # ============ 归因分析类 ============
    "ROOT_CAUSE_ANALYSIS": [
        "分析{metric}{change_direction}的原因",
        "{metric}为什么{change_direction}",
        "{metric}{change_direction}的主要因素",
        "找出{metric}{change_direction}的根源",
        "{metric}{change_direction}归因分析",
        "分析{metric}变化的原因",
        "{metric}波动的原因",
        "{metric}异常的原因",
        "{metric}下降的原因",
        "{metric}上升的原因",
        "分析{metric}变化趋势的原因",
        "{metric}变化的主要驱动因素",
        "{metric}变化归因",
        "分析{entity}的{metric}{change_direction}原因",
        "{entity}的{metric}为什么{change_direction}"
    ]
}

# ============ 实体库 ============
ENTITIES = {
    "time_range": ["上个月", "本月", "上个季度", "今年", "最近7天", "2024年", "本周", "去年", "下个月"],
    "time_range1": ["上个月", "去年", "上周", "前一季度"],
    "time_range2": ["本月", "今年", "本周", "本季度"],
    "entity": ["产品", "用户", "部门", "地区", "订单", "客户"],
    "entity2": ["订单", "产品", "部门", "客户"],
    "entity1": ["产品", "用户", "地区"],
    "entity_id": ["A001", "B002", "C003", "123", "456"],
    "metric": ["销售额", "营收", "利润", "数量", "客单价", "转化率", "成本", "满意度"],
    "top_n": ["3", "5", "10", "20", "50"],
    "keyword": ["手机", "电脑", "配件", "电子产品", "服装", "食品"],
    "value": ["1000", "5000", "10000", "50%", "80分"],
    "change_direction": ["下降", "上升", "下跌", "增长", "减少", "增加", "波动", "异常"]
}

# ============ HYBRID 模板 ============
HYBRID_TEMPLATES = [
    # 查询 + 可视化
    "查询{entity}的{metric}并用图表展示",
    "显示{time_range}{entity}的{metric}趋势图",
    "帮我看一下{entity}的数据然后生成报表",
    "查询{entity}信息并做成可视化",
    "统计{entity}的{metric}然后用柱状图显示",
    "生成{entity}的{metric}分布图",
    "画出{time_range}{metric}的变化曲线",
    "用饼图展示{entity}的{metric}分布",

    # 多步骤查询
    "先查询{entity}列表再统计{metric}",
    "找出{metric}最高的{entity}然后分析趋势",
    "显示{entity}详情并计算{metric}",
    "查询{entity}信息同时对比{time_range1}和{time_range2}",
    "先列出{entity}再排名",
    "查看{entity}的详情并导出报表",

    # 导出 + 查询
    "查询{entity}数据并导出Excel",
    "统计{time_range}{metric}并生成PDF报告",
    "把{entity}的{metric}数据查出来并发送邮件",
    "导出{entity}列表并保存",
    "生成{entity}报表并发送",

    # 对话 + 查询
    "你好，帮我查一下{entity}的{metric}",
    "麻烦查一下{time_range}{entity}的数据，谢谢",
    "请帮我显示{entity}的{metric}分布图",
    "帮我看看{entity}的情况",
    "查查{entity}然后做个总结",

    # 复杂组合
    "分析{entity}的{metric}趋势并预测下个月",
    "对比{entity1}和{entity2}的差异并生成对比图",
    "统计{entity}的各项指标并用仪表盘展示",
    "查询{metric}排名前10的{entity}并分析原因",
    "查看{entity}分布情况并给出建议"
]

# ============ DATA_OPERATION 模板 ============
DATA_OPERATION_TEMPLATES = {
    "CREATE_OPERATION": [
        "创建一个新的{entity}",
        "添加{entity}信息",
        "新增{entity}记录",
        "插入{entity}数据",
        "建一个{entity}",
        "增加一个{entity}",
        "录入{entity}信息",
        "注册新的{entity}",
        "添加{entity}到系统",
        "创建{entity}条目",
        "新建{entity}档案",
        "增加{entity}"
    ],

    "UPDATE_OPERATION": [
        "更新{entity}的信息",
        "修改{entity}的{metric}",
        "编辑{entity}数据",
        "更改{entity}记录",
        "把{entity}的{metric}改成{value}",
        "更新{entity}状态",
        "调整{entity}参数",
        "变更{entity}信息",
        "修改{entity}资料",
        "更新{entity}的详细信息",
        "编辑一下{entity}",
        "更改{entity}配置"
    ],

    "DELETE_OPERATION": [
        "删除{entity}记录",
        "移除这个{entity}",
        "清除{entity}数据",
        "删除ID为{entity_id}的{entity}",
        "去掉这个{entity}",
        "移除{entity}",
        "销毁{entity}记录",
        "清空{entity}",
        "删除{entity}信息",
        "移除{entity}条目",
        "把这个{entity}删了",
        "清除{entity}档案"
    ],

    "EXPORT_OPERATION": [
        "导出{entity}数据",
        "把{entity}保存为Excel",
        "下载{entity}报表",
        "生成{entity}的CSV文件",
        "输出{entity}数据到文件",
        "将{entity}导出为PDF",
        "备份{entity}数据",
        "导出{entity}列表",
        "下载{entity}信息",
        "生成{entity}报告文件",
        "把{entity}数据导出来"
    ]
}

# ============ GENERAL_CHAT 模板 ============
GENERAL_CHAT_TEMPLATES = [
    # 问候
    "你好", "您好", "早上好", "下午好", "晚上好", "大家好",
    "嗨", "hello", "hi", "在吗",

    # 感谢
    "谢谢", "感谢", "非常感谢", "谢谢你的帮助", "多谢", "谢谢你",

    # 告别
    "再见", "拜拜", "走了", "下次聊", "回见",

    # 询问功能
    "这是什么", "怎么用", "怎么操作", "如何使用",
    "有什么功能", "能做什么", "支持哪些操作", "介绍一下功能",

    # 请求帮助
    "帮助", "help", "求助", "可以帮我吗", "帮帮我", "需要帮助",

    # 疑问
    "不明白", "什么意思", "解释一下", "怎么说", "什么情况",
    "为什么", "怎么回事", "不太懂", "听不懂",

    # 其他闲聊
    "好的", "可以", "行", "没问题", "知道了", "收到",
    "了解", "清楚了", "明白", "懂了", "OK", "好的好的",

    # 系统相关
    "使用教程", "操作指南", "联系方式", "客服", "人工服务",
    "技术支持", "在线咨询", "有什么问题", "出错了", "报错了",

    # XX什么类型 - 用于修复"GMV什么"误判为归因分析的问题
    "GMV什么", "销售额什么", "利润什么", "营收什么", "成本什么",
    "转化率什么", "客单价什么", "数量什么", "满意度什么",
    "用户什么", "产品什么", "订单什么", "客户什么", "地区什么",
    "部门什么", "数据什么", "报表什么", "图表什么"
]

# ============ 数据生成函数 ============
def fill_template(template, entities):
    """填充模板中的占位符"""
    text = template
    for key, values in entities.items():
        placeholder = "{" + key + "}"
        if placeholder in text:
            text = text.replace(placeholder, random.choice(values))
    return text

def generate_data_query_data(samples_per_intent):
    """生成 DATA_QUERY 类型的数据"""
    data = []
    for sub_type, templates in DATA_QUERY_TEMPLATES.items():
        for _ in range(samples_per_intent):
            template = random.choice(templates)
            text = fill_template(template, ENTITIES)
            data.append({
                "text": text,
                "category": "DATA_QUERY",
                "sub_type": sub_type,
                "confidence": 1.0
            })
    return data

def generate_hybrid_data(samples):
    """生成 HYBRID 类型的数据"""
    data = []
    for _ in range(samples):
        template = random.choice(HYBRID_TEMPLATES)
        text = fill_template(template, ENTITIES)
        data.append({
            "text": text,
            "category": "HYBRID",
            "sub_type": "HYBRID_QUERY",
            "confidence": 1.0
        })
    return data

def generate_data_operation_data(samples_per_operation):
    """生成 DATA_OPERATION 类型的数据"""
    data = []
    for sub_type, templates in DATA_OPERATION_TEMPLATES.items():
        for _ in range(samples_per_operation):
            template = random.choice(templates)
            text = fill_template(template, ENTITIES)
            data.append({
                "text": text,
                "category": "DATA_OPERATION",
                "sub_type": sub_type,
                "confidence": 1.0
            })
    return data

def generate_general_chat_data(samples):
    """生成 GENERAL_CHAT 类型的数据"""
    data = []
    for _ in range(samples):
        text = random.choice(GENERAL_CHAT_TEMPLATES)
        # 添加一些随机变化
        if random.random() > 0.5:
            text = text + "～"
        if random.random() > 0.7:
            text = text + "！"
        data.append({
            "text": text,
            "category": "GENERAL_CHAT",
            "sub_type": "CHAT",  # 给一个明确的子类型
            "confidence": 1.0
        })
    return data

def generate_all_data():
    """生成所有训练数据"""
    print("=" * 60)
    print("生成意图识别训练数据")
    print("=" * 60)

    all_data = []

    # 1. DATA_QUERY
    print(f"\n生成 DATA_QUERY 数据...")
    data_query_data = generate_data_query_data(Config.SAMPLES_PER_INTENT)
    print(f"  [OK] DATA_QUERY: {len(data_query_data)} 条")
    all_data.extend(data_query_data)

    # 2. HYBRID
    print(f"生成 HYBRID 数据...")
    hybrid_data = generate_hybrid_data(Config.SAMPLES_HYBRID)
    print(f"  [OK] HYBRID: {len(hybrid_data)} 条")
    all_data.extend(hybrid_data)

    # 3. DATA_OPERATION
    print(f"生成 DATA_OPERATION 数据...")
    operation_data = generate_data_operation_data(Config.SAMPLES_PER_OPERATION)
    print(f"  [OK] DATA_OPERATION: {len(operation_data)} 条")
    all_data.extend(operation_data)

    # 4. GENERAL_CHAT
    print(f"生成 GENERAL_CHAT 数据...")
    chat_data = generate_general_chat_data(Config.SAMPLES_GENERAL_CHAT)
    print(f"  [OK] GENERAL_CHAT: {len(chat_data)} 条")
    all_data.extend(chat_data)

    # 打乱数据
    random.shuffle(all_data)

    # 统计信息
    category_dist = Counter(d['category'] for d in all_data)
    subtype_dist = Counter(d['sub_type'] for d in all_data)

    print(f"\n{'=' * 60}")
    print(f"总计生成: {len(all_data)} 条")
    print(f"{'=' * 60}")

    print(f"\n一级分类分布:")
    for cat, count in sorted(category_dist.items()):
        print(f"  {cat}: {count}")

    print(f"\n二级分类分布 ({len(subtype_dist)} 种):")
    for sub, count in sorted(subtype_dist.items()):
        print(f"  {sub}: {count}")

    # 保存数据
    print(f"\n保存到: {Config.OUTPUT_PATH}")
    with open(Config.OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(all_data, f, ensure_ascii=False, indent=2)
    print("[OK] 保存完成")

    # 显示样本示例
    print(f"\n样本示例:")
    print("-" * 60)
    for item in all_data[:10]:
        print(f"[{item['category']}/{item['sub_type']}] {item['text']}")

    return all_data

# ============ 主函数 ============
if __name__ == "__main__":
    random.seed(42)  # 固定随机种子以保证可重现
    generate_all_data()
    print("\n" + "=" * 60)
    print("数据生成完成！")
    print("=" * 60)
