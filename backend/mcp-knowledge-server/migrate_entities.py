"""
数据迁移脚本：从 business-entities.json 导入数据到 MCP 数据库
"""
import json
import logging
import sys
from pathlib import Path

from database import db, Entity, ProductSeries, ColumnDomainValue

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# business-entities.json 的路径
BUSINESS_ENTITIES_FILE = Path(__file__).parent.parent / "src" / "main" / "resources" / "entity-graph" / "business-entities.json"


def load_business_entities():
    """加载 business-entities.json 文件"""
    try:
        with open(BUSINESS_ENTITIES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        logger.error(f"文件不存在: {BUSINESS_ENTITIES_FILE}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        logger.error(f"JSON 解析错误: {e}")
        sys.exit(1)


def migrate_entities(data):
    """迁移组织和地区实体"""
    logger.info("开始迁移实体数据...")

    # 迁移组织
    organizations = data.get("organizations", [])
    logger.info(f"迁移 {len(organizations)} 个组织...")
    for org in organizations:
        try:
            db.create_entity({
                "entity_type": "organization",
                "entity_value": org,
                "aliases": []
            })
        except Exception as e:
            logger.warning(f"组织 '{org}' 已存在或创建失败: {e}")

    # 迁移地区
    locations = data.get("locations", [])
    logger.info(f"迁移 {len(locations)} 个地区...")
    for loc in locations:
        try:
            db.create_entity({
                "entity_type": "location",
                "entity_value": loc,
                "aliases": []
            })
        except Exception as e:
            logger.warning(f"地区 '{loc}' 已存在或创建失败: {e}")

    logger.info("实体数据迁移完成")


def migrate_product_series(data):
    """迁移产品系列"""
    logger.info("开始迁移产品系列数据...")

    product_series = data.get("productSeries", {})
    logger.info(f"迁移 {len(product_series)} 个产品系列...")
    for series_name, models in product_series.items():
        try:
            db.create_product_series({
                "series_name": series_name,
                "models": models,
                "description": f"{series_name} 系列产品"
            })
        except Exception as e:
            logger.warning(f"产品系列 '{series_name}' 已存在或创建失败: {e}")

    logger.info("产品系列数据迁移完成")


def migrate_column_domain_values(data):
    """迁移列域值"""
    logger.info("开始迁移列域值数据...")

    column_domain_values = data.get("columnDomainValues", {})
    logger.info(f"迁移 {len(column_domain_values)} 个列域值...")
    for column_name, valid_values in column_domain_values.items():
        try:
            db.create_column_domain_value({
                "table_name": "statshipmentboxai",  # 默认表名
                "column_name": column_name,
                "valid_values": valid_values
            })
        except Exception as e:
            logger.warning(f"列域值 '{column_name}' 已存在或创建失败: {e}")

    logger.info("列域值数据迁移完成")


def main():
    """主函数"""
    logger.info("=" * 60)
    logger.info("开始数据迁移")
    logger.info("=" * 60)

    # 初始化数据库
    logger.info("初始化数据库...")
    db.init_db()

    # 加载数据
    logger.info(f"加载数据文件: {BUSINESS_ENTITIES_FILE}")
    data = load_business_entities()

    # 执行迁移
    migrate_entities(data)
    migrate_product_series(data)
    migrate_column_domain_values(data)

    logger.info("=" * 60)
    logger.info("数据迁移完成！")
    logger.info("=" * 60)

    # 显示统计信息
    stats = db.get_stats()
    logger.info(f"统计信息:")
    logger.info(f"  - 业务术语: {stats['total_terms']}")
    logger.info(f"  - 列映射: {stats['total_mappings']}")
    logger.info(f"  - 时间表达式: {stats['total_expressions']}")

    entities = db.get_all_entities()
    logger.info(f"  - 实体: {len(entities)}")

    series = db.get_all_product_series()
    logger.info(f"  - 产品系列: {len(series)}")

    domain_values = db.get_all_column_domain_values()
    logger.info(f"  - 列域值: {len(domain_values)}")


if __name__ == "__main__":
    main()
