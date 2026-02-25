# ChatBI 知识图谱工作流程详解

## 📋 目录
1. [系统架构概览](#系统架构概览)
2. [知识图谱数据结构](#知识图谱数据结构)
3. [完整工作流程](#完整工作流程)
4. [核心服务详解](#核心服务详解)
5. [实际案例演示](#实际案例演示)

---

## 🏗️ 系统架构概览

知识图谱系统由三个核心服务组成，协同工作完成从用户问题到SQL查询的转换：

```
用户问题
   ↓
[NER服务] → 提取实体（表名、列名、值等）
   ↓
[EntityDisambiguationService] → 实体消歧（纠正类型错误）
   ↓
[EntityLinkingService] → 实体链接（映射到数据库Schema）
   ↓
[SchemaGraphService] → 知识图谱查询（提供业务知识）
   ↓
SQL生成
```

---

## 📊 知识图谱数据结构

知识图谱存储在 `business-entities.json` 文件中，包含以下7类业务知识：

### 1. **组织名称** (organizations)
```json
["COMPAL", "Foxconn", "Dell", "HP", "Lenovo", "华硕", "仁宝", ...]
```
- **作用**: 识别供应商、制造商、品牌等组织实体
- **映射**: 通常对应 `SITE` 或 `PRODUCT_BRAND` 列

### 2. **地理位置** (locations)
```json
["美国", "中国", "华东", "华南", "NA", "EMEA", "PRC", ...]
```
- **作用**: 识别地区、国家、城市等地理实体
- **映射**: 通常对应 `GEO` 列

### 3. **表别名** (tableAliases)
```json
{
  "产品": "statshipmentboxai",
  "出货": "statshipmentboxai",
  "shipment": "statshipmentboxai"
}
```
- **作用**: 将业务术语映射到实际表名
- **示例**: 用户说"产品" → 系统理解为 `statshipmentboxai` 表

### 4. **列别名** (columnAliases)
```json
{
  "销售额": "Volume",
  "地区": "GEO",
  "产品名称": "PRODUCT_NAME",
  "月份": "Month"
}
```
- **作用**: 将业务术语映射到实际列名
- **示例**: 用户说"销售额" → 系统理解为 `Volume` 列

### 5. **列值域** (columnDomainValues)
```json
{
  "GEO": ["美国", "中国", "台湾", "NA", "EMEA", ...],
  "SITE": ["COMPAL", "Foxconn", "Dell", ...],
  "PRODUCT_CATEGORY": ["笔记本", "台式机", "Gaming", ...]
}
```
- **作用**: 定义每个列的合法取值范围
- **用途**: 
  - 实体消歧（判断"美国"是地区还是其他）
  - SQL生成时的值校验

### 6. **产品系列** (productSeries)
```json
{
  "S3": ["S3 15", "S3 80", "S3 IRH", "S3 20"],
  "LOQ": ["LOQ 15IRX9", "LOQ 15IAX9I", ...],
  "IPS5": ["IPS5 16IRL8", "IPS5 14IMH9", ...]
}
```
- **作用**: 定义产品系列与型号的层次关系
- **示例**: 用户说"S3系列" → 系统展开为所有S3型号

### 7. **业务术语** (businessTerms)
```json
{
  "SS": "Listing",
  "上市": "Listing",
  "CTO": "Configure To Order",
  "ODM": "Original Design Manufacturer"
}
```
- **作用**: 将行业黑话翻译为标准术语
- **示例**: 用户说"SS后" → 系统理解为 "After Listing"

### 8. **财年规则** (fiscalYearRule)
```json
{
  "startMonth": 4,
  "startDay": 1,
  "description": "财年从每年4月1日开始",
  "fiscalYearMappings": {
    "FY24": "2023-04-01 to 2024-03-31"
  }
}
```
- **作用**: 处理财年时间范围查询
- **示例**: 用户说"FY24" → 系统转换为 `2023-04-01 to 2024-03-31`

---

## 🔄 完整工作流程

### 阶段1: 初始化（系统启动时）

```java
@PostConstruct
public void init() {
    refreshGraph();  // 构建知识图谱
}
```

**步骤**:
1. **读取数据库Schema** (`ReadSchemaStructureService`)
   - 获取所有表名、列名、列注释
   
2. **加载业务词典** (`business-entities.json`)
   - 读取组织、地区、别名、值域等配置

3. **构建内存图结构** (`SchemaGraphService`)
   ```
   aliasToType: 别名 → 实体类型 (TABLE/COLUMN/ORG/LOC)
   tableAliasToTableName: 表别名 → 实际表名
   columnAliasToTableNames: 列别名 → 表名列表
   columnDomainValues: 列名 → 合法值集合
   orgSet/locSet: 组织/地区集合
   ```

4. **构建Schema缓存** (`EntityLinkingService`)
   ```
   tableNameMap: 中文名/注释 → 实际表名
   columnNameMap: 中文名/注释 → "表名.列名"
   tableColumns: 表名 → 列列表
   ```

---

### 阶段2: 查询处理（用户提问时）

#### 步骤1: NER实体识别
```
用户问题: "查询COMPAL在华东地区的S3系列销售额"
         ↓
NER服务识别:
  - "COMPAL" (ORG, 0.95)
  - "华东" (LOC, 0.92)
  - "S3" (PRODUCT_SERIES, 0.88)
  - "销售额" (COLUMN, 0.85)
```

#### 步骤2: 实体消歧 (EntityDisambiguationService)

**策略1: 图精确匹配**
```java
String graphType = schemaGraph.getTypeByAlias("COMPAL");
// graphType = "ORG" ✓ 确认正确
```

**策略2: 领域值校验**
```java
if (schemaGraph.isOrganization("COMPAL")) {
    entity.setType("ORG");  // 确认为组织
}
if (schemaGraph.isLocation("华东")) {
    entity.setType("LOC");  // 确认为地理位置
}
```

**策略3: 共现路径约束**
```java
// 如果同时出现"产品"和"销售额"，且图中存在 产品表→Volume列 路径
if (hasTableColumnPath("产品", "销售额")) {
    // 确认"产品"是表名，"销售额"是列名
}
```

**策略4: 业务术语消歧**
```java
String meaning = schemaGraph.getBusinessTermMeaning("SS");
// meaning = "Listing"
entity.setNormalizedValue("Listing");
```

**策略5: 产品系列层次化**
```java
if (schemaGraph.isProductSeries("S3")) {
    entity.setType("PRODUCT_SERIES");
    List<String> models = schemaGraph.getProductModelsBySeries("S3");
    // models = ["S3 15", "S3 80", "S3 IRH", "S3 20"]
}
```

#### 步骤3: 实体链接 (EntityLinkingService)

**三级匹配策略**:

**Tier 0: 业务别名映射** (优先级最高)
```java
String tableName = schemaGraph.getTableNameByAlias("产品");
// tableName = "statshipmentboxai" ✓

String columnMapping = getBusinessColumnMapping("销售额");
// columnMapping = "statshipmentboxai.Volume" ✓
```

**Tier 1: 精确匹配**
```java
String exact = cache.tableNameMap.get("statshipmentboxai");
// exact = "statshipmentboxai" ✓
```

**Tier 2: 模糊匹配** (包含关系)
```java
// 如果用户输入"产品表"，包含"产品"
fuzzyMatch("产品表", tableNameMap);
// 返回 "statshipmentboxai"
```

**Tier 3: 编辑距离匹配**
```java
// 如果用户输入"statshipment"（少了boxai）
editDistanceMatch("statshipment", tableNameMap, 0.5);
// 返回 "statshipmentboxai" (编辑距离在阈值内)
```

**链接结果**:
```
"COMPAL" → "statshipmentboxai.SITE"
"华东" → "statshipmentboxai.GEO"
"S3" → PRODUCT_SERIES (展开为 ["S3 15", "S3 80", ...])
"销售额" → "statshipmentboxai.Volume"
```

#### 步骤4: SQL生成

基于链接后的实体，生成SQL：
```sql
SELECT SUM(Volume) AS 销售额
FROM statshipmentboxai
WHERE SITE = 'COMPAL'
  AND GEO = '华东'
  AND PRODUCT_NAME IN ('S3 15', 'S3 80', 'S3 IRH', 'S3 20')
```

---

## 🔧 核心服务详解

### 1. SchemaGraphService (知识图谱核心)

**职责**: 管理业务知识，提供查询接口

**核心数据结构**:
```java
// 别名 → 类型映射
Map<String, String> aliasToType;
// "compal" → "ORG"
// "销售额" → "COLUMN"
// "产品" → "TABLE"

// 表别名 → 实际表名
Map<String, String> tableAliasToTableName;
// "产品" → "statshipmentboxai"

// 列别名 → 表名列表
Map<String, List<String>> columnAliasToTableNames;
// "销售额" → ["statshipmentboxai"]

// 列 → 值域
Map<String, Set<String>> columnDomainValues;
// "GEO" → ["美国", "中国", "华东", ...]
```

**关键方法**:
```java
// 查询别名类型
String getTypeByAlias(String alias);

// 检查是否为组织/地区
boolean isOrganization(String text);
boolean isLocation(String text);

// 检查值是否在列值域中
boolean isInColumnDomain(String text);

// 检查表-列路径是否存在
boolean hasTableColumnPath(String tableAlias, String columnAlias);

// 获取业务术语含义
String getBusinessTermMeaning(String term);  // "SS" → "Listing"

// 获取产品系列型号
List<String> getProductModelsBySeries(String series);  // "S3" → [...]
```

---

### 2. EntityDisambiguationService (实体消歧)

**职责**: 纠正NER识别错误，提高实体类型准确性

**5大消歧策略**:

| 策略 | 优先级 | 触发条件 | 示例 |
|------|--------|----------|------|
| 图精确匹配 | 最高 | 实体在图中有别名 | "COMPAL" → ORG (图中已标注) |
| 领域值校验 | 高 | 实体类型为KW/O或置信度低 | "华东" → LOC (在locations中) |
| 共现路径约束 | 中 | 多个实体共现 | "产品"+"销售额" → 产品是TABLE |
| 业务术语消歧 | 中 | 实体是业务黑话 | "SS" → "Listing" |
| 产品系列层次化 | 低 | 实体是产品系列名 | "S3" → PRODUCT_SERIES |

**示例**:
```java
// 原始NER结果: "SS" (KW, 0.6)  ← 置信度低，类型不明确
disambiguate("SS后的销售额", entities);

// 策略4: 业务术语消歧
String meaning = schemaGraph.getBusinessTermMeaning("SS");
// meaning = "Listing"
entity.setNormalizedValue("Listing");
entity.setType("VALUE");

// 最终结果: "SS" (VALUE, normalizedValue="Listing") ✓
```

---

### 3. EntityLinkingService (实体链接)

**职责**: 将实体映射到数据库Schema的具体表名/列名

**三级匹配策略**:

```java
// Tier 0: 业务别名 (最准确)
"销售额" → "statshipmentboxai.Volume"

// Tier 1: 精确匹配
"Volume" → "statshipmentboxai.Volume"

// Tier 2: 模糊匹配 (包含关系)
"销售" → "statshipmentboxai.Volume" (包含"销售额")

// Tier 3: 编辑距离 (容错)
"Volme" → "statshipmentboxai.Volume" (编辑距离=1)
```

**特殊处理**:

**组织名链接**:
```java
matchOrganization("COMPAL", cache);
// 1. 确认是组织 ✓
// 2. 优先匹配 SITE 列
// 3. 其次匹配 brand/vendor 列
// 返回: "statshipmentboxai.SITE"
```

**地理位置链接**:
```java
matchLocation("华东", cache);
// 1. 确认是地理位置 ✓
// 2. 优先匹配 GEO 列
// 3. 其次匹配 location/region/country 列
// 返回: "statshipmentboxai.GEO"
```

**值链接**:
```java
matchValue("Gaming", cache);
// 1. 检查是否在 columnDomainValues 中 ✓
// 2. 返回: "VALUE_DOMAIN:Gaming"
```

---

## 💡 实际案例演示

### 案例1: 简单查询

**用户问题**: "查询Dell的销售额"

**处理流程**:
```
1. NER识别:
   - "Dell" (ORG, 0.95)
   - "销售额" (COLUMN, 0.90)

2. 实体消歧:
   - "Dell" → ORG (在organizations中) ✓
   - "销售额" → COLUMN ✓

3. 实体链接:
   - "Dell" → "statshipmentboxai.PRODUCT_BRAND" (组织→品牌列)
   - "销售额" → "statshipmentboxai.Volume" (别名映射)

4. SQL生成:
   SELECT SUM(Volume) AS 销售额
   FROM statshipmentboxai
   WHERE PRODUCT_BRAND = 'Dell'
```

---

### 案例2: 复杂查询（多实体+业务术语）

**用户问题**: "COMPAL在华东地区SS后的S3系列销售额"

**处理流程**:
```
1. NER识别:
   - "COMPAL" (ORG, 0.95)
   - "华东" (LOC, 0.92)
   - "SS" (KW, 0.60)  ← 置信度低
   - "S3" (PRODUCT_SERIES, 0.88)
   - "销售额" (COLUMN, 0.90)

2. 实体消歧:
   策略1 (图精确匹配):
     - "COMPAL" → ORG ✓
     - "华东" → LOC ✓
   
   策略4 (业务术语):
     - "SS" → normalizedValue="Listing" ✓
   
   策略5 (产品系列):
     - "S3" → PRODUCT_SERIES
     - 展开: ["S3 15", "S3 80", "S3 IRH", "S3 20"]

3. 实体链接:
   - "COMPAL" → "statshipmentboxai.SITE"
   - "华东" → "statshipmentboxai.GEO"
   - "SS" → "Listing" (值)
   - "S3" → 产品系列 (展开为IN条件)
   - "销售额" → "statshipmentboxai.Volume"

4. SQL生成:
   SELECT SUM(Volume) AS 销售额
   FROM statshipmentboxai
   WHERE SITE = 'COMPAL'
     AND GEO = '华东'
     AND Listing IS NOT NULL  -- SS后 = 已上市
     AND PRODUCT_NAME IN ('S3 15', 'S3 80', 'S3 IRH', 'S3 20')
```

---

### 案例3: 财年查询

**用户问题**: "FY24的销售额"

**处理流程**:
```
1. NER识别:
   - "FY24" (TIME_RANGE, 0.90)
   - "销售额" (COLUMN, 0.90)

2. 实体消歧:
   - "FY24" → TIME_RANGE ✓

3. 财年规则应用:
   fiscalYearMappings["FY24"] = "2023-04-01 to 2024-03-31"
   
   转换为SQL条件:
   (Year = 2023 AND Month >= 4) OR (Year = 2024 AND Month <= 3)

4. SQL生成:
   SELECT SUM(Volume) AS 销售额
   FROM statshipmentboxai
   WHERE ((Year = 2023 AND Month >= 4) 
          OR (Year = 2024 AND Month <= 3))
```

---

## 🔄 配置管理

### 1. 获取配置
```http
GET /api/knowledge-graph/config
```
返回当前知识图谱的完整配置

### 2. 验证配置
```http
POST /api/knowledge-graph/validate
Content-Type: application/json

{
  "tableAliases": {"产品": "statshipmentboxai"},
  "columnAliases": {"销售额": "Volume"},
  ...
}
```
验证配置的有效性（表名/列名是否存在）

### 3. 更新配置
```http
PUT /api/knowledge-graph/config
Content-Type: application/json

{配置内容}
```
**流程**:
1. 验证配置 ✓
2. 创建备份 → `backups/business-entities_20260216_231432.json`
3. 保存配置
4. 提示调用 `/refresh` 重新加载

### 4. 刷新图谱
```http
POST /api/knowledge-graph/refresh
```
重新加载配置到内存（Schema变更或配置更新后调用）

### 5. 创建备份
```http
POST /api/knowledge-graph/backup
```
手动创建配置备份

---

## 📈 性能优化

### 1. 内存缓存
- 所有映射关系存储在 `ConcurrentHashMap` 中
- 启动时一次性加载，查询时 O(1) 复杂度

### 2. 懒加载
```java
private SchemaCache getCache() {
    if (schemaCache == null) {
        refreshCache();  // 首次使用时初始化
    }
    return schemaCache;
}
```

### 3. 并发安全
- 使用 `volatile` 保证可见性
- 使用 `ConcurrentHashMap` 保证线程安全

---

## 🎯 关键优势

### 1. **业务友好**
- 支持中文别名："销售额" → `Volume`
- 支持行业术语："SS" → `Listing`
- 支持模糊匹配：容忍拼写错误

### 2. **高准确性**
- 5层消歧策略，逐级纠错
- 领域值校验，避免误识别
- 共现路径约束，利用上下文

### 3. **可扩展**
- 配置文件驱动，无需修改代码
- 支持动态刷新，无需重启
- 支持备份恢复，安全可靠

### 4. **智能化**
- 产品系列自动展开
- 财年自动转换
- 业务术语自动翻译

---

## 🚀 未来优化方向

1. **语义匹配**: 引入向量相似度，提升模糊匹配准确性
2. **上下文感知**: 利用对话历史，提升多轮对话准确性
3. **自动学习**: 从用户反馈中自动学习新的别名映射
4. **可视化管理**: 提供图形化界面管理知识图谱

---

## 📚 相关文件

- **配置文件**: `backend/src/main/resources/entity-graph/business-entities.json`
- **核心服务**:
  - `SchemaGraphService.java` - 知识图谱管理
  - `EntityDisambiguationService.java` - 实体消歧
  - `EntityLinkingService.java` - 实体链接
- **API控制器**: `KnowledgeGraphController.java`
- **DTO**: `KnowledgeGraphConfig.java`, `ValidationResult.java`

---

**最后更新**: 2026-02-16
**作者**: ChatBI Team
