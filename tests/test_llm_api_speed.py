#!/usr/bin/env python3
"""
LLM API 调用速度测试工具

支持 OpenRouter 和 DeepSeek 官方 API 的响应速度测试
独立于 ChatBI 系统，直接测试 API 性能

用法:
    # OpenRouter
    python test_llm_api_speed.py --api openrouter --model deepseek/deepseek-v3.2 --queries 10

    # DeepSeek 官方 API
    python test_llm_api_speed.py --api deepseek --model deepseek-chat --queries 10
"""

import os
import time
import json
import argparse
import statistics
from datetime import datetime
from typing import List, Dict, Any
import requests


class LLMAPISpeedTester:
    def __init__(self, api_key: str, base_url: str = "https://openrouter.ai/api/v1", api_type: str = "openrouter"):
        self.api_key = api_key
        self.base_url = base_url
        self.api_type = api_type
        self.results = []

    def test_single_call(
        self,
        prompt: str,
        model: str,
        temperature: float = 0.1,
        max_tokens: int = 500
    ) -> Dict[str, Any]:
        """测试单次 API 调用"""

        start_time = time.time()

        try:
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }

            # OpenRouter 需要额外的 headers
            if self.api_type == "openrouter":
                headers["HTTP-Referer"] = "http://localhost"
                headers["X-Title"] = "ChatBI Speed Test"

            payload = {
                "model": model,
                "messages": [
                    {"role": "user", "content": prompt}
                ],
                "temperature": temperature,
                "max_tokens": max_tokens
            }

            response = requests.post(
                f"{self.base_url}/chat/completions",
                headers=headers,
                json=payload,
                timeout=60
            )

            elapsed_time = time.time() - start_time

            if response.status_code == 200:
                data = response.json()

                # 提取响应信息
                content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
                usage = data.get("usage", {})

                result = {
                    "success": True,
                    "response_time": elapsed_time,
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0),
                    "total_tokens": usage.get("total_tokens", 0),
                    "content_length": len(content),
                    "timestamp": datetime.now().isoformat()
                }

                print(f"  ✓ 成功 ({elapsed_time:.2f}s, {usage.get('total_tokens', 0)} tokens)")

                return result
            else:
                print(f"  ✗ API 错误: {response.status_code}")
                return {
                    "success": False,
                    "response_time": elapsed_time,
                    "error": f"HTTP {response.status_code}: {response.text[:100]}",
                    "timestamp": datetime.now().isoformat()
                }

        except Exception as e:
            elapsed_time = time.time() - start_time
            print(f"  ✗ 异常: {str(e)}")
            return {
                "success": False,
                "response_time": elapsed_time,
                "error": str(e),
                "timestamp": datetime.now().isoformat()
            }

    def test_batch(
        self,
        prompts: List[str],
        model: str,
        temperature: float = 0.1,
        delay: float = 1.0
    ) -> List[Dict[str, Any]]:
        """批量测试 API 调用"""

        results = []
        total = len(prompts)

        print(f"\n开始测试 {total} 次 API 调用...")
        print(f"模型: {model}")
        print(f"温度: {temperature}")
        print("=" * 60)

        for i, prompt in enumerate(prompts, 1):
            print(f"\n[{i}/{total}] 测试提示: {prompt[:50]}...")

            result = self.test_single_call(prompt, model, temperature)
            results.append(result)

            # 添加延迟
            if i < total:
                time.sleep(delay)

        return results

    def analyze_results(self, results: List[Dict[str, Any]]) -> Dict[str, Any]:
        """分析测试结果"""

        successful = [r for r in results if r["success"]]
        failed = [r for r in results if not r["success"]]

        if not successful:
            return {
                "total_calls": len(results),
                "successful": 0,
                "failed": len(failed),
                "success_rate": 0
            }

        response_times = [r["response_time"] for r in successful]
        total_tokens = [r.get("total_tokens", 0) for r in successful]

        analysis = {
            "total_calls": len(results),
            "successful": len(successful),
            "failed": len(failed),
            "success_rate": (len(successful) / len(results) * 100),

            # 响应时间统计
            "response_time": {
                "mean": statistics.mean(response_times),
                "median": statistics.median(response_times),
                "min": min(response_times),
                "max": max(response_times),
                "stdev": statistics.stdev(response_times) if len(response_times) > 1 else 0,
                "p95": sorted(response_times)[int(len(response_times) * 0.95)] if len(response_times) > 1 else response_times[0],
                "p99": sorted(response_times)[int(len(response_times) * 0.99)] if len(response_times) > 1 else response_times[0]
            },

            # Token 统计
            "tokens": {
                "mean": statistics.mean(total_tokens),
                "total": sum(total_tokens)
            }
        }

        return analysis

    def print_summary(self, results: List[Dict[str, Any]], model: str):
        """打印测试摘要"""

        analysis = self.analyze_results(results)

        print("\n" + "=" * 60)
        print("LLM API 速度测试摘要")
        print("=" * 60)
        print(f"模型: {model}")
        print(f"总调用次数: {analysis['total_calls']}")
        print(f"成功: {analysis['successful']} ({analysis['success_rate']:.1f}%)")
        print(f"失败: {analysis['failed']}")

        if analysis['successful'] > 0:
            rt = analysis['response_time']
            print(f"\n响应时间统计:")
            print(f"  平均: {rt['mean']:.2f}s")
            print(f"  中位数: {rt['median']:.2f}s")
            print(f"  最快: {rt['min']:.2f}s")
            print(f"  最慢: {rt['max']:.2f}s")
            print(f"  标准差: {rt['stdev']:.2f}s")
            print(f"  P95: {rt['p95']:.2f}s")
            print(f"  P99: {rt['p99']:.2f}s")

            tokens = analysis['tokens']
            print(f"\nToken 统计:")
            print(f"  平均: {tokens['mean']:.0f} tokens/请求")
            print(f"  总计: {tokens['total']} tokens")

    def save_results(self, results: List[Dict[str, Any]], model: str, output_file: str):
        """保存测试结果"""

        analysis = self.analyze_results(results)

        output = {
            "test_info": {
                "timestamp": datetime.now().isoformat(),
                "model": model,
                "total_calls": len(results)
            },
            "analysis": analysis,
            "results": results
        }

        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(output, f, ensure_ascii=False, indent=2)

        print(f"\n结果已保存到: {output_file}")


def get_test_prompts(test_type: str = "simple") -> List[str]:
    """获取测试提示"""

    if test_type == "simple":
        # 简单提示，快速响应
        return [
            "Hello, how are you?",
            "What is 2+2?",
            "Tell me a joke.",
            "What is Python?",
            "Explain AI in one sentence."
        ]

    elif test_type == "medium":
        # 中等复杂度提示
        return [
            "Explain the concept of machine learning in 2-3 sentences.",
            "What are the main differences between Python and JavaScript?",
            "How does a database index work?",
            "Describe the MVC architecture pattern.",
            "What is the difference between REST and GraphQL?"
        ]

    elif test_type == "complex":
        # 复杂提示，类似实际使用
        return [
            "Generate a SQL query to find the total shipment volume for all products in April 2024.",
            "Write a Python function to calculate the moving average of a time series.",
            "Explain how to optimize database queries for large datasets.",
            "Design a REST API for a user management system.",
            "Create a regex pattern to validate email addresses."
        ]

    elif test_type == "chatbi":
        # ChatBI 实际场景
        return [
            "2024年4月所有产品的出货量一共多少？",
            "S3在23财年一共出了多少货？",
            "Yoga产品FY23全年的总出货量多少?",
            "FY23 消费NB 出货与FY22年相比如何？",
            "EMEA 在2024年CQ1的出货情况如何"
        ]

    else:
        return ["Test prompt"]


def main():
    parser = argparse.ArgumentParser(description='LLM API 速度测试工具')
    parser.add_argument('--api', '-a', choices=['openrouter', 'deepseek'], default='openrouter',
                       help='API 类型: openrouter 或 deepseek')
    parser.add_argument('--model', '-m', default='deepseek/deepseek-v3.2',
                       help='模型名称 (OpenRouter: deepseek/deepseek-v3.2, DeepSeek: deepseek-chat)')
    parser.add_argument('--queries', '-q', type=int, default=5, help='测试次数')
    parser.add_argument('--test-type', '-t', choices=['simple', 'medium', 'complex', 'chatbi'],
                       default='medium', help='测试类型')
    parser.add_argument('--temperature', type=float, default=0.1, help='温度参数')
    parser.add_argument('--delay', type=float, default=1.0, help='请求间隔（秒）')
    parser.add_argument('--output', '-o', default='llm_speed_results.json', help='输出文件')
    parser.add_argument('--api-key', help='API Key（或使用环境变量）')

    args = parser.parse_args()

    # 根据 API 类型获取配置
    if args.api == 'deepseek':
        api_key = args.api_key or os.getenv('DEEPSEEK_API_KEY')
        base_url = "https://api.deepseek.com/v1"
        env_var_name = "DEEPSEEK_API_KEY"
    else:  # openrouter
        api_key = args.api_key or os.getenv('OPENROUTER_API_KEY')
        base_url = "https://openrouter.ai/api/v1"
        env_var_name = "OPENROUTER_API_KEY"

    if not api_key:
        print(f"错误: 请提供 {args.api.upper()} API Key")
        print(f"方法1: --api-key YOUR_KEY")
        print(f"方法2: 设置环境变量 {env_var_name}")
        return

    # 获取测试提示
    prompts = get_test_prompts(args.test_type)

    # 如果指定了查询次数，重复提示
    if args.queries > len(prompts):
        prompts = prompts * (args.queries // len(prompts) + 1)
    prompts = prompts[:args.queries]

    # 创建测试器
    tester = LLMAPISpeedTester(api_key, base_url, args.api)

    print(f"\n使用 {args.api.upper()} API")
    print(f"Base URL: {base_url}")
    print(f"模型: {args.model}\n")

    # 执行测试
    results = tester.test_batch(prompts, args.model, args.temperature, args.delay)

    # 保存结果
    tester.save_results(results, args.model, args.output)

    # 打印摘要
    tester.print_summary(results, args.model)


if __name__ == "__main__":
    main()
