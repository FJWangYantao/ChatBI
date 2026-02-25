"""
测试新的实体验证 API 端点
"""
import requests
import json

BASE_URL = "http://localhost:8004"


def test_validate_entities():
    """测试实体验证"""
    print("\n=== 测试实体验证 ===")
    url = f"{BASE_URL}/tools/validate_entities"
    data = {
        "entities": [
            {"text": "COMPAL", "type": "ORG"},
            {"text": "华东", "type": "LOC"},
            {"text": "华西", "type": "LOC"}  # 无效实体
        ]
    }

    response = requests.post(url, json=data)
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_disambiguate_entity():
    """测试实体消歧"""
    print("\n=== 测试实体消歧 ===")
    url = f"{BASE_URL}/tools/disambiguate_entity"
    data = {
        "entity": "Apple",
        "context": "Apple的销售额",
        "possible_types": ["ORG", "PRODUCT"]
    }

    response = requests.post(url, json=data)
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_expand_product_series():
    """测试产品系列展开"""
    print("\n=== 测试产品系列展开 ===")
    url = f"{BASE_URL}/tools/expand_product_series"
    data = {
        "series_name": "S3"
    }

    response = requests.post(url, json=data)
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_health():
    """测试健康检查"""
    print("\n=== 测试健康检查 ===")
    url = f"{BASE_URL}/health"

    response = requests.get(url)
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


if __name__ == "__main__":
    print("开始测试 MCP 服务器新功能...")

    try:
        test_health()
        test_validate_entities()
        test_disambiguate_entity()
        test_expand_product_series()

        print("\n✅ 所有测试完成")
    except requests.exceptions.ConnectionError:
        print("\n❌ 无法连接到服务器，请确保 MCP 服务器正在运行")
        print("   启动命令: cd backend/mcp-knowledge-server && python server.py")
    except Exception as e:
        print(f"\n❌ 测试失败: {e}")
