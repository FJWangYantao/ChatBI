"""
数据库模型和操作
"""
import json
import logging
from typing import List, Optional, Dict, Any
from sqlalchemy import create_engine, Column, Integer, String, Text, DateTime, ForeignKey
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship
from sqlalchemy.sql import func
from datetime import datetime

from config import DATABASE_URL, INITIAL_DATA_FILE

logger = logging.getLogger(__name__)

Base = declarative_base()


class BusinessTerm(Base):
    """业务术语表"""
    __tablename__ = "business_terms"

    id = Column(Integer, primary_key=True, autoincrement=True)
    term = Column(String(100), nullable=False, index=True)
    category = Column(String(50), index=True)
    definition = Column(Text)
    aliases = Column(Text)  # JSON 数组
    examples = Column(Text)  # JSON 数组
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    # 关系
    column_mappings = relationship("ColumnMapping", back_populates="term")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "term": self.term,
            "category": self.category,
            "definition": self.definition,
            "aliases": json.loads(self.aliases) if self.aliases else [],
            "examples": json.loads(self.examples) if self.examples else [],
        }


class ColumnMapping(Base):
    """列映射表"""
    __tablename__ = "column_mappings"

    id = Column(Integer, primary_key=True, autoincrement=True)
    term_id = Column(Integer, ForeignKey("business_terms.id"))
    table_name = Column(String(100), index=True)
    column_name = Column(String(100))
    data_type = Column(String(50))
    description = Column(Text)
    sample_values = Column(Text)  # JSON 数组

    # 关系
    term = relationship("BusinessTerm", back_populates="column_mappings")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "term_id": self.term_id,
            "table_name": self.table_name,
            "column_name": self.column_name,
            "data_type": self.data_type,
            "description": self.description,
            "sample_values": json.loads(self.sample_values) if self.sample_values else [],
        }


class TimeExpression(Base):
    """时间表达式规则表"""
    __tablename__ = "time_expressions"

    id = Column(Integer, primary_key=True, autoincrement=True)
    pattern = Column(String(200))
    expression_type = Column(String(50), index=True)
    parse_rule = Column(Text)  # JSON
    examples = Column(Text)  # JSON 数组

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "pattern": self.pattern,
            "expression_type": self.expression_type,
            "parse_rule": json.loads(self.parse_rule) if self.parse_rule else {},
            "examples": json.loads(self.examples) if self.examples else [],
        }


class Entity(Base):
    """实体表（组织、地区等）"""
    __tablename__ = "entities"

    id = Column(Integer, primary_key=True, autoincrement=True)
    entity_type = Column(String(50), nullable=False, index=True)  # 'organization', 'location'
    entity_value = Column(String(200), nullable=False)
    aliases = Column(Text)  # JSON array of alternative names
    created_at = Column(DateTime, default=datetime.utcnow)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "entity_type": self.entity_type,
            "entity_value": self.entity_value,
            "aliases": json.loads(self.aliases) if self.aliases else [],
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


class ProductSeries(Base):
    """产品系列表"""
    __tablename__ = "product_series"

    id = Column(Integer, primary_key=True, autoincrement=True)
    series_name = Column(String(100), nullable=False, unique=True)
    models = Column(Text, nullable=False)  # JSON array of model names
    description = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "series_name": self.series_name,
            "models": json.loads(self.models) if self.models else [],
            "description": self.description,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


class ColumnDomainValue(Base):
    """列域值表"""
    __tablename__ = "column_domain_values"

    id = Column(Integer, primary_key=True, autoincrement=True)
    table_name = Column(String(100))
    column_name = Column(String(100), nullable=False, index=True)
    valid_values = Column(Text, nullable=False)  # JSON array
    created_at = Column(DateTime, default=datetime.utcnow)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "table_name": self.table_name,
            "column_name": self.column_name,
            "valid_values": json.loads(self.valid_values) if self.valid_values else [],
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


class Database:
    """数据库操作类"""

    def __init__(self, database_url: str = DATABASE_URL):
        self.engine = create_engine(database_url, echo=False)
        self.SessionLocal = sessionmaker(bind=self.engine)

    def init_db(self):
        """初始化数据库"""
        Base.metadata.create_all(self.engine)
        logger.info("数据库表创建成功")

    def load_initial_data(self):
        """加载初始数据"""
        session = self.SessionLocal()
        try:
            # 检查是否已有数据
            if session.query(BusinessTerm).count() > 0:
                logger.info("数据库已有数据，跳过初始化")
                return

            # 读取初始数据文件
            with open(INITIAL_DATA_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)

            # 插入业务术语
            term_map = {}
            for term_data in data.get("business_terms", []):
                term = BusinessTerm(
                    term=term_data["term"],
                    category=term_data.get("category"),
                    definition=term_data.get("definition"),
                    aliases=json.dumps(term_data.get("aliases", []), ensure_ascii=False),
                    examples=json.dumps(term_data.get("examples", []), ensure_ascii=False),
                )
                session.add(term)
                session.flush()
                term_map[term_data["term"]] = term.id

            # 插入列映射
            for mapping_data in data.get("column_mappings", []):
                term_name = mapping_data.get("term")
                term_id = term_map.get(term_name)
                if term_id:
                    mapping = ColumnMapping(
                        term_id=term_id,
                        table_name=mapping_data.get("table_name"),
                        column_name=mapping_data.get("column_name"),
                        data_type=mapping_data.get("data_type"),
                        description=mapping_data.get("description"),
                        sample_values=json.dumps(mapping_data.get("sample_values", []), ensure_ascii=False),
                    )
                    session.add(mapping)

            # 插入时间表达式
            for expr_data in data.get("time_expressions", []):
                expr = TimeExpression(
                    pattern=expr_data.get("pattern"),
                    expression_type=expr_data.get("expression_type"),
                    parse_rule=json.dumps(expr_data.get("parse_rule", {}), ensure_ascii=False),
                    examples=json.dumps(expr_data.get("examples", []), ensure_ascii=False),
                )
                session.add(expr)

            session.commit()
            logger.info("初始数据加载成功")

        except Exception as e:
            session.rollback()
            logger.error(f"加载初始数据失败: {e}")
            raise
        finally:
            session.close()

    def search_terms(self, keyword: str, category: Optional[str] = None) -> List[Dict[str, Any]]:
        """搜索术语"""
        session = self.SessionLocal()
        try:
            query = session.query(BusinessTerm)

            # 模糊搜索：术语名称或别名
            query = query.filter(
                (BusinessTerm.term.like(f"%{keyword}%")) |
                (BusinessTerm.aliases.like(f"%{keyword}%"))
            )

            if category:
                query = query.filter(BusinessTerm.category == category)

            results = query.all()
            return [term.to_dict() for term in results]

        finally:
            session.close()

    def get_column_mapping(self, term: str) -> Optional[Dict[str, Any]]:
        """获取术语的列映射"""
        session = self.SessionLocal()
        try:
            business_term = session.query(BusinessTerm).filter(
                (BusinessTerm.term == term) |
                (BusinessTerm.aliases.like(f'%"{term}"%'))
            ).first()

            if not business_term:
                return None

            mappings = session.query(ColumnMapping).filter(
                ColumnMapping.term_id == business_term.id
            ).all()

            return {
                "term": business_term.to_dict(),
                "mappings": [m.to_dict() for m in mappings]
            }

        finally:
            session.close()

    def get_time_expressions(self) -> List[Dict[str, Any]]:
        """获取所有时间表达式规则"""
        session = self.SessionLocal()
        try:
            expressions = session.query(TimeExpression).all()
            return [expr.to_dict() for expr in expressions]
        finally:
            session.close()

    # ============ 业务术语 CRUD ============

    def get_all_terms(self) -> List[Dict[str, Any]]:
        """获取所有术语"""
        session = self.SessionLocal()
        try:
            terms = session.query(BusinessTerm).all()
            return [term.to_dict() for term in terms]
        finally:
            session.close()

    def get_term_by_id(self, term_id: int) -> Optional[Dict[str, Any]]:
        """根据 ID 获取术语"""
        session = self.SessionLocal()
        try:
            term = session.query(BusinessTerm).filter(BusinessTerm.id == term_id).first()
            return term.to_dict() if term else None
        finally:
            session.close()

    def create_term(self, term_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建术语"""
        session = self.SessionLocal()
        try:
            term = BusinessTerm(
                term=term_data["term"],
                category=term_data.get("category"),
                definition=term_data.get("definition"),
                aliases=json.dumps(term_data.get("aliases", []), ensure_ascii=False),
                examples=json.dumps(term_data.get("examples", []), ensure_ascii=False),
            )
            session.add(term)
            session.commit()
            session.refresh(term)
            return term.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def update_term(self, term_id: int, term_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """更新术语"""
        session = self.SessionLocal()
        try:
            term = session.query(BusinessTerm).filter(BusinessTerm.id == term_id).first()
            if not term:
                return None

            if "term" in term_data:
                term.term = term_data["term"]
            if "category" in term_data:
                term.category = term_data["category"]
            if "definition" in term_data:
                term.definition = term_data["definition"]
            if "aliases" in term_data:
                term.aliases = json.dumps(term_data["aliases"], ensure_ascii=False)
            if "examples" in term_data:
                term.examples = json.dumps(term_data["examples"], ensure_ascii=False)

            term.updated_at = datetime.utcnow()
            session.commit()
            session.refresh(term)
            return term.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def delete_term(self, term_id: int) -> bool:
        """删除术语（级联删除关联的列映射）"""
        session = self.SessionLocal()
        try:
            term = session.query(BusinessTerm).filter(BusinessTerm.id == term_id).first()
            if not term:
                return False

            # 删除关联的列映射
            session.query(ColumnMapping).filter(ColumnMapping.term_id == term_id).delete()

            session.delete(term)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    # ============ 列映射 CRUD ============

    def get_all_mappings(self, term_id: Optional[int] = None) -> List[Dict[str, Any]]:
        """获取所有列映射"""
        session = self.SessionLocal()
        try:
            query = session.query(ColumnMapping)
            if term_id:
                query = query.filter(ColumnMapping.term_id == term_id)
            mappings = query.all()
            return [m.to_dict() for m in mappings]
        finally:
            session.close()

    def get_mapping_by_id(self, mapping_id: int) -> Optional[Dict[str, Any]]:
        """根据 ID 获取列映射"""
        session = self.SessionLocal()
        try:
            mapping = session.query(ColumnMapping).filter(ColumnMapping.id == mapping_id).first()
            return mapping.to_dict() if mapping else None
        finally:
            session.close()

    def create_mapping(self, mapping_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建列映射"""
        session = self.SessionLocal()
        try:
            mapping = ColumnMapping(
                term_id=mapping_data["term_id"],
                table_name=mapping_data["table_name"],
                column_name=mapping_data["column_name"],
                data_type=mapping_data["data_type"],
                description=mapping_data.get("description"),
                sample_values=json.dumps(mapping_data.get("sample_values", []), ensure_ascii=False),
            )
            session.add(mapping)
            session.commit()
            session.refresh(mapping)
            return mapping.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def update_mapping(self, mapping_id: int, mapping_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """更新列映射"""
        session = self.SessionLocal()
        try:
            mapping = session.query(ColumnMapping).filter(ColumnMapping.id == mapping_id).first()
            if not mapping:
                return None

            for key, value in mapping_data.items():
                if key == "sample_values":
                    setattr(mapping, key, json.dumps(value, ensure_ascii=False))
                else:
                    setattr(mapping, key, value)

            session.commit()
            session.refresh(mapping)
            return mapping.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def delete_mapping(self, mapping_id: int) -> bool:
        """删除列映射"""
        session = self.SessionLocal()
        try:
            mapping = session.query(ColumnMapping).filter(ColumnMapping.id == mapping_id).first()
            if not mapping:
                return False
            session.delete(mapping)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    # ============ 时间表达式 CRUD ============

    def get_all_expressions(self) -> List[Dict[str, Any]]:
        """获取所有时间表达式"""
        session = self.SessionLocal()
        try:
            expressions = session.query(TimeExpression).all()
            return [expr.to_dict() for expr in expressions]
        finally:
            session.close()

    def get_expression_by_id(self, expr_id: int) -> Optional[Dict[str, Any]]:
        """根据 ID 获取时间表达式"""
        session = self.SessionLocal()
        try:
            expr = session.query(TimeExpression).filter(TimeExpression.id == expr_id).first()
            return expr.to_dict() if expr else None
        finally:
            session.close()

    def create_expression(self, expr_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建时间表达式"""
        session = self.SessionLocal()
        try:
            expr = TimeExpression(
                pattern=expr_data["pattern"],
                expression_type=expr_data["expression_type"],
                parse_rule=json.dumps(expr_data["parse_rule"], ensure_ascii=False),
                examples=json.dumps(expr_data.get("examples", []), ensure_ascii=False),
            )
            session.add(expr)
            session.commit()
            session.refresh(expr)
            return expr.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def update_expression(self, expr_id: int, expr_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """更新时间表达式"""
        session = self.SessionLocal()
        try:
            expr = session.query(TimeExpression).filter(TimeExpression.id == expr_id).first()
            if not expr:
                return None

            for key, value in expr_data.items():
                if key in ["parse_rule", "examples"]:
                    setattr(expr, key, json.dumps(value, ensure_ascii=False))
                else:
                    setattr(expr, key, value)

            session.commit()
            session.refresh(expr)
            return expr.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    def delete_expression(self, expr_id: int) -> bool:
        """删除时间表达式"""
        session = self.SessionLocal()
        try:
            expr = session.query(TimeExpression).filter(TimeExpression.id == expr_id).first()
            if not expr:
                return False
            session.delete(expr)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    # ============ 统计信息 ============

    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        session = self.SessionLocal()
        try:
            total_terms = session.query(BusinessTerm).count()
            total_mappings = session.query(ColumnMapping).count()
            total_expressions = session.query(TimeExpression).count()

            # 按类别统计术语
            categories = {}
            terms = session.query(BusinessTerm).all()
            for term in terms:
                cat = term.category or "未分类"
                categories[cat] = categories.get(cat, 0) + 1

            return {
                "total_terms": total_terms,
                "total_mappings": total_mappings,
                "total_expressions": total_expressions,
                "categories": categories,
            }
        finally:
            session.close()

    # ============ 实体 CRUD ============

    def get_all_entities(self, entity_type: Optional[str] = None) -> List[Dict[str, Any]]:
        """获取所有实体"""
        session = self.SessionLocal()
        try:
            query = session.query(Entity)
            if entity_type:
                query = query.filter(Entity.entity_type == entity_type)
            entities = query.all()
            return [e.to_dict() for e in entities]
        finally:
            session.close()

    def get_entity_by_value(self, entity_value: str, entity_type: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """根据值获取实体"""
        session = self.SessionLocal()
        try:
            query = session.query(Entity).filter(Entity.entity_value == entity_value)
            if entity_type:
                query = query.filter(Entity.entity_type == entity_type)
            entity = query.first()
            return entity.to_dict() if entity else None
        finally:
            session.close()

    def create_entity(self, entity_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建实体"""
        session = self.SessionLocal()
        try:
            entity = Entity(
                entity_type=entity_data["entity_type"],
                entity_value=entity_data["entity_value"],
                aliases=json.dumps(entity_data.get("aliases", []), ensure_ascii=False),
            )
            session.add(entity)
            session.commit()
            session.refresh(entity)
            return entity.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    # ============ 产品系列 CRUD ============

    def get_all_product_series(self) -> List[Dict[str, Any]]:
        """获取所有产品系列"""
        session = self.SessionLocal()
        try:
            series = session.query(ProductSeries).all()
            return [s.to_dict() for s in series]
        finally:
            session.close()

    def get_product_series_by_name(self, series_name: str) -> Optional[Dict[str, Any]]:
        """根据名称获取产品系列"""
        session = self.SessionLocal()
        try:
            series = session.query(ProductSeries).filter(ProductSeries.series_name == series_name).first()
            return series.to_dict() if series else None
        finally:
            session.close()

    def create_product_series(self, series_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建产品系列"""
        session = self.SessionLocal()
        try:
            series = ProductSeries(
                series_name=series_data["series_name"],
                models=json.dumps(series_data["models"], ensure_ascii=False),
                description=series_data.get("description"),
            )
            session.add(series)
            session.commit()
            session.refresh(series)
            return series.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()

    # ============ 列域值 CRUD ============

    def get_all_column_domain_values(self) -> List[Dict[str, Any]]:
        """获取所有列域值"""
        session = self.SessionLocal()
        try:
            values = session.query(ColumnDomainValue).all()
            return [v.to_dict() for v in values]
        finally:
            session.close()

    def get_column_domain_values_by_column(self, column_name: str, table_name: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """根据列名获取域值"""
        session = self.SessionLocal()
        try:
            query = session.query(ColumnDomainValue).filter(ColumnDomainValue.column_name == column_name)
            if table_name:
                query = query.filter(ColumnDomainValue.table_name == table_name)
            value = query.first()
            return value.to_dict() if value else None
        finally:
            session.close()

    def create_column_domain_value(self, domain_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建列域值"""
        session = self.SessionLocal()
        try:
            domain_value = ColumnDomainValue(
                table_name=domain_data.get("table_name"),
                column_name=domain_data["column_name"],
                valid_values=json.dumps(domain_data["valid_values"], ensure_ascii=False),
            )
            session.add(domain_value)
            session.commit()
            session.refresh(domain_value)
            return domain_value.to_dict()
        except Exception as e:
            session.rollback()
            raise
        finally:
            session.close()


# 全局数据库实例
db = Database()
