"""
简单测试图表数据提取（不使用 multiprocessing）
"""
import matplotlib
matplotlib.use('Agg')  # 使用非交互式后端
import matplotlib.pyplot as plt
from chart_extractor import extract_chart_data

# 测试 1: 柱状图
print("=" * 50)
print("测试 1: 柱状图")
print("=" * 50)
plt.figure()
categories = ['A', 'B', 'C', 'D']
values = [23, 45, 56, 78]
plt.bar(categories, values)
plt.title('销售数据')
plt.xlabel('类别')
plt.ylabel('销售额')

chart_data = extract_chart_data(plt)
if chart_data:
    print(f"图表类型: {chart_data['type']}")
    print(f"数据列: {chart_data['data']['columns']}")
    print(f"数据行数: {len(chart_data['data']['rows'])}")
    print(f"前3行数据: {chart_data['data']['rows'][:3]}")
    print(f"配置: {chart_data['config']}")
else:
    print("未提取到图表数据")

plt.close('all')

# 测试 2: 折线图
print("\n" + "=" * 50)
print("测试 2: 折线图")
print("=" * 50)
plt.figure()
x = [1, 2, 3, 4, 5]
y = [10, 20, 15, 25, 30]
plt.plot(x, y, marker='o')
plt.title('趋势分析')
plt.xlabel('时间')
plt.ylabel('数值')

chart_data = extract_chart_data(plt)
if chart_data:
    print(f"图表类型: {chart_data['type']}")
    print(f"数据列: {chart_data['data']['columns']}")
    print(f"数据行数: {len(chart_data['data']['rows'])}")
    print(f"前3行数据: {chart_data['data']['rows'][:3]}")
    print(f"配置: {chart_data['config']}")
else:
    print("未提取到图表数据")

plt.close('all')

# 测试 3: 饼图
print("\n" + "=" * 50)
print("测试 3: 饼图")
print("=" * 50)
plt.figure()
sizes = [30, 25, 20, 25]
labels = ['产品A', '产品B', '产品C', '产品D']
plt.pie(sizes, labels=labels, autopct='%1.1f%%')
plt.title('市场份额')

chart_data = extract_chart_data(plt)
if chart_data:
    print(f"图表类型: {chart_data['type']}")
    print(f"数据列: {chart_data['data']['columns']}")
    print(f"数据行数: {len(chart_data['data']['rows'])}")
    print(f"数据: {chart_data['data']['rows']}")
    print(f"配置: {chart_data['config']}")
else:
    print("未提取到图表数据")

plt.close('all')

print("\n" + "=" * 50)
print("测试完成！")
print("=" * 50)
