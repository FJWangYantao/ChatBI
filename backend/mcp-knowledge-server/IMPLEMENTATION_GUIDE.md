# 业务术语知识库 MCP 服务器 - 实施指南

## 项目概述

本项目为 ChatBI 系统提供业务术语知识库服务，通过 MCP (Model Context Protocol) 协议，让 AI 能够理解业务术语（如"出货额"、"FY23"），从而提高问题理解准确度和 SQL 生成质量。

## 架构设计

```
┌─────────────────┐
│   用户问题      │
│ "FY23的出货额"  │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│         ChatBI Backend (Spring Boot)     │
│  ┌────────────────────────────────────┐ │
│  │  ChatService                       │ │
│  │  1. 接收用户问题                   │ │
│  │  2. 调用 MCPKnowledgeService       │ │
│  │  3. 获取增强的上下文               │ │
│  │  4. 传递给 Spring AI               │ │
│  └────────────────────────────────────┘ │
└─────────────────┬───────────────────────┘
                  │ HTTP REST API
                  ▼
┌─────────────────────────────────────────┐
│   MCP Knowledge Server (Python/FastAPI) │
│  ┌────────────────────────────────────┐ │
│  │  术语查询服务                      │ │
│  │  - 搜索术语定义                    │ │
│  │  - 获取列映射                      │ │
│  │  - 解析时间表达式                  │ │
│  │  - 生成增强 Prompt                 │ │
│  └────────────────────────────────────┘ │
│  ┌────────────────────────────────────┐ │
│  │  SQLite/MySQL 数据库               │ │
│  │  - business_terms (术语表)         │ │
│  │  - column_mappings (列映射表)      │ │
│  │  - time_expressions (时间规则表)   │ │
│  └────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## 实施步骤

### 阶段 1：MCP 服务器部署（第 1-2 天）

#### 1.1 安装依赖

```bash
cd backend/mcp-knowledge-server
pip install -r requirements.txt
```

#### 1.2 初始化数据库

服务器首次启动时会自动创建数据库并加载初始数据。

```bash
python server.py
```

访问 `http://localhost:8004` 验证服务启动成功。

#### 1.3 测试 MCP 服务器

```bash
python test_server.py
```

预期输出：
- 健康检查通过
- 术语搜索返回结果
- 时间表达式解析成功
- 上下文增强生成完整的 Prompt

### 阶段 2：Spring Boot 集成（第 3-4 天）

#### 2.1 添加配置

编辑 `backend/src/main/resources/application.properties`：

```properties
# MCP 知识库服务器地址
mcp.knowledge.server.url=http://localhost:8004

# 是否启用 MCP 知识库服务
mcp.knowledge.enabled=true
```

#### 2.2 修改 ChatService

在 `ChatService.java` 中注入 `MCPKnowledgeService`：

```java
@Autowired
private MCPKnowledgeService mcpKnowledgeService;

public ChatResponseWithConversation smartChatWithConversation(String message, String conversationId) {
    // 1. 【新增】调用 MCP 服务增强上下文
    String enrichedPrompt = mcpKnowledgeService.getEnrichedPrompt(message);

    // 2. 构建完整的 AI Prompt
    String aiPrompt = enrichedPrompt + "\n\n请根据以上信息生成 SQL 查询。";

    // 3. 调用 Spring AI（原有逻辑）
    String response = springAI.generate(aiPrompt);

    // ... 其余逻辑保持不变
}
```

#### 2.3 测试集成

启动 Spring Boot 应用，发送测试请求：

```bash
curl -X POST http://localhost:8080/chat/message \
  -H "Content-Type: application/json" \
  -d '{"message": "FY23的出货额前10是多少？"}'
```

检查日志，确认：
1. MCP 服务被调用
2. 术语被正确识别
3. 上下文增强成功

### 阶段 3：数据完善（第 5-7 天）

#### 3.1 收集业务术语

与业务团队合作，收集常用的业务术语：
- 指标类：销售额、出货额、利润率、增长率等
- 时间类：FY23、Q1、上半年、同比、环比等
- 维度类：产品、客户、地区、渠道等
- 分析类：前10、排名、趋势、对比等

#### 3.2 更新术语数据

编辑 `data/initial_terms.json`，添加新术语：

```json
{
  "business_terms": [
    {
      "term": "利润率",
      "category": "指标",
      "definition": "利润占销售额的百分比",
      "aliases": ["profit_margin", "毛利率"],
      "examples": ["2024年的利润率", "各产品利润率对比"]
    }
  ]
}
```

#### 3.3 重新加载数据

```bash
# 删除旧数据库
rm data/knowledge.db

# 重启服务（会自动重新初始化）
python server.py
```

### 阶段 4：效果验证（第 8-10 天）

#### 4.1 对比测试

准备测试用例，对比启用/禁用 MCP 的效果：

| 用户问题 | 不使用 MCP | 使用 MCP | 改进 |
|---------|-----------|---------|------|
| FY23的出货额 | 可能无法识别 FY23 | 正确解析为 2023-01-01 至 2023-12-31 | ✅ |
| 出货额前10 | 可能不知道对应哪个列 | 正确映射到 product_shipment.shipment_amount | ✅ |
| 同比增长 | 可能不理解"同比" | 理解为与去年同期对比 | ✅ |

#### 4.2 性能测试

测试 MCP 调用的性能影响：
- 单次调用延迟：< 100ms
- 并发处理能力：> 100 QPS
- 对整体响应时间的影响：< 10%

#### 4.3 准确率评估

统计 SQL 生成准确率：
- 基线（不使用 MCP）：70%
- 使用 MCP 后：85%+
- 目标提升：15%+

## 使用示例

### 示例 1：基本查询

**用户问题**：
```
FY23的出货额是多少？
```

**MCP 增强后的 Prompt**：
```
用户问题：FY23的出货额是多少？

业务术语解释：
- FY23: 财年2023，时间范围：2023-01-01 至 2023-12-31
- 出货额: 产品出货的金额总和

数据库列映射：
- 出货额 → 表: product_shipment, 列: shipment_amount (DECIMAL(15,2))

时间范围解析：
- FY23: 财年2023（2023-01-01 至 2023-12-31）
  SQL条件: date_column >= '2023-01-01' AND date_column <= '2023-12-31'

请根据以上信息生成 SQL 查询。
```

**生成的 SQL**：
```sql
SELECT SUM(shipment_amount) as total_shipment
FROM product_shipment
WHERE shipment_date >= '2023-01-01' AND shipment_date <= '2023-12-31';
```

### 示例 2：复杂查询

**用户问题**：
```
2024年Q1销售额前10的产品是哪些？
```

**MCP 增强后的 Prompt**：
```
用户问题：2024年Q1销售额前10的产品是哪些？

业务术语解释：
- Q1: 第一季度，时间范围：1月1日至3月31日
- 销售额: 产品销售的金额总和
- 前10: 按指定指标排序后取前10条记录

数据库列映射：
- 销售额 → 表: sales_records, 列: sales_amount (DECIMAL(15,2))
- 产品 → 表: products, 列: product_name (VARCHAR(200))

时间范围解析：
- 2024年Q1: 2024年第1季度（2024-01-01 至 2024-03-31）
  SQL条件: date_column >= '2024-01-01' AND date_column <= '2024-03-31'

请根据以上信息生成 SQL 查询。
```

**生成的 SQL**：
```sql
SELECT p.product_name, SUM(s.sales_amount) as total_sales
FROM sales_records s
JOIN products p ON s.product_id = p.id
WHERE s.sale_date >= '2024-01-01' AND s.sale_date <= '2024-03-31'
GROUP BY p.product_name
ORDER BY total_sales DESC
LIMIT 10;
```

## 维护指南

### 添加新术语

1. 编辑 `data/initial_terms.json`
2. 添加术语定义和列映射
3. 删除 `data/knowledge.db`
4. 重启服务

### 更新现有术语

1. 直接修改数据库（推荐用于小改动）：
```sql
UPDATE business_terms
SET definition = '新定义'
WHERE term = '术语名称';
```

2. 或者修改 JSON 文件并重新初始化（推荐用于大批量更新）

### 监控和日志

查看 MCP 服务器日志：
```bash
tail -f mcp-server.log
```

关键指标：
- 请求成功率
- 平均响应时间
- 术语识别率

## 故障排查

### 问题 1：MCP 服务器无法启动

**症状**：`python server.py` 报错

**解决方案**：
1. 检查依赖是否安装：`pip list`
2. 检查端口是否被占用：`netstat -ano | findstr 8004`
3. 查看详细错误日志

### 问题 2：Spring Boot 无法连接 MCP 服务器

**症状**：日志显示 "调用 MCP 服务器失败"

**解决方案**：
1. 确认 MCP 服务器已启动：访问 `http://localhost:8004/health`
2. 检查配置：`mcp.knowledge.server.url` 是否正确
3. 检查防火墙设置

### 问题 3：术语识别不准确

**症状**：某些术语没有被识别

**解决方案**：
1. 检查术语是否在数据库中：`POST /tools/search_terms`
2. 添加更多别名
3. 检查搜索逻辑是否需要优化

## 扩展功能

### 未来可以添加的功能

1. **多语言支持**：支持英文术语
2. **术语推荐**：根据上下文推荐相关术语
3. **学习功能**：从用户反馈中学习新术语
4. **版本管理**：术语定义的版本控制
5. **权限控制**：不同用户看到不同的术语集

## 总结

通过引入 MCP 知识库服务器，ChatBI 系统能够：

✅ **更准确地理解业务术语**：AI 知道"FY23"是什么意思
✅ **生成更准确的 SQL**：知道"出货额"对应哪个表和列
✅ **减少用户困惑**：不需要用户记住数据库列名
✅ **易于维护**：业务术语集中管理，无需重新训练模型

预期效果：
- SQL 生成准确率提升 15%+
- 用户满意度提升
- 维护成本降低

## 联系方式

如有问题，请联系开发团队。
