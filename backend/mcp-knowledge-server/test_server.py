"""
MCP 知识库服务器测试脚本
"""
import requests
import json

BASE_URL = "http://localhost:8004"


def test_health():
    """测试健康检查"""
    print("\n=== 测试健康检查 ===")
    response = requests.get(f"{BASE_URL}/health")
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_search_terms():
    """测试搜索术语"""
    print("\n=== 测试搜索术语 ===")
    response = requests.post(
        f"{BASE_URL}/tools/search_terms",
        json={"keyword": "出货", "category": None}
    )
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_get_column_mapping():
    """测试获取列映射"""
    print("\n=== 测试获取列映射 ===")
    response = requests.post(
        f"{BASE_URL}/tools/get_column_mapping",
        json={"term": "出货额"}
    )
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_parse_time_expression():
    """测试解析时间表达式"""
    print("\n=== 测试解析时间表达式 ===")

    test_cases = ["FY23", "Q1", "2024年Q2", "FY2024"]

    for expression in test_cases:
        print(f"\n解析: {expression}")
        response = requests.post(
            f"{BASE_URL}/tools/parse_time_expression",
            json={"expression": expression, "reference_date": "2024-01-01"}
        )
        print(f"状态码: {response.status_code}")
        print(f"响应: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_enrich_query_context():
    """测试增强查询上下文"""
    print("\n=== 测试增强查询上下文 ===")

    test_queries = [
        "FY23 的出货额前10是多少？",
        "2024年Q1的销售额同比增长多少？",
        "查询产品名称和出货额"
    ]

    for query in test_queries:
        print(f"\n查询: {query}")
        response = requests.post(
            f"{BASE_URL}/tools/enrich_query_context",
            json={"query": query}
        )
        print(f"状态码: {response.status_code}")
        result = response.json()

        if result.get("success"):
            enriched_prompt = result["result"]["enriched_prompt"]
            print(f"\n增强后的 Prompt:\n{enriched_prompt}")
        else:
            print(f"响应: {json.dumps(result, indent=2, ensure_ascii=False)}")


def main():
    """运行所有测试"""
    print("=" * 60)
    print("MCP 知识库服务器测试")
    print("=" * 60)

    try:
        test_health()
        test_search_terms()
        test_get_column_mapping()
        test_parse_time_expression()
        test_enrich_query_context()

        print("\n" + "=" * 60)
        print("所有测试完成！")
        print("=" * 60)

    except requests.exceptions.ConnectionError:
        print("\n错误：无法连接到 MCP 服务器")
        print("请确保服务器已启动：python server.py")
    except Exception as e:
        print(f"\n测试失败: {e}")


if __name__ == "__main__":
    main()
