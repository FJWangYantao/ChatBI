/**
 * 数据分析工具 - 用于分析查询结果并推荐合适的图表类型
 */

import { MessageTag } from "@/app/page";

// 列数据类型
export type ColumnDataType = 'number' | 'date' | 'string' | 'boolean';

// 图表类型
export type ChartType = 'bar' | 'line' | 'pie' | 'scatter' | 'table';

// 列分析结果
export interface ColumnAnalysis {
  name: string;
  dataType: ColumnDataType;
  uniqueCount: number;
  nullCount: number;
  sampleValues: any[];
}

// 数据集分析结果
export interface DatasetAnalysis {
  columns: ColumnAnalysis[];
  recommendedChart: ChartType;
  dimensionCol?: string;
  measureCol?: string;
  confidence: number;
}

// QueryResult 类型定义（与后端返回的数据结构一致）
export interface QueryResult {
  columns: string[];
  rows: Record<string, any>[];
  totalRows: number;
  executionTime?: number;
  success?: boolean;
  error?: string;
  dataRefId?: string;
  // Python sandbox 图表数据字段
  type?: string;
  data?: QueryResult;
  config?: any;
}

/**
 * 检测单个值的数据类型
 */
function detectValueType(value: any): ColumnDataType {
  if (value === null || value === undefined) {
    return 'string';
  }

  // 布尔值检测
  if (typeof value === 'boolean') {
    return 'boolean';
  }

  // 数值检测
  if (typeof value === 'number' && !isNaN(value)) {
    return 'number';
  }

  // 字符串形式的数值
  if (typeof value === 'string') {
    // 尝试解析为数字
    const trimmed = value.trim();
    if (!isNaN(Number(trimmed)) && trimmed !== '') {
      return 'number';
    }

    // 布尔字符串
    if (trimmed === 'true' || trimmed === 'false') {
      return 'boolean';
    }

    // 日期检测
    const date = new Date(value);
    if (!isNaN(date.getTime())) {
      return 'date';
    }
  }

  return 'string';
}

/**
 * 分析列的数据类型
 */
export function analyzeColumn(
  columnName: string,
  rows: Record<string, any>[]
): ColumnAnalysis {
  const values = rows.map(row => row[columnName]).filter(v => v !== null && v !== undefined);
  const uniqueValues = new Set(values);

  // 检查是否是ID字段：列名包含 id/ID/_id/Id（驼峰命名），且所有值都是整数
  const isIdColumn = /\bid\b|_id$|Id\b/i.test(columnName);
  const allIntegers = values.every(v => {
    const num = Number(v);
    return !isNaN(num) && Number.isInteger(num);
  });

  // 如果是ID字段，强制视为分类字段（string）
  if (isIdColumn && allIntegers) {
    return {
      name: columnName,
      dataType: 'string',
      uniqueCount: uniqueValues.size,
      nullCount: rows.length - values.length,
      sampleValues: values.slice(0, 5)
    };
  }

  // 统计各类型出现次数
  const typeCount: Record<ColumnDataType, number> = {
    number: 0,
    date: 0,
    string: 0,
    boolean: 0
  };

  values.forEach(v => {
    const type = detectValueType(v);
    typeCount[type]++;
  });

  // 确定列的主数据类型（占比 > 60%）
  const totalValues = values.length || 1;
  let dataType: ColumnDataType = 'string';
  for (const [type, count] of Object.entries(typeCount)) {
    if (count / totalValues > 0.6) {
      dataType = type as ColumnDataType;
      break;
    }
  }

  return {
    name: columnName,
    dataType,
    uniqueCount: uniqueValues.size,
    nullCount: rows.length - values.length,
    sampleValues: values.slice(0, 5)
  };
}

/**
 * 分析整个数据集并推荐图表类型
 */
export function analyzeDataset(queryResult: QueryResult): DatasetAnalysis {
  if (!queryResult.columns || queryResult.columns.length === 0) {
    return {
      columns: [],
      recommendedChart: 'table',
      confidence: 1
    };
  }

  // 分析所有列
  const columns = queryResult.columns.map(col =>
    analyzeColumn(col, queryResult.rows)
  );

  // 按优先级检查适合的图表类型

  // 1. 检查是否有日期列（时间序列 - 折线图）
  const dateColumns = columns.filter(c => c.dataType === 'date');
  const numberColumns = columns.filter(c => c.dataType === 'number');

  if (dateColumns.length > 0 && numberColumns.length > 0) {
    return {
      columns,
      recommendedChart: 'line',
      dimensionCol: dateColumns[0].name,
      measureCol: numberColumns[0].name,
      confidence: 0.9
    };
  }

  // 2. 检查分类数据（柱状图）
  const stringColumns = columns.filter(c => c.dataType === 'string' || c.dataType === 'boolean');

  if (stringColumns.length > 0 && numberColumns.length > 0) {
    // 找出唯一值较少的分类列（适合做维度）
    const dimensionCol = stringColumns.find(c => c.uniqueCount < 20);
    if (dimensionCol) {
      return {
        columns,
        recommendedChart: 'bar',
        dimensionCol: dimensionCol.name,
        measureCol: numberColumns[0].name,
        confidence: 0.85
      };
    }
  }

  // 3. 检查饼图条件（分类列唯一值较少）
  if (stringColumns.length > 0 && numberColumns.length > 0) {
    const pieDimensionCol = stringColumns.find(c => c.uniqueCount >= 2 && c.uniqueCount <= 8);
    if (pieDimensionCol) {
      return {
        columns,
        recommendedChart: 'pie',
        dimensionCol: pieDimensionCol.name,
        measureCol: numberColumns[0].name,
        confidence: 0.8
      };
    }
  }

  // 4. 检查散点图条件（多个数值列）
  if (numberColumns.length >= 2) {
    return {
      columns,
      recommendedChart: 'scatter',
      dimensionCol: numberColumns[0].name,
      measureCol: numberColumns[1].name,
      confidence: 0.75
    };
  }

  // 5. 兜底：使用表格展示
  return {
    columns,
    recommendedChart: 'table',
    confidence: 1
  };
}

/**
 * 判断数据是否适合图表展示
 */
export function isSuitableForChart(queryResult: QueryResult): boolean {
  // 数据行数过多不适合图表
  if (queryResult.rows?.length > 1000) {
    return false;
  }

  // 列数过多不适合图表
  if (queryResult.columns?.length > 5) {
    return false;
  }

  return true;
}
