#!/usr/bin/env python3
"""
SQL 准确率结果分析脚本

用法:
    python analyze_results.py --input test_results.json --compare baseline_results.json
"""

import json
import argparse
from typing import Dict, Any, List
from collections import defaultdict


class ResultsAnalyzer:
    def __init__(self, results_file: str):
        with open(results_file, 'r', encoding='utf-8') as f:
            self.data = json.load(f)
        self.results = self.data.get('results', [])
        self.analysis = self.data.get('analysis', {})

    def print_detailed_report(self):
        """打印详细报告"""
        print("\n" + "=" * 80)
        print("SQL 准确率测试详细报告")
        print("=" * 80)

        # 基本统计
        print(f"\n【基本统计】")
        print(f"测试时间: {self.data['test_info']['timestamp']}")
        print(f"总查询数: {self.analysis['total_queries']}")
        print(f"成功: {self.analysis['successful']} ({self.analysis['success_rate']:.1f}%)")
        print(f"失败: {self.analysis['failed']}")
        print(f"平均响应时间: {self.analysis['avg_response_time']:.2f}s")

        # 按类别分析
        self._analyze_by_category()

        # 失败案例分析
        self._analyze_failures()

        # 性能分析
        self._analyze_performance()

    def _analyze_by_category(self):
        """按查询类别分析"""
        print(f"\n【按类别分析】")

        categories = {
            "基础查询": ["出货量", "shipment", "一共"],
            "时间范围": ["到", "从", "月"],
            "产品系列": ["系列", "平台"],
            "品牌查询": ["Yoga", "Legion", "Ideapad"],
            "地区查询": ["EMEA", "PRC", "NA", "Geo"],
            "ODM查询": ["LCFC", "Compal", "ODM"],
            "对比查询": ["对比", "相比", "比较"],
            "趋势查询": ["趋势", "变化"],
            "占比查询": ["占比", "比例", "mix"]
        }

        category_stats = defaultdict(lambda: {"total": 0, "success": 0})

        for result in self.results:
            query = result['query']
            success = result['success']

            # 分类
            matched_category = "其他"
            for category, keywords in categories.items():
                if any(kw in query for kw in keywords):
                    matched_category = category
                    break

            category_stats[matched_category]["total"] += 1
            if success:
                category_stats[matched_category]["success"] += 1

        # 打印分类统计
        for category in sorted(category_stats.keys()):
            stats = category_stats[category]
            success_rate = (stats["success"] / stats["total"] * 100) if stats["total"] > 0 else 0
            print(f"  {category:12s}: {stats['success']:3d}/{stats['total']:3d} ({success_rate:5.1f}%)")

    def _analyze_failures(self):
        """分析失败案例"""
        print(f"\n【失败案例分析】")

        failed_results = [r for r in self.results if not r['success']]

        if not failed_results:
            print("  没有失败案例")
            return

        print(f"  失败案例数: {len(failed_results)}")
        print(f"\n  失败案例详情:")

        for i, result in enumerate(failed_results[:10], 1):  # 只显示前10个
            print(f"\n  [{i}] {result['query']}")
            print(f"      错误: {result.get('error', 'Unknown')[:100]}")

        if len(failed_results) > 10:
            print(f"\n  ... 还有 {len(failed_results) - 10} 个失败案例")

    def _analyze_performance(self):
        """分析性能"""
        print(f"\n【性能分析】")

        response_times = [r['response_time'] for r in self.results]
        response_times.sort()

        if response_times:
            print(f"  最快: {response_times[0]:.2f}s")
            print(f"  最慢: {response_times[-1]:.2f}s")
            print(f"  中位数: {response_times[len(response_times)//2]:.2f}s")
            print(f"  平均: {sum(response_times)/len(response_times):.2f}s")

            # P95, P99
            p95_idx = int(len(response_times) * 0.95)
            p99_idx = int(len(response_times) * 0.99)
            print(f"  P95: {response_times[p95_idx]:.2f}s")
            print(f"  P99: {response_times[p99_idx]:.2f}s")

    def compare_with_baseline(self, baseline_file: str):
        """与基线结果对比"""
        print(f"\n" + "=" * 80)
        print("与基线对比")
        print("=" * 80)

        with open(baseline_file, 'r', encoding='utf-8') as f:
            baseline_data = json.load(f)

        baseline_analysis = baseline_data.get('analysis', {})

        # 准确率对比
        current_rate = self.analysis['success_rate']
        baseline_rate = baseline_analysis['success_rate']
        rate_diff = current_rate - baseline_rate

        print(f"\n【准确率对比】")
        print(f"  当前: {current_rate:.1f}%")
        print(f"  基线: {baseline_rate:.1f}%")
        print(f"  差异: {rate_diff:+.1f}%")

        if rate_diff > 0:
            print(f"  ✓ 准确率提升了 {rate_diff:.1f}%")
        elif rate_diff < 0:
            print(f"  ✗ 准确率下降了 {abs(rate_diff):.1f}%")
        else:
            print(f"  = 准确率保持不变")

        # 性能对比
        current_time = self.analysis['avg_response_time']
        baseline_time = baseline_analysis['avg_response_time']
        time_diff = current_time - baseline_time

        print(f"\n【性能对比】")
        print(f"  当前: {current_time:.2f}s")
        print(f"  基线: {baseline_time:.2f}s")
        print(f"  差异: {time_diff:+.2f}s")

        if time_diff < 0:
            print(f"  ✓ 响应时间减少了 {abs(time_diff):.2f}s")
        elif time_diff > 0:
            print(f"  ✗ 响应时间增加了 {time_diff:.2f}s")
        else:
            print(f"  = 响应时间保持不变")


def main():
    parser = argparse.ArgumentParser(description='SQL 准确率结果分析工具')
    parser.add_argument('--input', '-i', required=True, help='测试结果文件路径')
    parser.add_argument('--compare', '-c', help='基线结果文件路径（用于对比）')

    args = parser.parse_args()

    # 分析结果
    analyzer = ResultsAnalyzer(args.input)
    analyzer.print_detailed_report()

    # 如果提供了基线，进行对比
    if args.compare:
        analyzer.compare_with_baseline(args.compare)


if __name__ == "__main__":
    main()
