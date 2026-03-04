"""
更新 MCP 知识库的列映射，使其匹配 StatShipmentBoxAI 表结构
"""
import json
from pathlib import Path
import sqlite3

# 数据库路径
DB_PATH = Path(__file__).parent / "data" / "knowledge.db"

# 新的列映射配置
NEW_COLUMN_MAPPINGS = [
    {
        "term": "出货量",
        "table_name": "statshipmentboxai",
        "column_name": "Volume",
        "data_type": "INT",
        "description": "产品出货数量，单位：台",
        "sample_values": ["1000", "5000", "200"]
    },
    {
        "term": "NB",
        "table_name": "statshipmentboxai",
        "column_name": "PRODUCT_CATEGORY",
        "data_type": "VARCHAR(100)",
        "description": "产品类别，NB 对应 Notebook",
        "sample_values": ["Notebook", "NB"],
        "filter_condition": "PRODUCT_CATEGORY = 'Notebook' OR PRODUCT_CATEGORY = 'NB'"
    },
    {
        "term": "消费NB",
        "table_name": "statshipmentboxai",
        "column_name": "PRODUCT_CATEGORY",
        "data_type": "VARCHAR(100)",
        "description": "消费类笔记本，需要同时匹配 Consumer 和 Notebook",
        "sample_values": ["Consumer Notebook"],
        "filter_condition": "PRODUCT_CATEGORY LIKE '%Consumer%' AND (PRODUCT_CATEGORY LIKE '%Notebook%' OR PRODUCT_CATEGORY LIKE '%NB%')"
    },
    {
        "term": "Consumer",
        "table_name": "statshipmentboxai",
        "column_name": "PRODUCT_CATEGORY",
        "data_type": "VARCHAR(100)",
        "description": "消费产品线",
        "sample_values": ["Consumer"],
        "filter_condition": "PRODUCT_CATEGORY LIKE '%Consumer%'"
    },
    {
        "term": "产品名称",
        "table_name": "statshipmentboxai",
        "column_name": "PRODUCT_NAME",
        "data_type": "VARCHAR(255)",
        "description": "产品名称",
        "sample_values": []
    },
    {
        "term": "年份",
        "table_name": "statshipmentboxai",
        "column_name": "Year",
        "data_type": "INT",
        "description": "自然年份",
        "sample_values": ["2023", "2024", "2025"]
    },
    {
        "term": "财年",
        "table_name": "statshipmentboxai",
        "column_name": "FiscalYear",
        "data_type": "INT",
        "description": "财年（4月1日至次年3月31日）",
        "sample_values": ["2023", "2024", "2025"]
    },
    {
        "term": "月份",
        "table_name": "statshipmentboxai",
        "column_name": "Month",
        "data_type": "INT",
        "description": "自然月份（1-12）",
        "sample_values": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"]
    },
    {
        "term": "财月",
        "table_name": "statshipmentboxai",
        "column_name": "FiscalMonth",
        "data_type": "INT",
        "description": "财年月份（1-12）",
        "sample_values": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"]
    },
    {
        "term": "季度",
        "table_name": "statshipmentboxai",
        "column_name": "Quarter",
        "data_type": "INT",
        "description": "自然季度（1-4）",
        "sample_values": ["1", "2", "3", "4"]
    },
    {
        "term": "财季",
        "table_name": "statshipmentboxai",
        "column_name": "FiscalQuarter",
        "data_type": "INT",
        "description": "财年季度（1-4）",
        "sample_values": ["1", "2", "3", "4"]
    }
]

def update_column_mappings():
    """更新数据库中的列映射"""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    try:
        # 清空现有的列映射
        cursor.execute("DELETE FROM column_mappings")
        print("[OK] 已清空旧的列映射")

        # 插入新的列映射
        for mapping in NEW_COLUMN_MAPPINGS:
            # 查找对应的术语 ID
            cursor.execute("SELECT id FROM business_terms WHERE term = ?", (mapping["term"],))
            result = cursor.fetchone()

            if result:
                term_id = result[0]
                cursor.execute("""
                    INSERT INTO column_mappings
                    (term_id, table_name, column_name, data_type, description, sample_values)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (
                    term_id,
                    mapping["table_name"],
                    mapping["column_name"],
                    mapping["data_type"],
                    mapping["description"],
                    json.dumps(mapping["sample_values"])
                ))
                print(f"[OK] 已添加列映射：{mapping['term']} -> {mapping['table_name']}.{mapping['column_name']}")
            else:
                print(f"[WARN] 未找到术语：{mapping['term']}")

        conn.commit()
        print(f"\n[OK] 成功更新 {len(NEW_COLUMN_MAPPINGS)} 个列映射")

    except Exception as e:
        conn.rollback()
        print(f"[ERROR] 更新失败：{e}")
        raise
    finally:
        conn.close()

if __name__ == "__main__":
    update_column_mappings()
