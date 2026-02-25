/**
 * 图表数据转换工具 - 将 QueryResult 转换为 Recharts 可用的数据格式
 */

import { QueryResult, DatasetAnalysis } from './dataAnalyzer';

/**
 * 柱状图数据转换
 */
export function transformToBarData(
  queryResult: QueryResult,
  analysis: DatasetAnalysis
): Array<{ name: string; value: number }> {
  const { dimensionCol, measureCol } = analysis;

  if (!dimensionCol || !measureCol) {
    return [];
  }

  return queryResult.rows
    .map(row => ({
      name: String(row[dimensionCol] ?? '-'),
      value: Number(row[measureCol]) || 0
    }))
    .filter(item => item.value > 0 || item.name !== '-');
}

/**
 * 折线图数据转换
 */
export function transformToLineData(
  queryResult: QueryResult,
  analysis: DatasetAnalysis
): Array<{ date: string; value: number }> {
  const { dimensionCol, measureCol } = analysis;

  if (!dimensionCol || !measureCol) {
    return [];
  }

  return queryResult.rows
    .map(row => {
      const dateValue = row[dimensionCol];
      let formattedDate = String(dateValue);

      // 尝试格式化日期
      if (dateValue instanceof Date) {
        formattedDate = dateValue.toLocaleDateString('zh-CN');
      } else if (typeof dateValue === 'string') {
        const date = new Date(dateValue);
        if (!isNaN(date.getTime())) {
          formattedDate = date.toLocaleDateString('zh-CN');
        }
      }

      return {
        date: formattedDate,
        value: Number(row[measureCol]) || 0
      };
    })
    .sort((a, b) => a.date.localeCompare(b.date));
}

/**
 * 饼图数据转换
 */
export function transformToPieData(
  queryResult: QueryResult,
  analysis: DatasetAnalysis
): Array<{ name: string; value: number }> {
  const { dimensionCol, measureCol } = analysis;

  if (!dimensionCol || !measureCol) {
    return [];
  }

  return queryResult.rows
    .map(row => ({
      name: String(row[dimensionCol] ?? '-'),
      value: Number(row[measureCol]) || 0
    }))
    .filter(item => item.value > 0);
}

/**
 * 散点图数据转换
 */
export function transformToScatterData(
  queryResult: QueryResult,
  analysis: DatasetAnalysis
): Array<{ x: number; y: number; z?: number }> {
  const columns = analysis.columns;
  const numberCols = columns.filter(c => c.dataType === 'number');

  if (numberCols.length < 2) {
    return [];
  }

  const xCol = numberCols[0].name;
  const yCol = numberCols[1].name;
  const zCol = numberCols[2]?.name;

  return queryResult.rows
    .map(row => {
      const item: { x: number; y: number; z?: number } = {
        x: Number(row[xCol]) || 0,
        y: Number(row[yCol]) || 0
      };

      if (zCol) {
        item.z = Number(row[zCol]) || 0;
      }

      return item;
    })
    .filter(item => !isNaN(item.x) && !isNaN(item.y));
}

/**
 * 生成图表颜色配置
 */
export const chartColors = [
  '#3b82f6', // blue-500
  '#10b981', // emerald-500
  '#f59e0b', // amber-500
  '#ef4444', // red-500
  '#8b5cf6', // violet-500
  '#ec4899', // pink-500
  '#06b6d4', // cyan-500
  '#f97316', // orange-500
];

/**
 * 获取深色模式图表颜色
 */
export function getChartColors(isDark: boolean) {
  return {
    text: isDark ? '#e5e7eb' : '#374151',
    grid: isDark ? '#374151' : '#e5e7eb',
    tooltip: {
      background: isDark ? '#1f2937' : '#ffffff',
      border: isDark ? '#374151' : '#e5e7eb',
      text: isDark ? '#e5e7eb' : '#374151'
    }
  };
}
