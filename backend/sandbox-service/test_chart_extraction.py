"""
测试图表数据提取功能
"""
import multiprocessing
from executor import execute_code
import json

if __name__ == '__main__':
    multiprocessing.freeze_support()

    # 测试柱状图
    print("=" * 50)
    print("测试 1: 柱状图")
    print("=" * 50)
    bar_code = """
import matplotlib.pyplot as plt

categories = ['A', 'B', 'C', 'D']
values = [23, 45, 56, 78]

plt.figure(figsize=(8, 6))
plt.bar(categories, values)
plt.title('销售数据分析')
plt.xlabel('类别')
plt.ylabel('销售额')
"""

    result = execute_code(bar_code, timeout=10)
    print(f"Success: {result['success']}")
    print(f"Stdout: {result['stdout']}")
    if result['chart_data']:
        print(f"Chart Type: {result['chart_data']['type']}")
        print(f"Chart Config: {result['chart_data']['config']}")
        print(f"Data Rows: {len(result['chart_data']['data']['rows'])}")
        print(f"Sample Data: {json.dumps(result['chart_data']['data']['rows'][:3], indent=2, ensure_ascii=False)}")
    else:
        print("No chart data extracted")
    print()

    # 测试折线图
    print("=" * 50)
    print("测试 2: 折线图")
    print("=" * 50)
    line_code = """
import matplotlib.pyplot as plt

x = [1, 2, 3, 4, 5]
y = [10, 25, 20, 35, 30]

plt.figure(figsize=(8, 6))
plt.plot(x, y, marker='o')
plt.title('趋势分析')
plt.xlabel('时间')
plt.ylabel('数值')
"""

    result = execute_code(line_code, timeout=10)
    print(f"Success: {result['success']}")
    if result['chart_data']:
        print(f"Chart Type: {result['chart_data']['type']}")
        print(f"Chart Config: {json.dumps(result['chart_data']['config'], indent=2, ensure_ascii=False)}")
        print(f"Data Rows: {len(result['chart_data']['data']['rows'])}")
        print(f"Sample Data: {json.dumps(result['chart_data']['data']['rows'], indent=2, ensure_ascii=False)}")
    else:
        print("No chart data extracted")
    print()

    # 测试饼图
    print("=" * 50)
    print("测试 3: 饼图")
    print("=" * 50)
    pie_code = """
import matplotlib.pyplot as plt

labels = ['产品A', '产品B', '产品C', '产品D']
sizes = [15, 30, 45, 10]

plt.figure(figsize=(8, 6))
plt.pie(sizes, labels=labels, autopct='%1.1f%%')
plt.title('市场份额')
"""

    result = execute_code(pie_code, timeout=10)
    print(f"Success: {result['success']}")
    if result['chart_data']:
        print(f"Chart Type: {result['chart_data']['type']}")
        print(f"Chart Config: {json.dumps(result['chart_data']['config'], indent=2, ensure_ascii=False)}")
        print(f"Data Rows: {len(result['chart_data']['data']['rows'])}")
        print(f"Sample Data: {json.dumps(result['chart_data']['data']['rows'], indent=2, ensure_ascii=False)}")
    else:
        print("No chart data extracted")
    print()

    # 测试散点图
    print("=" * 50)
    print("测试 4: 散点图")
    print("=" * 50)
    scatter_code = """
import matplotlib.pyplot as plt
import numpy as np

x = np.random.rand(20) * 100
y = np.random.rand(20) * 100

plt.figure(figsize=(8, 6))
plt.scatter(x, y)
plt.title('相关性分析')
plt.xlabel('变量X')
plt.ylabel('变量Y')
"""

    result = execute_code(scatter_code, timeout=10)
    print(f"Success: {result['success']}")
    if result['chart_data']:
        print(f"Chart Type: {result['chart_data']['type']}")
        print(f"Chart Config: {json.dumps(result['chart_data']['config'], indent=2, ensure_ascii=False)}")
        print(f"Data Rows: {len(result['chart_data']['data']['rows'])}")
        print(f"Sample Data (first 3): {json.dumps(result['chart_data']['data']['rows'][:3], indent=2, ensure_ascii=False)}")
    else:
        print("No chart data extracted")

