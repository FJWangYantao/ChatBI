"""
MCP 知识库服务器

提供业务术语、数据字典、时间表达式解析等功能
符合 MCP (Model Context Protocol) 规范
"""
import logging
from typing import Optional, List, Any, Dict
from datetime import date

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn

from config import HOST, PORT, LOG_LEVEL
from database import db
from term_service import TermService
from validation_service import validation_service

# 配置日志
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# 全局服务实例（在 startup 事件中初始化）
term_service: TermService = None

# 创建 FastAPI 应用
app = FastAPI(
    title="MCP Knowledge Server",
    description="业务术语知识库 MCP 服务器 - 为 ChatBI 提供术语查询和上下文增强",
    version="1.0.0"
)

# 配置 CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============ 请求/响应模型 ============

class SearchTermsRequest(BaseModel):
    """搜索术语请求"""
    keyword: str = Field(..., description="搜索关键词")
    category: Optional[str] = Field(None, description="术语类别（可选）")


class GetColumnMappingRequest(BaseModel):
    """获取列映射请求"""
    term: str = Field(..., description="术语名称")


class ParseTimeExpressionRequest(BaseModel):
    """解析时间表达式请求"""
    expression: str = Field(..., description="时间表达式")
    reference_date: Optional[str] = Field(None, description="参考日期 YYYY-MM-DD")


class EnrichQueryContextRequest(BaseModel):
    """增强查询上下文请求"""
    query: str = Field(..., description="用户查询")


class ValidateEntitiesRequest(BaseModel):
    """验证实体请求"""
    entities: List[dict] = Field(..., description="实体列表，每个实体包含 text 和 type")


class DisambiguateEntityRequest(BaseModel):
    """实体消歧请求"""
    entity: str = Field(..., description="实体文本")
    context: str = Field(..., description="上下文")
    possible_types: List[str] = Field(..., description="可能的类型列表")


class ExpandProductSeriesRequest(BaseModel):
    """展开产品系列请求"""
    series_name: str = Field(..., description="产品系列名称")


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str
    version: str
    database_connected: bool


# ============ CRUD 请求/响应模型 ============

from typing import List, Any

class CreateBusinessTermRequest(BaseModel):
    """创建业务术语请求"""
    term: str = Field(..., min_length=1, max_length=100, description="术语名称")
    category: str = Field(..., max_length=50, description="术语类别")
    definition: str = Field(..., min_length=1, description="术语定义")
    aliases: List[str] = Field(default_factory=list, description="别名列表")
    examples: List[str] = Field(default_factory=list, description="示例列表")
    sql_hint: Optional[str] = Field(None, description="SQL生成提示")


class UpdateBusinessTermRequest(BaseModel):
    """更新业务术语请求"""
    term: Optional[str] = Field(None, min_length=1, max_length=100, description="术语名称")
    category: Optional[str] = Field(None, max_length=50, description="术语类别")
    definition: Optional[str] = Field(None, min_length=1, description="术语定义")
    aliases: Optional[List[str]] = Field(None, description="别名列表")
    examples: Optional[List[str]] = Field(None, description="示例列表")
    sql_hint: Optional[str] = Field(None, description="SQL生成提示")


class CreateColumnMappingRequest(BaseModel):
    """创建列映射请求"""
    term_id: int = Field(..., gt=0, description="关联的术语ID")
    table_name: str = Field(..., min_length=1, max_length=100, description="表名")
    column_name: str = Field(..., min_length=1, max_length=100, description="列名")
    data_type: str = Field(..., max_length=50, description="数据类型")
    description: Optional[str] = Field(None, description="描述")
    sample_values: List[str] = Field(default_factory=list, description="示例值列表")


class UpdateColumnMappingRequest(BaseModel):
    """更新列映射请求"""
    term_id: Optional[int] = Field(None, gt=0, description="关联的术语ID")
    table_name: Optional[str] = Field(None, min_length=1, max_length=100, description="表名")
    column_name: Optional[str] = Field(None, min_length=1, max_length=100, description="列名")
    data_type: Optional[str] = Field(None, max_length=50, description="数据类型")
    description: Optional[str] = Field(None, description="描述")
    sample_values: Optional[List[str]] = Field(None, description="示例值列表")


class CreateTimeExpressionRequest(BaseModel):
    """创建时间表达式请求"""
    pattern: str = Field(..., min_length=1, max_length=200, description="匹配模式")
    expression_type: str = Field(..., max_length=50, description="表达式类型")
    parse_rule: dict = Field(..., description="解析规则")
    examples: List[str] = Field(default_factory=list, description="示例列表")


class UpdateTimeExpressionRequest(BaseModel):
    """更新时间表达式请求"""
    pattern: Optional[str] = Field(None, min_length=1, max_length=200, description="匹配模式")
    expression_type: Optional[str] = Field(None, max_length=50, description="表达式类型")
    parse_rule: Optional[dict] = Field(None, description="解析规则")
    examples: Optional[List[str]] = Field(None, description="示例列表")


# ============ 生命周期事件 ============

@app.on_event("startup")
async def startup_event():
    """启动时初始化数据库"""
    logger.info("=" * 60)
    logger.info("MCP 知识库服务器启动中...")
    logger.info("=" * 60)

    try:
        db.init_db()
        db.load_initial_data()
        logger.info("数据库初始化成功")
    except Exception as e:
        logger.error(f"数据库初始化失败: {e}")
        raise

    # 数据库就绪后再创建服务实例
    global term_service
    term_service = TermService()

    logger.info("=" * 60)
    logger.info(f"MCP 服务器启动完成，监听 {HOST}:{PORT}")
    logger.info("=" * 60)


# ============ API 路由 ============

@app.get("/", response_model=dict)
async def root():
    """根路径"""
    return {
        "service": "MCP Knowledge Server",
        "version": "1.0.0",
        "description": "业务术语知识库服务",
        "endpoints": {
            "POST /tools/search_terms": "搜索业务术语",
            "POST /tools/get_column_mapping": "获取术语的列映射",
            "POST /tools/parse_time_expression": "解析时间表达式",
            "POST /tools/enrich_query_context": "增强查询上下文",
            "GET /health": "健康检查"
        }
    }


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查"""
    try:
        # 测试数据库连接
        db.search_terms("test")
        database_connected = True
    except Exception:
        database_connected = False

    return HealthResponse(
        status="healthy" if database_connected else "unhealthy",
        version="1.0.0",
        database_connected=database_connected
    )


@app.post("/tools/search_terms")
async def search_terms(request: SearchTermsRequest):
    """
    搜索业务术语

    MCP Tool: search_terms
    """
    try:
        results = term_service.search_terms(request.keyword, request.category)
        return {
            "success": True,
            "keyword": request.keyword,
            "category": request.category,
            "results": results,
            "count": len(results)
        }
    except Exception as e:
        logger.error(f"搜索术语失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/get_column_mapping")
async def get_column_mapping(request: GetColumnMappingRequest):
    """
    获取术语对应的数据库列映射

    MCP Tool: get_column_mapping
    """
    try:
        result = term_service.get_column_mapping(request.term)
        if result is None:
            return {
                "success": False,
                "term": request.term,
                "message": f"未找到术语 '{request.term}' 的列映射"
            }

        return {
            "success": True,
            "term": request.term,
            "result": result
        }
    except Exception as e:
        logger.error(f"获取列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/parse_time_expression")
async def parse_time_expression(request: ParseTimeExpressionRequest):
    """
    解析时间表达式

    MCP Tool: parse_time_expression
    """
    try:
        reference_date = None
        if request.reference_date:
            reference_date = date.fromisoformat(request.reference_date)

        result = term_service.parse_time_expression(request.expression, reference_date)

        if result is None:
            return {
                "success": False,
                "expression": request.expression,
                "message": f"无法解析时间表达式 '{request.expression}'"
            }

        return {
            "success": True,
            "expression": request.expression,
            "result": result
        }
    except Exception as e:
        logger.error(f"解析时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/enrich_query_context")
async def enrich_query_context(request: EnrichQueryContextRequest):
    """
    为用户查询增强上下文

    MCP Tool: enrich_query_context
    这是最核心的工具，会自动识别查询中的术语、时间表达式等，
    返回增强后的上下文信息，可直接用于 AI Prompt
    """
    try:
        result = term_service.enrich_query_context(request.query)
        return {
            "success": True,
            "result": result
        }
    except Exception as e:
        logger.error(f"增强查询上下文失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/validate_entities")
async def validate_entities(request: ValidateEntitiesRequest):
    """
    验证实体列表

    MCP Tool: validate_entities
    验证实体是否存在于知识库中，返回验证结果和建议
    """
    try:
        result = validation_service.validate_entities(request.entities)
        return result
    except Exception as e:
        logger.error(f"验证实体失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/disambiguate_entity")
async def disambiguate_entity(request: DisambiguateEntityRequest):
    """
    实体消歧

    MCP Tool: disambiguate_entity
    根据上下文确定实体的类型
    """
    try:
        result = validation_service.disambiguate_entity(
            request.entity,
            request.context,
            request.possible_types
        )
        return result
    except Exception as e:
        logger.error(f"实体消歧失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tools/expand_product_series")
async def expand_product_series(request: ExpandProductSeriesRequest):
    """
    展开产品系列

    MCP Tool: expand_product_series
    将产品系列名称展开为具体的型号列表
    """
    try:
        result = validation_service.expand_product_series(request.series_name)
        return result
    except Exception as e:
        logger.error(f"展开产品系列失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ============ CRUD API 路由 ============

# 业务术语 CRUD

@app.get("/api/terms")
async def get_all_terms():
    """获取所有术语"""
    try:
        terms = db.get_all_terms()
        return {"success": True, "data": terms}
    except Exception as e:
        logger.error(f"获取术语列表失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/terms/{term_id}")
async def get_term(term_id: int):
    """获取单个术语"""
    try:
        term = db.get_term_by_id(term_id)
        if not term:
            raise HTTPException(status_code=404, detail="术语不存在")
        return {"success": True, "data": term}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取术语失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/terms")
async def create_term(request: CreateBusinessTermRequest):
    """创建术语"""
    try:
        term = db.create_term(request.dict())
        return {"success": True, "message": "术语创建成功", "data": term}
    except Exception as e:
        logger.error(f"创建术语失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.put("/api/terms/{term_id}")
async def update_term(term_id: int, request: UpdateBusinessTermRequest):
    """更新术语"""
    try:
        term = db.update_term(term_id, request.dict(exclude_unset=True))
        if not term:
            raise HTTPException(status_code=404, detail="术语不存在")
        return {"success": True, "message": "术语更新成功", "data": term}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新术语失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/terms/{term_id}")
async def delete_term(term_id: int):
    """删除术语"""
    try:
        success = db.delete_term(term_id)
        if not success:
            raise HTTPException(status_code=404, detail="术语不存在")
        return {"success": True, "message": "术语删除成功"}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"删除术语失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# 列映射 CRUD

@app.get("/api/mappings")
async def get_all_mappings(term_id: Optional[int] = None):
    """获取所有列映射"""
    try:
        mappings = db.get_all_mappings(term_id)
        return {"success": True, "data": mappings}
    except Exception as e:
        logger.error(f"获取列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/mappings/{mapping_id}")
async def get_mapping(mapping_id: int):
    """获取单个列映射"""
    try:
        mapping = db.get_mapping_by_id(mapping_id)
        if not mapping:
            raise HTTPException(status_code=404, detail="列映射不存在")
        return {"success": True, "data": mapping}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/mappings")
async def create_mapping(request: CreateColumnMappingRequest):
    """创建列映射"""
    try:
        mapping = db.create_mapping(request.dict())
        return {"success": True, "message": "列映射创建成功", "data": mapping}
    except Exception as e:
        logger.error(f"创建列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.put("/api/mappings/{mapping_id}")
async def update_mapping(mapping_id: int, request: UpdateColumnMappingRequest):
    """更新列映射"""
    try:
        mapping = db.update_mapping(mapping_id, request.dict(exclude_unset=True))
        if not mapping:
            raise HTTPException(status_code=404, detail="列映射不存在")
        return {"success": True, "message": "列映射更新成功", "data": mapping}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/mappings/{mapping_id}")
async def delete_mapping(mapping_id: int):
    """删除列映射"""
    try:
        success = db.delete_mapping(mapping_id)
        if not success:
            raise HTTPException(status_code=404, detail="列映射不存在")
        return {"success": True, "message": "列映射删除成功"}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"删除列映射失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# 时间表达式 CRUD

@app.get("/api/expressions")
async def get_all_expressions():
    """获取所有时间表达式"""
    try:
        expressions = db.get_all_expressions()
        return {"success": True, "data": expressions}
    except Exception as e:
        logger.error(f"获取时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/expressions/{expr_id}")
async def get_expression(expr_id: int):
    """获取单个时间表达式"""
    try:
        expr = db.get_expression_by_id(expr_id)
        if not expr:
            raise HTTPException(status_code=404, detail="时间表达式不存在")
        return {"success": True, "data": expr}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/expressions")
async def create_expression(request: CreateTimeExpressionRequest):
    """创建时间表达式"""
    try:
        expr = db.create_expression(request.dict())
        return {"success": True, "message": "时间表达式创建成功", "data": expr}
    except Exception as e:
        logger.error(f"创建时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.put("/api/expressions/{expr_id}")
async def update_expression(expr_id: int, request: UpdateTimeExpressionRequest):
    """更新时间表达式"""
    try:
        expr = db.update_expression(expr_id, request.dict(exclude_unset=True))
        if not expr:
            raise HTTPException(status_code=404, detail="时间表达式不存在")
        return {"success": True, "message": "时间表达式更新成功", "data": expr}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/expressions/{expr_id}")
async def delete_expression(expr_id: int):
    """删除时间表达式"""
    try:
        success = db.delete_expression(expr_id)
        if not success:
            raise HTTPException(status_code=404, detail="时间表达式不存在")
        return {"success": True, "message": "时间表达式删除成功"}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"删除时间表达式失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# 统计信息

@app.get("/api/stats")
async def get_stats():
    """获取统计信息"""
    try:
        stats = db.get_stats()
        return {"success": True, "data": stats}
    except Exception as e:
        logger.error(f"获取统计信息失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ============ 主函数 ============

def main():
    """启动 MCP 服务器"""
    uvicorn.run(
        "server:app",
        host=HOST,
        port=PORT,
        reload=False,
        log_level=LOG_LEVEL.lower()
    )


if __name__ == "__main__":
    main()
