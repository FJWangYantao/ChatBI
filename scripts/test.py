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
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading
import time
import pandas as pd

# 禁用代理（避免本地请求被代理拦截）
os.environ['NO_PROXY'] = 'localhost,127.0.0.1'
os.environ['no_proxy'] = 'localhost,127.0.0.1'

# 配置
NER_SERVICE_URL = "http://localhost:8002"
BACKEND_BASE_URL = "http://localhost:8080/api"
TIMEOUT = 180  # 增加到 180 秒（3分钟）
MAX_WORKERS = 10  # 并行处理的最大线程数（从 2 提升到 10 测试并发能力）
REQUEST_DELAY = 0.1  # 请求之间的延迟（秒），降低到 0.1 秒测试并发

# LLM 配置（从环境变量读取）
LLM_PROVIDER = "openai"  # DeepSeek 兼容 OpenAI API
LLM_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")  # 从环境变量读取
LLM_MODEL = "deepseek-chat"  # DeepSeek 模型
LLM_BASE_URL = "https://api.deepseek.com"  # DeepSeek API 地址


class Text2SQLFullTester:
    def __init__(self):
        self.results = []
        self.timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.lock = threading.Lock()  # 用于线程安全地更新结果列表
        self.request_lock = threading.Lock()  # 用于控制请求间隔
        self.last_request_time = 0  # 上次请求时间

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
        # 控制请求间隔
        with self.request_lock:
            current_time = time.time()
            elapsed = current_time - self.last_request_time
            if elapsed < REQUEST_DELAY:
                time.sleep(REQUEST_DELAY - elapsed)
            self.last_request_time = time.time()

        try:
            url = f"{NER_SERVICE_URL}/ner/predict"
            payload = {"text": question}

            response = requests.post(url, json=payload, timeout=TIMEOUT)

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
        """调用完整流程生成 SQL（流式接口，包含 NER + MCP + Text2SQL）"""
        # 控制请求间隔
        with self.request_lock:
            current_time = time.time()
            elapsed = current_time - self.last_request_time
            if elapsed < REQUEST_DELAY:
                time.sleep(REQUEST_DELAY - elapsed)
            self.last_request_time = time.time()

        try:
            start_time = time.time()

            url = f"{BACKEND_BASE_URL}/chat/stream"
            # 指定为查数模式，强制生成 SQL
            payload = {
                "message": question,
                "agentType": "QUERY_MODE"
            }

            # 添加 LLM 配置请求头
            headers = {
                "Content-Type": "application/json",
                "X-LLM-Provider": LLM_PROVIDER,
                "X-LLM-API-Key": LLM_API_KEY,
                "X-LLM-Model": LLM_MODEL,
                "X-LLM-Base-URL": LLM_BASE_URL
            }

            # 调用流式接口
            response = requests.post(
                url,
                json=payload,
                headers=headers,
                timeout=TIMEOUT,
                stream=True
            )

            if response.status_code != 200:
                error_text = response.text[:200] if response.text else ""
                return {
                    "success": False,
                    "error": f"HTTP {response.status_code}: {error_text}",
                    "sql": "",
                    "mode": "stream"
                }

            # 解析 SSE 流
            sql = ""
            intent_type = ""
            current_event = ""
            all_events = []
            done_received = False

            for line in response.iter_lines(decode_unicode=True):
                if not line:
                    continue

                if line.startswith('event:'):
                    current_event = line[6:].strip()
                    if current_event == 'done':
                        done_received = True

                elif line.startswith('data:'):
                    data_str = line[5:].strip()

                    if data_str and data_str != '[DONE]':
                        try:
                            data = json.loads(data_str)
                            all_events.append({'event': current_event, 'data': data})

                            # 提取意图信息
                            if current_event == 'intent':
                                intent_type = data.get('category', '')

                            # 从 tag_end 事件中提取 SQL（完整的 SQL 在这里）
                            elif current_event == 'tag_end':
                                tag_data = data.get('tag', {})
                                tag_type = tag_data.get('type')
                                tag_content = tag_data.get('content')

                                # 处理 sql_editable 类型
                                if tag_type in ['sql', 'sql_editable']:
                                    if isinstance(tag_content, dict):
                                        sql = tag_content.get('sql', '')
                                    elif isinstance(tag_content, str):
                                        sql = tag_content

                            # 收到 done 事件的数据后，退出循环
                            elif current_event == 'done':
                                break

                        except json.JSONDecodeError:
                            pass

                # 如果已经收到 done 事件，退出循环
                if done_received:
                    break

            elapsed = time.time() - start_time

            return {
                "success": True,
                "sql": sql,
                "mode": "stream",
                "intent": intent_type,
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

    def test_single_question(self, question: str, question_index: int = 0) -> Dict[str, Any]:
        """测试单个问题"""
        print(f"\n测试: {question}")
        print("-" * 70)

        # 1. NER 实体识别
        print("1. NER 实体识别...")
        ner_result = self.extract_entities(question)

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
            intent = sql_result.get("intent", "unknown")
            if sql:
                # 截断长 SQL 显示
                display_sql = (sql[:100] + "...") if len(sql) > 100 else sql
                print(f"   [OK] SQL 生成成功 (意图: {intent})")
                print(f"   >> {display_sql}")
            else:
                print(f"   [WARN] 未生成 SQL (意图: {intent})")
                sql = ""
        else:
            print(f"   [ERROR] SQL 生成失败: {sql_result.get('error')}")
            sql = ""

        result = {
            "question_index": question_index,  # 添加原始索引
            "question": question,
            "ner": ner_result,
            "sql": sql_result,
            "entity_count": len(ner_result.get("entities", [])),
            "sql_generated": bool(sql),
            "intent": sql_result.get("intent", "unknown"),
            "status": "[OK] 成功生成 SQL" if sql else "[ERROR] 生成失败或为空"
        }

        with self.lock:
            self.results.append(result)
        return result

    def test_batch(self, questions: List[str], parallel: bool = True, max_workers: int = MAX_WORKERS):
        """批量测试问题

        Args:
            questions: 问题列表
            parallel: 是否并行处理（默认 True）
            max_workers: 最大并行线程数（默认使用全局配置）
        """
        print(f"\n{'='*70}")
        mode_text = f"并行模式 (最多 {max_workers} 个线程)" if parallel else "串行模式"
        print(f"开始批量测试 (完整流程) ({len(questions)} 个问题) - {mode_text}")
        print(f"{'='*70}")

        if parallel:
            # 并行处理
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                # 提交所有任务（传入原始索引）
                future_to_question = {
                    executor.submit(self._test_single_with_index, i, question, len(questions)): (i, question)
                    for i, question in enumerate(questions)
                }

                # 等待任务完成
                for future in as_completed(future_to_question):
                    idx, question = future_to_question[future]
                    try:
                        future.result()
                    except Exception as e:
                        print(f"   [ERROR] 问题 '{question}' 测试异常: {e}")
                        with self.lock:
                            self.results.append({
                                "question_index": idx,
                                "question": question,
                                "status": f"[ERROR] 异常: {str(e)}",
                                "sql_generated": False
                            })
        else:
            # 串行处理
            for i, question in enumerate(questions):
                print(f"\n【{i+1}/{len(questions)}】")
                try:
                    self.test_single_question(question, i)
                except Exception as e:
                    print(f"   [ERROR] 测试异常: {e}")
                    self.results.append({
                        "question_index": i,
                        "question": question,
                        "status": f"[ERROR] 异常: {str(e)}",
                        "sql_generated": False
                    })

    def _test_single_with_index(self, index: int, question: str, total: int):
        """带索引的单个问题测试（用于并行处理）"""
        print(f"\n【{index+1}/{total}】")
        try:
            self.test_single_question(question, index)
        except Exception as e:
            print(f"   [ERROR] 测试异常: {e}")
            with self.lock:
                self.results.append({
                    "question_index": index,
                    "question": question,
                    "status": f"[ERROR] 异常: {str(e)}",
                    "sql_generated": False
                })

    def print_summary(self):
        """打印测试总结"""
        print(f"\n{'='*70}")
        print("测试总结 (完整流程)")
        print(f"{'='*70}")

        total = len(self.results)
        sql_success = sum(1 for r in self.results if r.get("sql_generated", False))
        ner_success = sum(1 for r in self.results if r.get("ner", {}).get("success", False))

        print(f"总问题数: {total}")
        print(f"NER 成功: {ner_success}/{total} ({ner_success/total*100:.1f}%)")
        print(f"SQL 生成成功: {sql_success}/{total} ({sql_success/total*100:.1f}%)")

        # 显示失败的问题
        failed_sql = [r for r in self.results if not r.get("sql_generated", False)]
        if failed_sql:
            print(f"\nSQL 生成失败的问题 ({len(failed_sql)} 个):")
            for i, r in enumerate(failed_sql[:10], 1):
                print(f"  {i}. {r.get('question', 'N/A')}")
                intent = r.get('intent', 'unknown')
                print(f"     意图: {intent}")
                if r.get("sql", {}).get("error"):
                    print(f"     错误: {r['sql']['error']}")
            if len(failed_sql) > 10:
                print(f"  ... 还有 {len(failed_sql)-10} 个问题")

    def generate_report(self) -> str:
        """生成 JSON 和 Excel 格式的测试报告"""
        json_file = f"text2sql_full_test_report_{self.timestamp}.json"
        excel_file = f"text2sql_full_test_report_{self.timestamp}.xlsx"

        # 按原始索引排序结果
        self.results.sort(key=lambda x: x.get("question_index", 0))

        # 生成 JSON 报告
        report = {
            "timestamp": self.timestamp,
            "test_type": "full_flow",
            "total_questions": len(self.results),
            "sql_success_count": sum(1 for r in self.results if r.get("sql_generated", False)),
            "ner_success_count": sum(1 for r in self.results if r.get("ner", {}).get("success", False)),
            "results": self.results
        }

        with open(json_file, 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

        print(f"\nJSON 报告已生成: {json_file}")

        # 生成 Excel 报告
        try:
            excel_data = []
            for i, r in enumerate(self.results, 1):
                # 提取 SQL
                sql_result = r.get("sql", {})
                sql = sql_result.get("sql", "") if isinstance(sql_result, dict) else ""

                excel_data.append({
                    "序号": i,
                    "问题": r.get("question", ""),
                    "状态": "成功" if r.get("sql_generated", False) else "失败",
                    "生成的SQL": sql,
                    "耗时(秒)": round(sql_result.get("elapsed", 0), 2) if isinstance(sql_result, dict) else 0,
                })

            df = pd.DataFrame(excel_data)

            # 创建 Excel writer
            with pd.ExcelWriter(excel_file, engine='openpyxl') as writer:
                # 写入主数据
                df.to_excel(writer, sheet_name='测试结果', index=False)

                # 写入统计摘要
                success_count = report["sql_success_count"]
                total_count = len(self.results)
                avg_time = sum(r.get('sql', {}).get('elapsed', 0) for r in self.results if isinstance(r.get('sql'), dict)) / total_count if total_count > 0 else 0

                summary_data = {
                    "指标": ["总问题数", "成功数", "失败数", "成功率", "平均耗时(秒)"],
                    "数值": [
                        total_count,
                        success_count,
                        total_count - success_count,
                        f"{success_count/total_count*100:.1f}%",
                        f"{avg_time:.2f}"
                    ]
                }
                summary_df = pd.DataFrame(summary_data)
                summary_df.to_excel(writer, sheet_name='统计摘要', index=False)

                # 调整列宽
                worksheet = writer.sheets['测试结果']
                worksheet.column_dimensions['B'].width = 50  # 问题列
                worksheet.column_dimensions['D'].width = 80  # SQL列

            print(f"Excel 报告已生成: {excel_file}")

        except Exception as e:
            print(f"生成 Excel 报告失败: {e}")
            print("请确保已安装 pandas 和 openpyxl: pip install pandas openpyxl")

        return json_file


def main():
    import sys
    import argparse

    # 解析命令行参数
    parser = argparse.ArgumentParser(description='Text2SQL 完整流程准确率测试')
    parser.add_argument('question_file', nargs='?', default='questions.txt',
                        help='问题列表文件路径（每行一个问题）')
    parser.add_argument('--serial', action='store_true',
                        help='使用串行模式（默认为并行模式）')
    parser.add_argument('--workers', type=int, default=MAX_WORKERS,
                        help=f'并行处理的最大线程数（默认: {MAX_WORKERS}）')

    args = parser.parse_args()
    question_file = args.question_file

    # 检查问题文件是否存在
    if not os.path.exists(question_file):
        print(f"[ERROR] 找不到问题文件: {question_file}")
        print(f"\n使用方法: python {sys.argv[0]} [问题列表文件] [--serial] [--workers N]")
        print(f"\n参数说明:")
        print(f"  问题列表文件: 每行一个问题的文本文件（默认: questions.txt）")
        print(f"  --serial: 使用串行模式（默认为并行模式）")
        print(f"  --workers N: 设置并行线程数（默认: {MAX_WORKERS}）")
        print(f"\n问题列表文件格式示例:")
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

    # 运行测试（并行或串行）
    parallel_mode = not args.serial
    tester.test_batch(questions, parallel=parallel_mode, max_workers=args.workers)

    # 打印总结
    tester.print_summary()

    # 生成报告
    report_file = tester.generate_report()

    print(f"\n提示: 您可以在编辑器中打开生成的报告文件进行人工审核")
    print(f"报告文件: {report_file}")


if __name__ == "__main__":
    main()
