# 归因分析意图识别模型修改方案

## 一、概述

本文档描述如何在现有的意图识别模型中添加"归因分析"（ROOT_CAUSE_ANALYSIS）意图类型。

## 二、"GMV什么"误判为归因分析的原因分析

### 2.1 问题现象
用户输入"GMV什么"时，模型误判为归因分析（ROOT_CAUSE_ANALYSIS），而实际应该是：
- 如果询问GMV的定义/含义 → GENERAL_CHAT
- 如果询问GMV的数据 → DATA_QUERY（如 DETAIL_LIST 或 METADATA_QUERY）

### 2.2 根本原因

| 原因 | 说明 |
|------|------|
| **1. "什么"关键词歧义** | "什么"在多种意图类型中都出现，包括归因分析模板中的"原因是什么" |
| **2. 简短查询缺乏上下文** | "GMV什么"只有3个字，缺乏明确的意图指示词（如"查询"、"统计"、"为什么"） |
| **3. 训练数据不平衡** | 缺少"XX什么"这种简短查询的训练样本 |
| **4. 归因分析模板干扰** | 归因分析模板包含"{metric}{change_direction}的原因是什么"，"什么"可能被模型过度关联 |

### 2.3 训练数据分析

从 [`synthetic_training.json`](self_train_model/synthetic_training.json) 中发现：

**"什么"在 GENERAL_CHAT 中的使用：**
- "这是什么" → GENERAL_CHAT/CHAT
- "什么意思" → GENERAL_CHAT/CHAT
- "什么情况" → GENERAL_CHAT/CHAT

**"什么"在归因分析模板中的使用（计划添加）：**
- "{metric}{change_direction}的原因是什么" → ROOT_CAUSE_ANALYSIS

**问题：** 模型可能将"什么"与归因分析过度关联，导致"GMV什么"被误判。

### 2.4 解决方案

| 方案 | 具体措施 |
|------|----------|
| **1. 优化归因分析模板** | 避免使用"什么"作为关键词，改用更明确的表达 |
| **2. 添加简短查询样本** | 在 GENERAL_CHAT 中添加"XX什么"类型的样本 |
| **3. 增强意图区分度** | 为不同意图类型添加更多区分性强的关键词 |
| **4. 调整模板权重** | 在数据生成时增加某些模板的样本数量 |

### 2.5 修改后的归因分析模板

**原模板（包含"什么"）：**
```python
"{metric}{change_direction}的原因是什么"
```

**修改后（避免"什么"）：**
```python
"分析{metric}{change_direction}的原因"
"找出{metric}{change_direction}的归因"
"{metric}{change_direction}归因分析"
"诊断{metric}{change_direction}的根源"
```

### 2.6 新增 GENERAL_CHAT 简短查询模板

```python
# 在 GENERAL_CHAT_TEMPLATES 中添加
"XX什么",  # 如 "GMV什么"、"销售额什么"
"XX是什么",  # 如 "GMV是什么"
"XX什么意思",  # 如 "GMV什么意思"
```

## 三、现有模型结构分析

## 二、现有模型结构分析

### 2.1 一级分类（4种）
- `DATA_QUERY` - 数据查询
- `GENERAL_CHAT` - 普通对话
- `HYBRID` - 混合查询
- `DATA_OPERATION` - 数据操作

### 2.2 DATA_QUERY 子类型（15种）
| ID | 子类型 | 说明 |
|----|--------|------|
| 0 | AGGREGATION_SUM | 求和聚合 |
| 1 | AGGREGATION_COUNT | 计数聚合 |
| 2 | AGGREGATION_AVG | 平均值聚合 |
| 3 | AGGREGATION_MAX_MIN | 最大最小值 |
| 4 | DETAIL_LIST | 明细列表 |
| 5 | DETAIL_SINGLE | 单条明细 |
| 6 | DETAIL_SEARCH | 搜索明细 |
| 7 | TREND_ANALYSIS | 趋势分析 |
| 8 | COMPARISON_ANALYSIS | 对比分析 |
| 9 | RANKING_ANALYSIS | 排名分析 |
| 10 | DISTRIBUTION_ANALYSIS | 分布分析 |
| 11 | JOIN_QUERY | 关联查询 |
| 12 | SUB_QUERY | 子查询 |
| 13 | METADATA_QUERY | 元数据查询 |
| 14 | UNKNOWN_QUERY | 未知查询 |

## 三、归因分析意图设计

### 3.1 归因分析特点
归因分析是一种特殊的数据分析意图，用户询问数据变化的原因，例如：
- "为什么销售额下降了？"
- "GMV下跌的原因是什么？"
- "分析一下营收增长的原因"
- "为什么昨天的转化率比前天低？"

### 3.2 新增子类型
在 DATA_QUERY 类别下新增：
- **ROOT_CAUSE_ANALYSIS** (ID: 15) - 归因分析

### 3.3 归因分析数据模板

```python
ROOT_CAUSE_ANALYSIS_TEMPLATES = [
    # 基础归因询问
    "为什么{metric}{change_direction}了",
    "{metric}{change_direction}的原因是什么",
    "分析{metric}{change_direction}的原因",
    "{metric}为什么{change_direction}",
    "找出{metric}{change_direction}的原因",
    
    # 带时间范围的归因
    "为什么{time_range}{metric}{change_direction}",
    "{time_range}{metric}{change_direction}的原因",
    "分析{time_range}{metric}{change_direction}的原因",
    
    # 带实体的归因
    "为什么{entity}的{metric}{change_direction}",
    "{entity}的{metric}{change_direction}的原因是什么",
    "分析{entity}{metric}{change_direction}的原因",
    
    # 完整归因询问
    "为什么{time_range}{entity}的{metric}{change_direction}",
    "{time_range}{entity}的{metric}{change_direction}的原因是什么",
    "分析{time_range}{entity}的{metric}{change_direction}的原因",
    
    # 其他表达方式
    "{metric}{change_direction}归因",
    "{metric}{change_direction}的归因分析",
    "对{metric}{change_direction}进行归因",
    "帮我分析{metric}{change_direction}的原因",
    "请分析{time_range}{metric}{change_direction}的原因",
    
    # 具体场景
    "销售额下跌的原因",
    "为什么GMV下降了",
    "营收增长的原因是什么",
    "为什么昨天的转化率比前天低",
    "分析一下用户数量下降的原因",
    "利润上涨的原因是什么",
]
```

### 3.4 归因分析实体库扩展

```python
ATTRIBUTION_ENTITIES = {
    "change_direction": ["下降", "下跌", "降低", "减少", "下滑", "增长", "上涨", "提升", "增加", "上升"],
    # 复用现有实体
    "time_range": ["上个月", "本月", "上个季度", "今年", "最近7天", "昨天", "前天", "本周", "上周"],
    "entity": ["产品", "用户", "部门", "地区", "订单", "客户"],
    "metric": ["销售额", "营收", "利润", "数量", "客单价", "转化率", "GMV", "成本", "满意度"],
}
```

## 四、修改方案

### 4.1 修改数据生成代码 (data_generator.ipynb)

#### 修改点1：更新 DATA_QUERY_TEMPLATES
在 DATA_QUERY_TEMPLATES 字典中添加 ROOT_CAUSE_ANALYSIS 模板

#### 修改点2：更新 ENTITIES
添加 change_direction 实体

#### 修改点3：更新 Config
保持 SAMPLES_PER_INTENT = 50，新增的 ROOT_CAUSE_ANALYSIS 会自动生成 50 条数据

### 4.2 修改训练代码 (intent_classifier.ipynb)

#### 修改点1：更新 NUM_SUBTYPES
```python
NUM_SUBTYPES = 16  # 从 15 改为 16
```

#### 修改点2：更新 subtype2id 映射
添加 ROOT_CAUSE_ANALYSIS: 15

#### 修改点3：更新 id2subtype 映射
添加 15: "ROOT_CAUSE_ANALYSIS"

### 4.3 更新后端服务 (intent_api.py)

#### 修改点1：更新 ID2SUBTYPE
```python
ID2SUBTYPE = {
    # ... 现有映射 ...
    14: "UNKNOWN_QUERY",
    15: "ROOT_CAUSE_ANALYSIS"  # 新增
}
```

#### 修改点2：更新模型初始化参数
```python
num_subtypes=checkpoint['config'].get('num_subtypes', 16)  # 默认值改为 16
```

### 4.4 更新后端 Java 代码 (IntentType.java)

添加新的意图类型常量：
```java
public static final String ROOT_CAUSE_ANALYSIS = "ROOT_CAUSE_ANALYSIS";
```

## 五、实施步骤

### 步骤1：修改数据生成代码
1. 打开 `self_train_model/data_generator.ipynb`
2. 在 DATA_QUERY_TEMPLATES 中添加 ROOT_CAUSE_ANALYSIS 模板
3. 在 ENTITIES 中添加 change_direction 实体
4. 运行数据生成，生成新的训练数据

### 步骤2：修改训练代码
1. 打开 `self_train_model/intent_classifier.ipynb`
2. 更新 NUM_SUBTYPES = 16
3. 更新 subtype2id 和 id2subtype 映射
4. 重新训练模型

### 步骤3：更新后端服务
1. 修改 `backend/intent-service/intent_api.py`
2. 更新 ID2SUBTYPE 映射
3. 重启意图识别服务

### 步骤4：更新 Java 后端
1. 修改 `backend/src/main/java/com/chatbi/dto/IntentType.java`
2. 添加 ROOT_CAUSE_ANALYSIS 常量
3. 在 ChatService 中添加归因分析的处理逻辑

## 六、预期效果

### 6.1 数据分布
修改后总数据量：1140 条（原 1090 + 新增 50）

### 6.2 子类型分布
DATA_QUERY 下将有 16 种子类型，每种约 50 条数据

### 6.3 模型性能
由于归因分析与对比分析（COMPARISON_ANALYSIS）在语义上有一定相似性，需要确保：
- 训练数据足够区分这两种意图
- 模型能够准确识别归因分析的关键词（"为什么"、"原因"、"归因"等）

## 七、测试验证

### 7.1 单元测试
测试用例：
- "为什么销售额下降了？" → ROOT_CAUSE_ANALYSIS
- "对比上个月和本月的销售额" → COMPARISON_ANALYSIS
- "分析销售额下降的原因" → ROOT_CAUSE_ANALYSIS
- "销售额的趋势如何" → TREND_ANALYSIS

### 7.2 集成测试
1. 启动意图识别服务
2. 发送归因分析相关的测试请求
3. 验证返回的意图类型是否正确
4. 验证后端 ChatService 是否正确调用 DiagnosticService

## 八、注意事项

1. **数据平衡**：确保归因分析数据与其他子类型数量相当
2. **语义区分**：归因分析与对比分析容易混淆，需要精心设计模板
3. **模型兼容性**：更新模型后，需要重新部署所有相关服务
4. **向后兼容**：如果已有生产环境，需要考虑平滑升级方案
