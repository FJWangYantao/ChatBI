#!/usr/bin/env python3
"""
快速 SQL 生成测试脚本 - 只测试 SQL 生成，不执行

用法:
    python test_sql_generation.py --input questions.txt --output results.json
"""

import json
import requests
import time
from datetime import datetime
from typing import List, Dict, Any
import argparse


class SQLGenerationTester:
    def __init__(self, api_base_url: str = "http://localhost:8080/api"):
        self.api_base_url = api_base_url
        self.results = []

    def test_single_query(self, query: str, query_id: int) -> Dict[str, Any]:
        """测试单个查询的 SQL 生成"""
        print(f"\n[{query_id}] 测试: {query}")

        start_time = time.time()

        try:
            # 调用 text2sql API（只生成 SQL，不执行）
            headers = {
                "Content-Type": "application/json; charset=UTF-8"
            }
            response = requests.post(
                f"{self.api_base_url}/chat/text2sql",
                json={"message": query},
                headers=headers,
                timeout=60
            )

            elapsed_time = time.time() - start_time

            if response.status_code == 200:
                result_data = response.json()

                # 提取 SQL
                reply = result_data.get("reply", "")
                tags = result_data.get("tags", [])

                # 从 tags 中提取 SQL
                sql = ""
                for tag in tags or []:
                    if tag.get("type") == "sql":
                        sql = tag.get("content", "")
                        break

                # 判断是否成功生成 SQL
                has_sql = bool(sql and sql.strip())

                result = {
                    "query_id": query_id,
                    "query": query,
                    "success": has_sql,
                    "sql": sql,
                    "reply": reply[:200] if reply else "",
                    "response_time": elapsed_time,
                    "timestamp": datetime.now().isoformat()
                }

                if has_sql:
                    print(f"  ✓ 成功 ({elapsed_time:.2f}s)")
                    print(f"  SQL: {sql[:100]}...")
                else:
                    print(f"  ✗ 未生成 SQL ({elapsed_time:.2f}s)")
                    print(f"  回复: {reply[:100]}...")

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

        print(f"\n开始测试 {total} 个查询（仅 SQL 生成）...")
        print("=" * 60)

        for i, query in enumerate(queries, 1):
            result = self.test_single_query(query.strip(), i)
            results.append(result)

            # 添加延迟
            if i < total:
                time.sleep(delay)

        return results

    def analyze_results(self, results: List[Dict[str, Any]]) -> Dict[str, Any]:
        """分析测试结果"""
        total = len(results)
        successful = sum(1 for r in results if r["success"])
        failed = total - successful

        avg_response_time = sum(r["response_time"] for r in results) / total if total > 0 else 0

        # 响应时间分布
        response_times = sorted([r["response_time"] for r in results])
        p50 = response_times[len(response_times)//2] if response_times else 0
        p95 = response_times[int(len(response_times)*0.95)] if response_times else 0
        p99 = response_times[int(len(response_times)*0.99)] if response_times else 0

        analysis = {
            "total_queries": total,
            "successful": successful,
            "failed": failed,
            "success_rate": (successful / total * 100) if total > 0 else 0,
            "avg_response_time": avg_response_time,
            "p50_response_time": p50,
            "p95_response_time": p95,
            "p99_response_time": p99,
            "min_response_time": min(response_times) if response_times else 0,
            "max_response_time": max(response_times) if response_times else 0
        }

        return analysis

    def save_results(self, results: List[Dict[str, Any]], output_file: str):
        """保存测试结果"""
        analysis = self.analyze_results(results)

        output = {
            "test_info": {
                "timestamp": datetime.now().isoformat(),
                "total_queries": len(results),
                "api_base_url": self.api_base_url,
                "test_type": "sql_generation_only"
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
        print("测试摘要（SQL 生成）")
        print("=" * 60)
        print(f"总查询数: {analysis['total_queries']}")
        print(f"成功: {analysis['successful']} ({analysis['success_rate']:.1f}%)")
        print(f"失败: {analysis['failed']}")
        print(f"\n响应时间统计:")
        print(f"  平均: {analysis['avg_response_time']:.2f}s")
        print(f"  最快: {analysis['min_response_time']:.2f}s")
        print(f"  最慢: {analysis['max_response_time']:.2f}s")
        print(f"  P50: {analysis['p50_response_time']:.2f}s")
        print(f"  P95: {analysis['p95_response_time']:.2f}s")
        print(f"  P99: {analysis['p99_response_time']:.2f}s")


def load_queries_from_file(file_path: str) -> List[str]:
    """从文件加载查询"""
    with open(file_path, 'r', encoding='utf-8') as f:
        queries = [line.strip() for line in f if line.strip() and not line.startswith('#')]
    return queries


def main():
    parser = argparse.ArgumentParser(description='SQL 生成测试工具（快速版）')
    parser.add_argument('--input', '-i', required=True, help='输入问题文件路径')
    parser.add_argument('--output', '-o', default='sql_gen_results.json', help='输出结果文件路径')
    parser.add_argument('--api-url', default='http://localhost:8080/api', help='API 基础 URL')
    parser.add_argument('--delay', type=float, default=0.5, help='请求间隔（秒）')

    args = parser.parse_args()

    # 加载查询
    print(f"从 {args.input} 加载查询...")
    queries = load_queries_from_file(args.input)
    print(f"加载了 {len(queries)} 个查询")

    # 创建测试器
    tester = SQLGenerationTester(api_base_url=args.api_url)

    # 执行测试
    results = tester.test_batch(queries, delay=args.delay)

    # 保存结果
    tester.save_results(results, args.output)

    # 打印摘要
    tester.print_summary(results)


if __name__ == "__main__":
    main()
