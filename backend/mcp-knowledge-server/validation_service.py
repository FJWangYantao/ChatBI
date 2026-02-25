"""
验证服务：实现多种实体验证策略
"""
import logging
from typing import List, Dict, Any, Optional

from entity_service import entity_service
from database import db

logger = logging.getLogger(__name__)


class ValidationService:
    """验证服务类"""

    def __init__(self):
        self.entity_service = entity_service

    def validate_entities(self, entities: List[Dict[str, str]]) -> Dict[str, Any]:
        """
        批量验证实体

        Args:
            entities: 实体列表，每个实体包含 text 和 type

        Returns:
            验证结果
        """
        validated_entities = []

        for entity in entities:
            text = entity.get("text", "")
            entity_type = entity.get("type", "")

            validation_result = self._validate_single_entity(text, entity_type)
            validated_entities.append({
                "text": text,
                "type": entity_type,
                **validation_result
            })

        return {
            "success": True,
            "validated_entities": validated_entities
        }

    def _validate_single_entity(self, text: str, entity_type: str) -> Dict[str, Any]:
        """
        验证单个实体

        Args:
            text: 实体文本
            entity_type: 实体类型

        Returns:
            验证结果
        """
        # 策略1: 精确匹配
        exact_match_result = self._strategy_exact_match(text, entity_type)
        if exact_match_result["is_valid"]:
            return exact_match_result

        # 策略2: 域值验证
        domain_validation_result = self._strategy_domain_validation(text, entity_type)
        if domain_validation_result["is_valid"]:
            return domain_validation_result

        # 策略3: 产品系列匹配
        if entity_type in ["PRODUCT", "PROD"]:
            product_series_result = self._strategy_product_series(text)
            if product_series_result["is_valid"]:
                return product_series_result

        # 策略4: 业务术语匹配
        business_term_result = self._strategy_business_term(text)
        if business_term_result["is_valid"]:
            return business_term_result

        # 所有策略都失败
        return {
            "is_valid": False,
            "validation_method": "not_found",
            "suggestions": self._get_suggestions(text, entity_type)
        }

    def _strategy_exact_match(self, text: str, entity_type: str) -> Dict[str, Any]:
        """
        策略1: 精确匹配实体库

        Args:
            text: 实体文本
            entity_type: 实体类型

        Returns:
            验证结果
        """
        result = self.entity_service.validate_entity(text, entity_type)
        if result["is_valid"] and result["validation_method"] == "exact_match":
            return {
                "is_valid": True,
                "validation_method": "exact_match",
                "confidence": 1.0
            }
        return {"is_valid": False}

    def _strategy_domain_validation(self, text: str, entity_type: str) -> Dict[str, Any]:
        """
        策略2: 列域值验证

        Args:
            text: 实体文本
            entity_type: 实体类型

        Returns:
            验证结果
        """
        # 根据实体类型确定要检查的列
        column_mapping = {
            "ORG": "SITE",
            "ORGANIZATION": "SITE",
            "LOC": "GEO",
            "LOCATION": "GEO",
            "PRODUCT": "PRODUCT_NAME",
            "BRAND": "PRODUCT_BRAND",
            "CATEGORY": "PRODUCT_CATEGORY"
        }

        column_name = column_mapping.get(entity_type.upper())
        if not column_name:
            return {"is_valid": False}

        validation_result = self.entity_service.validate_column_value(column_name, text)
        if validation_result["is_valid"]:
            return {
                "is_valid": True,
                "validation_method": "domain_validation",
                "column": column_name,
                "confidence": 0.95
            }

        return {"is_valid": False}

    def _strategy_product_series(self, text: str) -> Dict[str, Any]:
        """
        策略3: 产品系列匹配

        Args:
            text: 产品文本

        Returns:
            验证结果
        """
        series_result = self.entity_service.expand_product_series(text)
        if series_result:
            return {
                "is_valid": True,
                "validation_method": "product_series",
                "series_name": series_result["series_name"],
                "models": series_result["models"],
                "confidence": 0.9
            }
        return {"is_valid": False}

    def _strategy_business_term(self, text: str) -> Dict[str, Any]:
        """
        策略4: 业务术语匹配

        Args:
            text: 文本

        Returns:
            验证结果
        """
        # 搜索业务术语
        terms = db.search_terms(text)
        if terms:
            return {
                "is_valid": True,
                "validation_method": "business_term",
                "matched_term": terms[0]["term"],
                "confidence": 0.85
            }
        return {"is_valid": False}

    def _get_suggestions(self, text: str, entity_type: str) -> List[str]:
        """
        获取建议值

        Args:
            text: 文本
            entity_type: 实体类型

        Returns:
            建议列表
        """
        suggestions = []

        # 从实体库获取建议
        type_mapping = {
            "ORG": "organization",
            "LOC": "location",
            "ORGANIZATION": "organization",
            "LOCATION": "location"
        }
        db_entity_type = type_mapping.get(entity_type.upper())
        if db_entity_type:
            entity_suggestions = self.entity_service._get_similar_entities(text, db_entity_type, limit=3)
            suggestions.extend(entity_suggestions)

        return suggestions[:5]  # 最多返回5个建议

    def disambiguate_entity(self, entity: str, context: str, possible_types: List[str]) -> Dict[str, Any]:
        """
        实体消歧：根据上下文确定实体类型

        Args:
            entity: 实体文本
            context: 上下文
            possible_types: 可能的类型列表

        Returns:
            消歧结果
        """
        # 简单的启发式规则
        context_lower = context.lower()

        # 规则1: 上下文关键词匹配
        org_keywords = ["销售额", "出货量", "客户", "供应商", "公司", "厂商"]
        loc_keywords = ["地区", "区域", "市场", "国家", "城市"]
        product_keywords = ["产品", "型号", "系列", "机型"]

        scores = {}
        for entity_type in possible_types:
            score = 0
            if entity_type.upper() in ["ORG", "ORGANIZATION"]:
                score = sum(1 for kw in org_keywords if kw in context_lower)
            elif entity_type.upper() in ["LOC", "LOCATION"]:
                score = sum(1 for kw in loc_keywords if kw in context_lower)
            elif entity_type.upper() in ["PRODUCT", "PROD"]:
                score = sum(1 for kw in product_keywords if kw in context_lower)
            scores[entity_type] = score

        # 选择得分最高的类型
        if scores:
            best_type = max(scores, key=scores.get)
            confidence = min(0.95, 0.6 + scores[best_type] * 0.1)

            return {
                "success": True,
                "disambiguated_type": best_type,
                "confidence": confidence,
                "reasoning": f"上下文中包含相关关键词，推断为 {best_type} 类型"
            }

        # 无法消歧
        return {
            "success": False,
            "disambiguated_type": possible_types[0] if possible_types else "UNKNOWN",
            "confidence": 0.5,
            "reasoning": "无法根据上下文确定类型，使用默认类型"
        }

    def expand_product_series(self, series_name: str) -> Dict[str, Any]:
        """
        展开产品系列

        Args:
            series_name: 系列名称

        Returns:
            展开结果
        """
        series_result = self.entity_service.expand_product_series(series_name)
        if series_result:
            return {
                "success": True,
                "series_name": series_result["series_name"],
                "models": series_result["models"]
            }
        else:
            return {
                "success": False,
                "error": f"未找到产品系列: {series_name}"
            }


# 全局实例
validation_service = ValidationService()
