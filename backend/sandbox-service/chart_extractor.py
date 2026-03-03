"""
图表数据提取器
从 matplotlib figure 中提取原始数据和配置，用于前端交互式渲染
"""
import numpy as np
import pandas as pd


def extract_chart_data(plt):
    """
    从 matplotlib 的所有 figure 中提取图表数据
    返回格式：
    {
        'type': 'bar' | 'line' | 'pie' | 'scatter',
        'data': {
            'columns': ['x', 'y'],
            'rows': [{'x': 1, 'y': 10}, ...]
        },
        'config': {
            'title': '图表标题',
            'xLabel': 'X轴',
            'yLabel': 'Y轴'
        }
    }
    """
    if not plt.get_fignums():
        return None

    # 只处理第一个 figure
    fig = plt.figure(plt.get_fignums()[0])
    axes = fig.get_axes()

    if not axes:
        return None

    # 只处理第一个 axes
    ax = axes[0]

    # 提取标题和标签
    config = {
        'title': ax.get_title() or '数据可视化',
        'xLabel': ax.get_xlabel() or 'X',
        'yLabel': ax.get_ylabel() or 'Y'
    }

    # 尝试识别图表类型并提取数据
    chart_data = None

    # 1. 检测饼图 (Pie Chart) - 优先检测，因为饼图也有 patches
    # 饼图的特征：patches 是 Wedge 类型
    if ax.patches and len(ax.patches) > 0:
        from matplotlib.patches import Wedge
        if isinstance(ax.patches[0], Wedge):
            chart_data = _extract_pie_chart(ax, config)
        else:
            # 2. 柱状图 (Bar Chart)
            chart_data = _extract_bar_chart(ax, config)

    # 3. 检测折线图 (Line Chart)
    elif ax.lines:
        chart_data = _extract_line_chart(ax, config)

    # 4. 检测散点图 (Scatter)
    elif ax.collections:
        chart_data = _extract_scatter_chart(ax, config)

    return chart_data


def _extract_bar_chart(ax, config):
    """提取柱状图数据"""
    try:
        patches = ax.patches
        if not patches:
            return None

        # 提取每个柱子的数据
        data_points = []
        for patch in patches:
            x = patch.get_x() + patch.get_width() / 2  # 柱子中心
            height = patch.get_height()
            data_points.append({'x': x, 'y': height})

        # 尝试获取 x 轴刻度标签
        xticks = ax.get_xticks()
        xticklabels = [label.get_text() for label in ax.get_xticklabels()]

        # 如果有标签，使用标签作为 category
        if xticklabels and any(xticklabels):
            rows = []
            for i, point in enumerate(data_points):
                if i < len(xticklabels):
                    rows.append({
                        'category': xticklabels[i] or str(point['x']),
                        'value': float(point['y'])
                    })
            return {
                'type': 'bar',
                'data': {
                    'columns': ['category', 'value'],
                    'rows': rows
                },
                'config': config
            }
        else:
            # 没有标签，使用数值
            return {
                'type': 'bar',
                'data': {
                    'columns': ['x', 'y'],
                    'rows': [{'x': float(p['x']), 'y': float(p['y'])} for p in data_points]
                },
                'config': config
            }
    except Exception as e:
        print(f"提取柱状图数据失败: {e}")
        return None


def _extract_line_chart(ax, config):
    """提取折线图数据"""
    try:
        lines = ax.lines
        if not lines:
            return None

        # 只处理第一条线
        line = lines[0]
        xdata = line.get_xdata()
        ydata = line.get_ydata()

        rows = []
        for x, y in zip(xdata, ydata):
            rows.append({'x': float(x), 'y': float(y)})

        return {
            'type': 'line',
            'data': {
                'columns': ['x', 'y'],
                'rows': rows
            },
            'config': config
        }
    except Exception as e:
        print(f"提取折线图数据失败: {e}")
        return None


def _extract_pie_chart(ax, config):
    """提取饼图数据"""
    try:
        # 饼图的数据通常在 patches 中
        patches = ax.patches
        if not patches:
            return None

        # 获取标签 - 只取不包含 % 的文本（排除百分比标签）
        all_texts = [label.get_text() for label in ax.texts]
        labels = [text for text in all_texts if text and '%' not in text]

        # 从 wedges 中提取角度计算值
        rows = []
        for i, patch in enumerate(patches):
            # 获取扇形的角度
            theta1 = patch.theta1
            theta2 = patch.theta2
            value = (theta2 - theta1) / 360.0  # 转换为比例

            label = labels[i] if i < len(labels) else f"类别{i+1}"
            rows.append({
                'category': label,
                'value': float(value)
            })

        return {
            'type': 'pie',
            'data': {
                'columns': ['category', 'value'],
                'rows': rows
            },
            'config': config
        }
    except Exception as e:
        print(f"提取饼图数据失败: {e}")
        return None


def _extract_scatter_chart(ax, config):
    """提取散点图数据"""
    try:
        collections = ax.collections
        if not collections:
            return None

        # 获取第一个散点集合
        collection = collections[0]
        offsets = collection.get_offsets()

        rows = []
        for point in offsets:
            rows.append({'x': float(point[0]), 'y': float(point[1])})

        return {
            'type': 'scatter',
            'data': {
                'columns': ['x', 'y'],
                'rows': rows
            },
            'config': config
        }
    except Exception as e:
        print(f"提取散点图数据失败: {e}")
        return None
