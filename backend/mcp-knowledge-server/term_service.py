"""
术语查询服务
"""
import re
import logging
from typing import List, Dict, Any, Optional
from datetime import datetime, date
from dateutil.relativedelta import relativedelta

from database import db

logger = logging.getLogger(__name__)


class TermService:
    """术语查询服务"""

    def __init__(self):
        self._time_expressions = None

    @property
    def time_expressions(self):
        if self._time_expressions is None:
            self._time_expressions = db.get_time_expressions()
        return self._time_expressions

    def search_terms(self, keyword: str, category: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        搜索业务术语

        Args:
            keyword: 搜索关键词
            category: 术语类别（可选）

        Returns:
            匹配的术语列表
        """
        results = db.search_terms(keyword, category)
        logger.info(f"搜索术语 '{keyword}' (类别: {category})，找到 {len(results)} 条结果")
        return results

    def get_column_mapping(self, term: str) -> Optional[Dict[str, Any]]:
        """
        获取术语对应的数据库列映射

        Args:
            term: 术语名称

        Returns:
            列映射信息
        """
        result = db.get_column_mapping(term)
        if result:
            logger.info(f"找到术语 '{term}' 的列映射")
        else:
            logger.warning(f"未找到术语 '{term}' 的列映射")
        return result

    def parse_time_expression(self, expression: str, reference_date: Optional[date] = None) -> Optional[Dict[str, Any]]:
        """
        解析时间表达式

        Args:
            expression: 时间表达式（如 "FY23", "Q1", "2024年Q2"）
            reference_date: 参考日期（默认为今天）

        Returns:
            解析结果，包含 start_date, end_date, sql_condition
        """
        if reference_date is None:
            reference_date = date.today()

        # 尝试匹配各种时间表达式模式
        for expr_rule in self.time_expressions:
            pattern = expr_rule["pattern"]
            if re.match(pattern, expression):
                return self._parse_by_rule(expression, expr_rule, reference_date)

        logger.warning(f"无法解析时间表达式: {expression}")
        return None

    def _parse_by_rule(self, expression: str, rule: Dict[str, Any], reference_date: date) -> Dict[str, Any]:
        """根据规则解析时间表达式"""
        parse_rule = rule["parse_rule"]
        expr_type = parse_rule.get("type")

        if expr_type == "fiscal_year":
            return self._parse_fiscal_year(expression, parse_rule, reference_date)
        elif expr_type == "quarter":
            return self._parse_quarter(expression, reference_date)
        elif expr_type == "year_quarter":
            return self._parse_year_quarter(expression)

        return None

    def _parse_fiscal_year(self, expression: str, rule: Dict[str, Any], reference_date: date) -> Dict[str, Any]:
        """解析财年表达式"""
        # 提取年份
        year_match = re.search(r'\d+', expression)
        if not year_match:
            return None

        year_str = year_match.group()

        # 判断是两位数还是四位数
        if len(year_str) == 2:
            year = 2000 + int(year_str)
        else:
            year = int(year_str)

        # 财年起始月份（默认1月）
        start_month = rule.get("start_month", 1)

        start_date = date(year, start_month, 1)
        end_date = date(year, 12, 31)

        return {
            "expression": expression,
            "type": "fiscal_year",
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "sql_condition": f"date_column >= '{start_date}' AND date_column <= '{end_date}'",
            "description": f"财年{year}（{start_date} 至 {end_date}）"
        }

    def _parse_quarter(self, expression: str, reference_date: date) -> Dict[str, Any]:
        """解析季度表达式（相对于参考日期）"""
        quarter_match = re.search(r'Q([1-4])', expression)
        if not quarter_match:
            return None

        quarter = int(quarter_match.group(1))
        year = reference_date.year

        # 计算季度的起止日期
        start_month = (quarter - 1) * 3 + 1
        start_date = date(year, start_month, 1)

        if quarter == 4:
            end_date = date(year, 12, 31)
        else:
            end_date = date(year, start_month + 2, 1) + relativedelta(months=1, days=-1)

        return {
            "expression": expression,
            "type": "quarter",
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "sql_condition": f"date_column >= '{start_date}' AND date_column <= '{end_date}'",
            "description": f"{year}年第{quarter}季度（{start_date} 至 {end_date}）"
        }

    def _parse_year_quarter(self, expression: str) -> Dict[str, Any]:
        """解析年度+季度表达式（如 "2024年Q1"）"""
        match = re.search(r'(\d{4})年Q([1-4])', expression)
        if not match:
            return None

        year = int(match.group(1))
        quarter = int(match.group(2))

        # 计算季度的起止日期
        start_month = (quarter - 1) * 3 + 1
        start_date = date(year, start_month, 1)

        if quarter == 4:
            end_date = date(year, 12, 31)
        else:
            end_date = date(year, start_month + 2, 1) + relativedelta(months=1, days=-1)

        return {
            "expression": expression,
            "type": "year_quarter",
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "sql_condition": f"date_column >= '{start_date}' AND date_column <= '{end_date}'",
            "description": f"{year}年第{quarter}季度（{start_date} 至 {end_date}）"
        }

    def enrich_query_context(self, user_query: str) -> Dict[str, Any]:
        """
        为用户查询增强上下文

        分析用户查询中的术语，返回相关的定义和映射信息

        Args:
            user_query: 用户的原始查询

        Returns:
            增强的上下文信息
        """
        context = {
            "original_query": user_query,
            "identified_terms": [],
            "time_expressions": [],
            "column_mappings": [],
            "enriched_prompt": ""
        }

        # 1. 搜索所有可能的术语
        all_terms = db.search_terms("")  # 获取所有术语

        for term_data in all_terms:
            term = term_data["term"]
            aliases = term_data.get("aliases", [])

            # 检查查询中是否包含该术语或其别名
            if term in user_query or any(alias in user_query for alias in aliases):
                context["identified_terms"].append(term_data)

                # 获取列映射
                mapping = db.get_column_mapping(term)
                if mapping:
                    context["column_mappings"].append(mapping)

        # 2. 解析时间表达式
        for expr_rule in self.time_expressions:
            pattern = expr_rule["pattern"]
            matches = re.findall(pattern, user_query)
            for match in matches:
                parsed = self.parse_time_expression(match)
                if parsed:
                    context["time_expressions"].append(parsed)

        # 3. 生成增强的 Prompt
        prompt_parts = [f"用户问题：{user_query}\n"]

        if context["identified_terms"]:
            prompt_parts.append("\n业务术语解释：")
            for term in context["identified_terms"]:
                prompt_parts.append(f"- {term['term']}: {term['definition']}")

        if context["column_mappings"]:
            prompt_parts.append("\n数据库列映射：")
            for mapping in context["column_mappings"]:
                term_info = mapping["term"]
                for m in mapping["mappings"]:
                    prompt_parts.append(
                        f"- {term_info['term']} → 表: {m['table_name']}, 列: {m['column_name']} ({m['data_type']})"
                    )

        if context["time_expressions"]:
            prompt_parts.append("\n时间范围解析：")
            for time_expr in context["time_expressions"]:
                prompt_parts.append(f"- {time_expr['expression']}: {time_expr['description']}")
                prompt_parts.append(f"  SQL条件: {time_expr['sql_condition']}")

        context["enriched_prompt"] = "\n".join(prompt_parts)

        logger.info(f"查询上下文增强完成，识别到 {len(context['identified_terms'])} 个术语")

        return context


# 全局服务实例
term_service = TermService()
