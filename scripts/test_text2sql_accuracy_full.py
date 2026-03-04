#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Text2SQL 完整流程准确率测试
评测包含 NER、MCP 知识库、Planning Agent 的完整前端流程
"""

import requests
import json
from datetime import datetime
from typing import List, Dict, Any
import os

# 配置
NER_SERVICE_URL = "http://localhost:8002"
BACKEND_BASE_URL = "http://localhost:8080/api"
TIMEOUT = 180  # 增加到 180 秒（3分钟）

class Text2SQLFullTester:
    def __init__(self):
        self.results = []
        self.timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    def load_questions_from_file(self, filepath: str) -> List[str]:
        """从文件中加载问题列表"""
        questions = []
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                questions = [line.strip() for line in f if line.strip()]
            print(f"[OK] 从文件加载了 {len(questions)} 个问题")
            return questions
        except Exception as e:
            print(f"[ERROR] 加载文件失败: {e}")
            return []

    def extract_entities(self, question: str) -> Dict[str, Any]:
        """调用 NER 服务提取实体"""
        try:
            response = requests.post(
                f"{NER_SERVICE_URL}/ner/predict",
                json={"text": question},
                timeout=TIMEOUT
            )

            if response.status_code == 200:
                data = response.json()
                return {
                    "success": True,
                    "entities": data.get("entities", []),
                    "original_text": data.get("original_text", question)
                }
            else:
                return {
                    "success": False,
                    "error": f"HTTP {response.status_code}",
                    "entities": []
                }
        except Exception as e:
            return {
                "success": False,
                "error": str(e),
                "entities": []
            }

    def generate_text2sql_full(self, question: str) -> Dict[str, Any]:
        """调用完整流程生成 SQL（非流式接口，包含 NER + MCP + Text2SQL）"""
        try:
            import time
            start_time = time.time()

            # 调用新的非流式查数模式接口
            response = requests.post(
                f"{BACKEND_BASE_URL}/chat/query-mode",
                json={"message": question},
                timeout=TIMEOUT
            )

            elapsed = time.time() - start_time

            if response.status_code != 200:
                return {
                    "success": False,
                    "error": f"HTTP {response.status_code}",
                    "sql": "",
                    "mode": "query_mode"
                }

            # 解析响应
            data = response.json()
            sql = ""
            tags = data.get("tags")

            if tags:
                # 从 tags 中提取 SQL
                for tag in tags:
                    tag_type = tag.get('type')
                    if tag_type in ['sql', 'sql_editable']:
                        content = tag.get('content')
                        if isinstance(content, dict) and 'sql' in content:
                            sql = content['sql']
                        elif isinstance(content, str):
                            sql = content
                        break

            print(f"      - 后端响应耗时: {elapsed:.2f}秒")

            return {
                "success": True,
                "sql": sql,
                "mode": "query_mode",
                "intent": "DATA_QUERY",
                "elapsed": elapsed,
                "error": None if sql else "未生成 SQL"
            }

        except requests.exceptions.Timeout:
            return {
                "success": False,
                "error": f"请求超时（{TIMEOUT}秒）",
                "sql": "",
                "mode": "timeout"
            }
        except Exception as e:
            return {
                "success": False,
                "error": str(e),
                "sql": "",
                "mode": "error"
            }

    def test_single_question(self, question: str) -> Dict[str, Any]:
        """测试单个问题"""
        print(f"\n测试: {question}")
        print("-" * 70)

        # 1. NER 实体识别
        print("1. NER 实体识别...")
        ner_result = self.extract_entities(question)

        entity_summary = ""
        if ner_result["success"]:
            entities = ner_result["entities"]
            entity_summary = ", ".join([f"'{e['text']}'({e['type']})" for e in entities])
            print(f"   [OK] 识别到 {len(entities)} 个实体: {entity_summary}")
        else:
            print(f"   [ERROR] 实体识别失败: {ner_result.get('error')}")

        # 2. 完整流程生成 SQL
        print("2. 生成 SQL（完整流程）...")
        sql_result = self.generate_text2sql_full(question)

        if sql_result["success"]:
            sql = sql_result.get("sql", "").strip()
            mode = sql_result.get("mode", "unknown")
            if sql:
                # 截断长 SQL 显示
                display_sql = (sql[:100] + "...") if len(sql) > 100 else sql
                print(f"   [OK] SQL 生成成功 (模式: {mode})")
                print(f"   >> {display_sql}")
            else:
                print(f"   [WARN] 未生成 SQL (模式: {mode})")
                sql = ""
        else:
            print(f"   [ERROR] SQL 生成失败: {sql_result.get('error')}")
            sql = ""

        result = {
            "question": question,
            "ner": ner_result,
            "sql": sql_result,
            "entity_count": len(ner_result.get("entities", [])),
            "sql_generated": bool(sql),
            "mode": sql_result.get("mode", "unknown"),
            "status": "[OK] 成功生成 SQL" if sql else "[ERROR] 生成失败或为空"
        }

        self.results.append(result)
        return result

    def test_batch(self, questions: List[str]):
        """批量测试问题"""
        print(f"\n{'='*70}")
        print(f"开始批量测试 (完整流程) ({len(questions)} 个问题)")
        print(f"{'='*70}")

        for i, question in enumerate(questions, 1):
            print(f"\n【{i}/{len(questions)}】")
            try:
                self.test_single_question(question)
            except Exception as e:
                print(f"   [ERROR] 测试异常: {e}")
                self.results.append({
                    "question": question,
                    "status": f"[ERROR] 异常: {str(e)}",
                    "error": str(e)
                })

    def print_summary(self):
        """打印测试总结"""
        print(f"\n{'='*70}")
        print("测试总结 (完整流程)")
        print(f"{'='*70}")

        total = len(self.results)
        sql_success = sum(1 for r in self.results if r.get("sql_generated", False))
        ner_success = sum(1 for r in self.results if r.get("ner", {}).get("success", False))

        # 按模式统计
        mode_stats = {}
        for r in self.results:
            mode = r.get("mode", "unknown")
            mode_stats[mode] = mode_stats.get(mode, 0) + 1

        print(f"\n统计数据:")
        print(f"  * 总问题数: {total}")
        print(f"  * SQL 生成成功: {sql_success}/{total} ({sql_success/total*100:.1f}%)")
        print(f"  * NER 识别成功: {ner_success}/{total} ({ner_success/total*100:.1f}%)")

        print(f"\n路由模式分布:")
        for mode, count in mode_stats.items():
            print(f"  * {mode}: {count}/{total} ({count/total*100:.1f}%)")

        # 显示失败的问题
        failed_sql = [r for r in self.results if not r.get("sql_generated", False)]
        if failed_sql:
            print(f"\n生成失败的问题 ({len(failed_sql)}):")
            for i, r in enumerate(failed_sql[:10], 1):
                print(f"  {i}. {r['question']}")
                print(f"     模式: {r.get('mode', 'unknown')}")
                if r.get("sql", {}).get("error"):
                    print(f"     错误: {r['sql']['error']}")
            if len(failed_sql) > 10:
                print(f"  ... 还有 {len(failed_sql)-10} 个问题")

    def generate_report(self) -> str:
        """生成 JSON 格式的测试报告"""
        report_file = f"text2sql_full_test_report_{self.timestamp}.json"

        report = {
            "timestamp": self.timestamp,
            "test_type": "full_flow",
            "total_questions": len(self.results),
            "sql_success_count": sum(1 for r in self.results if r.get("sql_generated", False)),
            "ner_success_count": sum(1 for r in self.results if r.get("ner", {}).get("success", False)),
            "results": self.results
        }

        with open(report_file, 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

        print(f"\n报告已生成: {report_file}")
        return report_file


def main():
    import sys

    # 检查是否提供了问题文件
    if len(sys.argv) > 1:
        question_file = sys.argv[1]
    else:
        # 默认寻找当前目录的问题文件
        question_file = "questions.txt"
        if not os.path.exists(question_file):
            print(f"[ERROR] 找不到问题文件: {question_file}")
            print(f"使用方法: python {sys.argv[0]} <问题列表文件>")
            print(f"\n问题列表文件格式: 每行一个问题，如:")
            print(f"  查询 2024 年的销售额")
            print(f"  统计每个产品的出货量")
            print(f"  显示销售额最高的前 10 个产品")
            sys.exit(1)

    # 创建测试器
    tester = Text2SQLFullTester()

    # 加载问题列表
    questions = tester.load_questions_from_file(question_file)
    if not questions:
        sys.exit(1)

    # 运行测试
    tester.test_batch(questions)

    # 打印总结
    tester.print_summary()

    # 生成报告
    report_file = tester.generate_report()

    print(f"\n提示: 您可以在编辑器中打开生成的报告文件进行人工审核")
    print(f"报告文件: {report_file}")


if __name__ == "__main__":
    main()
