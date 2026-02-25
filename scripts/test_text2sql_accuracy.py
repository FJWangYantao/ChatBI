#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Text2SQL 准确率测试框架
用于评估 NER 增强的 Text2SQL 系统的准确率
"""

import requests
import json
from datetime import datetime
from typing import List, Dict, Any
import os

# 配置
NER_SERVICE_URL = "http://localhost:8002"
BACKEND_BASE_URL = "http://localhost:8080/api"
TIMEOUT = 15

class Text2SQLTester:
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
    
    def generate_text2sql(self, question: str, ner_result: Dict = None) -> Dict[str, Any]:
        """调用后端生成 SQL"""
        try:
            response = requests.post(
                f"{BACKEND_BASE_URL}/chat/text2sql",
                json={"message": question},  # 修正：使用 message 字段
                timeout=TIMEOUT
            )
            
            if response.status_code == 200:
                data = response.json()
                return {
                    "success": True,
                    "sql": data.get("reply", ""),
                    "tags": data.get("tags", []),
                    "error": data.get("error")
                }
            else:
                return {
                    "success": False,
                    "error": f"HTTP {response.status_code}",
                    "sql": ""
                }
        except Exception as e:
            return {
                "success": False,
                "error": str(e),
                "sql": ""
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
        
        # 2. Text2SQL 生成
        print("2. 生成 SQL...")
        sql_result = self.generate_text2sql(question, ner_result)
        
        if sql_result["success"]:
            sql = sql_result.get("sql", "").strip()
            if sql:
                # 截断长 SQL 显示
                display_sql = (sql[:100] + "...") if len(sql) > 100 else sql
                print(f"   [OK] SQL 生成成功")
                print(f"   >> {display_sql}")
            else:
                print(f"   [ERROR] 生成的 SQL 为空")
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
            "status": "[OK] 成功生成 SQL" if sql else "[ERROR] 生成失败或为空"
        }
        
        self.results.append(result)
        return result
    
    def test_batch(self, questions: List[str]):
        """批量测试问题"""
        print(f"\n{'='*70}")
        print(f"开始批量测试 ({len(questions)} 个问题)")
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
    
    def generate_report(self, output_file: str = None) -> str:
        """生成测试报告"""
        if not output_file:
            output_file = f"text2sql_test_report_{self.timestamp}.json"
        
        # 统计信息
        total = len(self.results)
        sql_generated = sum(1 for r in self.results if r.get("sql_generated"))
        ner_success = sum(1 for r in self.results if r.get("ner", {}).get("success"))
        
        report = {
            "timestamp": self.timestamp,
            "summary": {
                "total_questions": total,
                "sql_generated_count": sql_generated,
                "sql_generation_rate": f"{(sql_generated/total*100):.1f}%" if total > 0 else "N/A",
                "ner_success_count": ner_success,
                "ner_success_rate": f"{(ner_success/total*100):.1f}%" if total > 0 else "N/A",
                "average_entity_count": f"{sum(r.get('entity_count', 0) for r in self.results)/total:.1f}" if total > 0 else "N/A"
            },
            "details": self.results
        }
        
        # 写入 JSON 文件
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        
        print(f"\n[OK] 报告已保存到: {output_file}")
        return output_file
    
    def print_summary(self):
        """打印测试总结"""
        print(f"\n{'='*70}")
        print("测试总结")
        print(f"{'='*70}")
        
        total = len(self.results)
        sql_generated = sum(1 for r in self.results if r.get("sql_generated"))
        ner_success = sum(1 for r in self.results if r.get("ner", {}).get("success"))
        
        print(f"\n统计数据:")
        print(f"  * 总问题数: {total}")
        print(f"  * SQL 生成成功: {sql_generated}/{total} ({(sql_generated/total*100):.1f}%)")
        print(f"  * NER 识别成功: {ner_success}/{total} ({(ner_success/total*100):.1f}%)")
        
        # 生成失败的问题列表
        failed_sql = [r for r in self.results if not r.get("sql_generated")]
        if failed_sql:
            print(f"\n生成失败的问题 ({len(failed_sql)}):")
            for i, r in enumerate(failed_sql[:10], 1):
                print(f"  {i}. {r['question']}")
                if r.get("sql", {}).get("error"):
                    print(f"     错误: {r['sql']['error']}")
            if len(failed_sql) > 10:
                print(f"  ... 还有 {len(failed_sql)-10} 个问题")
        
        # 成功的问题示例
        success_sql = [r for r in self.results if r.get("sql_generated")]
        if success_sql:
            print(f"\n生成成功的问题示例 ({len(success_sql)}):")
            for i, r in enumerate(success_sql[:5], 1):
                sql = r.get("sql", "")
                display_sql = (sql[:50] + "...") if len(sql) > 50 else sql
                print(f"  {i}. Q: {r['question']}")
                print(f"     >> {display_sql}")


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
    tester = Text2SQLTester()
    
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
