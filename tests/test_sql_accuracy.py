#!/usr/bin/env python3
"""
SQL 准确率测试脚本

用法:
    python test_sql_accuracy.py --input questions.txt --output results.json
"""

import json
import requests
import time
from datetime import datetime
from typing import List, Dict, Any
import argparse


class SQLAccuracyTester:
    def __init__(self, api_base_url: str = "http://localhost:8080/api"):
        self.api_base_url = api_base_url
        self.results = []

    def test_single_query(self, query: str, query_id: int) -> Dict[str, Any]:
        """测试单个查询"""
        print(f"\n[{query_id}] 测试问题: {query}")

        start_time = time.time()

        try:
            # 调用 ChatBI API
            headers = {
                "Content-Type": "application/json; charset=UTF-8"
            }
            response = requests.post(
                f"{self.api_base_url}/chat/message",
                json={"message": query},
                headers=headers,
                timeout=90  # 增加到90秒
            )

            elapsed_time = time.time() - start_time

            if response.status_code == 200:
                result_data = response.json()

                # 提取 SQL 和结果
                sql = result_data.get("sql", "")
                reply = result_data.get("reply", "")
                has_error = result_data.get("error") is not None

                result = {
                    "query_id": query_id,
                    "query": query,
                    "success": not has_error and (sql or reply),
                    "sql": sql,
                    "reply": reply[:200] if reply else "",  # 只保存前200字符
                    "response_time": elapsed_time,
                    "error": result_data.get("error"),
                    "timestamp": datetime.now().isoformat()
                }

                print(f"  ✓ 成功 ({elapsed_time:.2f}s)")
                if sql:
                    print(f"  SQL: {sql[:100]}...")

                return result
            else:
                print(f"  ✗ API 错误: {response.status_code}")
                return {
                    "query_id": query_id,
                    "query": query,
                    "success": False,
                    "error": f"HTTP {response.status_code}",
                    "response_time": elapsed_time,
                    "timestamp": datetime.now().isoformat()
                }

        except Exception as e:
            elapsed_time = time.time() - start_time
            print(f"  ✗ 异常: {str(e)}")
            return {
                "query_id": query_id,
                "query": query,
                "success": False,
                "error": str(e),
                "response_time": elapsed_time,
                "timestamp": datetime.now().isoformat()
            }

    def test_batch(self, queries: List[str], delay: float = 1.0) -> List[Dict[str, Any]]:
        """批量测试查询"""
        results = []
        total = len(queries)

        print(f"\n开始测试 {total} 个查询...")
        print("=" * 60)

        for i, query in enumerate(queries, 1):
            result = self.test_single_query(query.strip(), i)
            results.append(result)

            # 添加延迟避免过载
            if i < total:
                time.sleep(delay)

        return results

    def analyze_results(self, results: List[Dict[str, Any]]) -> Dict[str, Any]:
        """分析测试结果"""
        total = len(results)
        successful = sum(1 for r in results if r["success"])
        failed = total - successful

        avg_response_time = sum(r["response_time"] for r in results) / total if total > 0 else 0

        # 按错误类型分类
        error_types = {}
        for r in results:
            if not r["success"] and r.get("error"):
                error_type = r["error"][:50]  # 取前50个字符作为错误类型
                error_types[error_type] = error_types.get(error_type, 0) + 1

        analysis = {
            "total_queries": total,
            "successful": successful,
            "failed": failed,
            "success_rate": (successful / total * 100) if total > 0 else 0,
            "avg_response_time": avg_response_time,
            "error_types": error_types
        }

        return analysis

    def save_results(self, results: List[Dict[str, Any]], output_file: str):
        """保存测试结果"""
        analysis = self.analyze_results(results)

        output = {
            "test_info": {
                "timestamp": datetime.now().isoformat(),
                "total_queries": len(results),
                "api_base_url": self.api_base_url
            },
            "analysis": analysis,
            "results": results
        }

        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(output, f, ensure_ascii=False, indent=2)

        print(f"\n结果已保存到: {output_file}")

    def print_summary(self, results: List[Dict[str, Any]]):
        """打印测试摘要"""
        analysis = self.analyze_results(results)

        print("\n" + "=" * 60)
        print("测试摘要")
        print("=" * 60)
        print(f"总查询数: {analysis['total_queries']}")
        print(f"成功: {analysis['successful']} ({analysis['success_rate']:.1f}%)")
        print(f"失败: {analysis['failed']}")
        print(f"平均响应时间: {analysis['avg_response_time']:.2f}s")

        if analysis['error_types']:
            print(f"\n错误类型分布:")
            for error_type, count in sorted(analysis['error_types'].items(), key=lambda x: x[1], reverse=True):
                print(f"  - {error_type}: {count}")


def load_queries_from_file(file_path: str) -> List[str]:
    """从文件加载查询"""
    with open(file_path, 'r', encoding='utf-8') as f:
        queries = [line.strip() for line in f if line.strip() and not line.startswith('#')]
    return queries


def main():
    parser = argparse.ArgumentParser(description='SQL 准确率测试工具')
    parser.add_argument('--input', '-i', required=True, help='输入问题文件路径')
    parser.add_argument('--output', '-o', default='test_results.json', help='输出结果文件路径')
    parser.add_argument('--api-url', default='http://localhost:8080/api', help='API 基础 URL')
    parser.add_argument('--delay', type=float, default=1.0, help='请求间隔（秒）')

    args = parser.parse_args()

    # 加载查询
    print(f"从 {args.input} 加载查询...")
    queries = load_queries_from_file(args.input)
    print(f"加载了 {len(queries)} 个查询")

    # 创建测试器
    tester = SQLAccuracyTester(api_base_url=args.api_url)

    # 执行测试
    results = tester.test_batch(queries, delay=args.delay)

    # 保存结果
    tester.save_results(results, args.output)

    # 打印摘要
    tester.print_summary(results)


if __name__ == "__main__":
    main()
