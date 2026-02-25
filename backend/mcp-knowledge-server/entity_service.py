"""
实体服务：实体查询和验证
"""
import logging
from typing import List, Dict, Any, Optional

from database import db

logger = logging.getLogger(__name__)


class EntityService:
    """实体服务类"""

    def __init__(self):
        self.db = db

    def get_entities_by_type(self, entity_type: str) -> List[str]:
        """
        获取指定类型的所有实体值

        Args:
            entity_type: 实体类型 ('organization', 'location')

        Returns:
            实体值列表
        """
        entities = self.db.get_all_entities(entity_type=entity_type)
        return [e["entity_value"] for e in entities]

    def validate_entity(self, entity_text: str, entity_type: str) -> Dict[str, Any]:
        """
        验证实体是否存在于知识库中

        Args:
            entity_text: 实体文本
            entity_type: 实体类型 ('ORG' -> 'organization', 'LOC' -> 'location')

        Returns:
            验证结果字典
        """
        # 转换实体类型
        type_mapping = {
            "ORG": "organization",
            "LOC": "location",
            "ORGANIZATION": "organization",
            "LOCATION": "location"
        }
        db_entity_type = type_mapping.get(entity_type.upper(), entity_type.lower())

        # 精确匹配
        entity = self.db.get_entity_by_value(entity_text, db_entity_type)
        if entity:
            return {
                "is_valid": True,
                "validation_method": "exact_match",
                "entity": entity
            }

        # 检查是否在列域值中
        if db_entity_type == "organization":
            domain_result = self._check_column_domain("SITE", entity_text)
            if domain_result["is_valid"]:
                return domain_result
        elif db_entity_type == "location":
            domain_result = self._check_column_domain("GEO", entity_text)
            if domain_result["is_valid"]:
                return domain_result

        # 未找到
        return {
            "is_valid": False,
            "validation_method": "not_found",
            "suggestions": self._get_similar_entities(entity_text, db_entity_type)
        }

    def _check_column_domain(self, column_name: str, value: str) -> Dict[str, Any]:
        """
        检查值是否在列域值中

        Args:
            column_name: 列名
            value: 值

        Returns:
            验证结果
        """
        domain_data = self.db.get_column_domain_values_by_column(column_name)
        if domain_data and value in domain_data["valid_values"]:
            return {
                "is_valid": True,
                "validation_method": "domain_validation",
                "column": column_name
            }
        return {"is_valid": False}

    def _get_similar_entities(self, text: str, entity_type: str, limit: int = 3) -> List[str]:
        """
        获取相似的实体（简单的字符串匹配）

        Args:
            text: 输入文本
            entity_type: 实体类型
            limit: 返回数量限制

        Returns:
            相似实体列表
        """
        all_entities = self.get_entities_by_type(entity_type)

        # 简单的包含匹配
        similar = []
        for entity in all_entities:
            if text.lower() in entity.lower() or entity.lower() in text.lower():
                similar.append(entity)
                if len(similar) >= limit:
                    break

        return similar

    def expand_product_series(self, series_name: str) -> Optional[Dict[str, Any]]:
        """
        展开产品系列为具体型号

        Args:
            series_name: 系列名称

        Returns:
            产品系列信息，包含所有型号
        """
        series = self.db.get_product_series_by_name(series_name)
        if series:
            return {
                "series_name": series["series_name"],
                "models": series["models"],
                "model_count": len(series["models"])
            }
        return None

    def get_column_valid_values(self, column_name: str) -> Optional[List[str]]:
        """
        获取列的有效域值

        Args:
            column_name: 列名

        Returns:
            有效值列表
        """
        domain_data = self.db.get_column_domain_values_by_column(column_name)
        if domain_data:
            return domain_data["valid_values"]
        return None

    def validate_column_value(self, column_name: str, value: str) -> Dict[str, Any]:
        """
        验证列值是否有效

        Args:
            column_name: 列名
            value: 值

        Returns:
            验证结果
        """
        valid_values = self.get_column_valid_values(column_name)
        if valid_values is None:
            return {
                "is_valid": True,  # 没有域值限制，认为有效
                "validation_method": "no_constraint"
            }

        if value in valid_values:
            return {
                "is_valid": True,
                "validation_method": "domain_validation"
            }
        else:
            # 查找相似值
            similar = [v for v in valid_values if value.lower() in v.lower() or v.lower() in value.lower()][:3]
            return {
                "is_valid": False,
                "validation_method": "domain_validation",
                "suggestions": similar
            }


# 全局实例
entity_service = EntityService()
