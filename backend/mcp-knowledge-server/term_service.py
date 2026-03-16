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
        elif expr_type == "relative_time":
            return self._parse_relative_time(expression, reference_date)
        elif expr_type == "relative_years":
            return self._parse_relative_years(expression, reference_date)
        elif expr_type == "half_year":
            return self._parse_half_year(expression, reference_date)
        elif expr_type == "fiscal_quarter":
            return self._parse_fiscal_quarter(expression, parse_rule, reference_date)
        elif expr_type == "calendar_quarter":
            return self._parse_calendar_quarter(expression, reference_date)
        elif expr_type == "cross_fiscal_year":
            return self._parse_cross_fiscal_year(expression, parse_rule)
        elif expr_type == "fiscal_year_quarter":
            return self._parse_fiscal_year_quarter(expression, parse_rule)

        logger.warning(f"未知的时间表达式类型: {expr_type}")
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

    def _parse_relative_time(self, expression: str, reference_date: date) -> Dict[str, Any]:
        """解析相对时间表达式（如 "过去3个月", "近6个月", "上个月"）"""
        if "上个月" in expression:
            end_d = date(reference_date.year, reference_date.month, 1) - relativedelta(days=1)
            start_d = date(end_d.year, end_d.month, 1)
            return {
                "expression": expression,
                "type": "relative_time",
                "start_date": start_d.isoformat(),
                "end_date": end_d.isoformat(),
                "sql_condition": f"Year = {start_d.year} AND Month = {start_d.month}",
                "description": f"上个月（{start_d} 至 {end_d}）"
            }

        num_match = re.search(r'(\d+)', expression)
        if not num_match:
            return None
        months = int(num_match.group(1))

        # 结束日期：当前月的前一天（即上个月最后一天）
        end_d = date(reference_date.year, reference_date.month, 1) - relativedelta(days=1)
        # 起始日期：往前推 N 个月的第一天
        start_d = date(end_d.year, end_d.month, 1) - relativedelta(months=months - 1)

        # 生成 SQL 条件
        if start_d.year == end_d.year:
            sql_cond = f"Year = {start_d.year} AND Month >= {start_d.month} AND Month <= {end_d.month}"
        else:
            sql_cond = (f"((Year = {start_d.year} AND Month >= {start_d.month}) OR "
                        f"(Year > {start_d.year} AND Year < {end_d.year}) OR "
                        f"(Year = {end_d.year} AND Month <= {end_d.month}))")

        return {
            "expression": expression,
            "type": "relative_time",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": sql_cond,
            "description": f"过去{months}个月（{start_d} 至 {end_d}）"
        }

    def _parse_relative_years(self, expression: str, reference_date: date) -> Dict[str, Any]:
        """解析相对年份表达式（如 "过去3年", "近5年"）"""
        num_match = re.search(r'(\d+)', expression)
        if not num_match:
            return None
        years = int(num_match.group(1))

        end_d = date(reference_date.year - 1, 12, 31)
        start_d = date(reference_date.year - years, 1, 1)

        sql_cond = f"Year >= {start_d.year} AND Year <= {end_d.year}"

        return {
            "expression": expression,
            "type": "relative_years",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": sql_cond,
            "description": f"过去{years}年（{start_d.year} 至 {end_d.year}）"
        }

    def _parse_half_year(self, expression: str, reference_date: date) -> Dict[str, Any]:
        """解析半年表达式（如 "上半年", "下半年", "1H", "2H"）"""
        is_first_half = "上半年" in expression or "1H" in expression

        if is_first_half:
            start_month, end_month = 1, 6
            label = "上半年"
        else:
            start_month, end_month = 7, 12
            label = "下半年"

        year = reference_date.year
        start_d = date(year, start_month, 1)
        end_d = date(year, end_month, 30 if end_month == 6 else 31)

        return {
            "expression": expression,
            "type": "half_year",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": f"Year = {year} AND Month >= {start_month} AND Month <= {end_month}",
            "description": f"{year}年{label}（{start_d} 至 {end_d}）"
        }

    def _parse_fiscal_quarter(self, expression: str, rule: Dict[str, Any], reference_date: date) -> Dict[str, Any]:
        """解析财务季度表达式（如 "FQ1", "FQ3"）
        联想财年：FQ1=4-6月, FQ2=7-9月, FQ3=10-12月, FQ4=1-3月
        """
        fq_match = re.search(r'FQ([1-4])', expression)
        if not fq_match:
            return None
        fq = int(fq_match.group(1))

        # 财务季度到自然月的映射
        fq_month_map = {1: (4, 6), 2: (7, 9), 3: (10, 12), 4: (1, 3)}
        start_month, end_month = fq_month_map[fq]

        year = reference_date.year
        # FQ4 属于下一自然年的 1-3 月
        if fq == 4:
            year = reference_date.year + 1 if reference_date.month >= 4 else reference_date.year

        start_d = date(year, start_month, 1)
        end_d = date(year, end_month, 1) + relativedelta(months=1, days=-1)

        return {
            "expression": expression,
            "type": "fiscal_quarter",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": f"Year = {year} AND Month >= {start_month} AND Month <= {end_month}",
            "description": f"财务季度FQ{fq}（{start_d} 至 {end_d}）"
        }

    def _parse_calendar_quarter(self, expression: str, reference_date: date) -> Dict[str, Any]:
        """解析自然季度表达式（如 "CQ1", "CQ3"）
        CQ1=1-3月, CQ2=4-6月, CQ3=7-9月, CQ4=10-12月
        """
        cq_match = re.search(r'CQ([1-4])', expression)
        if not cq_match:
            return None
        cq = int(cq_match.group(1))

        year = reference_date.year
        start_month = (cq - 1) * 3 + 1
        start_d = date(year, start_month, 1)

        if cq == 4:
            end_d = date(year, 12, 31)
        else:
            end_d = date(year, start_month + 2, 1) + relativedelta(months=1, days=-1)

        return {
            "expression": expression,
            "type": "calendar_quarter",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": f"Year = {year} AND Month >= {start_month} AND Month <= {start_month + 2}",
            "description": f"{year}年自然季度CQ{cq}（{start_d} 至 {end_d}）"
        }

    def _parse_cross_fiscal_year(self, expression: str, rule: Dict[str, Any]) -> Dict[str, Any]:
        """解析跨财年表达式（如 "FY23/24"）
        FY23/24 表示 FY23 和 FY24 两个完整财年，即 2023-04-01 至 2025-03-31
        """
        match = re.search(r'FY(\d{2})/(\d{2})', expression)
        if not match:
            return None
        start_fy = 2000 + int(match.group(1))
        end_fy = 2000 + int(match.group(2))

        start_month = rule.get("start_month", 4)
        start_d = date(start_fy, start_month, 1)
        end_d = date(end_fy + 1, start_month, 1) - relativedelta(days=1)

        return {
            "expression": expression,
            "type": "cross_fiscal_year",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": f"FiscalYear >= {start_fy} AND FiscalYear <= {end_fy}",
            "description": f"FY{match.group(1)}/FY{match.group(2)}（{start_d} 至 {end_d}）"
        }

    def _parse_fiscal_year_quarter(self, expression: str, rule: Dict[str, Any]) -> Dict[str, Any]:
        """解析财年+财务季度表达式（如 "23FQ1", "FY23 FQ2"）"""
        match = re.search(r'(\d{2,4})\s*FQ([1-4])', expression)
        if not match:
            return None
        year_str = match.group(1)
        if len(year_str) == 2:
            fy = 2000 + int(year_str)
        else:
            fy = int(year_str)
        fq = int(match.group(2))

        # 财务季度到自然月的映射（财年从4月开始）
        fq_month_map = {1: (4, 6), 2: (7, 9), 3: (10, 12), 4: (1, 3)}
        start_month, end_month = fq_month_map[fq]

        # FQ4 的自然年是财年+1
        if fq == 4:
            natural_year = fy + 1
        else:
            natural_year = fy

        start_d = date(natural_year, start_month, 1)
        end_d = date(natural_year, end_month, 1) + relativedelta(months=1, days=-1)

        return {
            "expression": expression,
            "type": "fiscal_year_quarter",
            "start_date": start_d.isoformat(),
            "end_date": end_d.isoformat(),
            "sql_condition": f"FiscalYear = {fy} AND FiscalQuarter = {fq}",
            "description": f"FY{fy} FQ{fq}（{start_d} 至 {end_d}）"
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
