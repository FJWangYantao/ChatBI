"use client";

import { useState, useMemo } from "react";
import { MessageTag } from "@/app/page";
import { QueryResult, DatasetAnalysis, analyzeDataset, ChartType } from "@/lib/utils/dataAnalyzer";
import { PaginatedTable } from "@/components/Table/PaginatedTable";
import { BarChartView } from "./BarChartView";
import { LineChartView } from "./LineChartView";
import { PieChartView } from "./PieChartView";
import { ScatterChartView } from "./ScatterChartView";

interface AutoChartProps {
  tag: MessageTag;
}

export function AutoChart({ tag }: AutoChartProps) {
  // 检查是否有推荐的图表类型（新格式）
  const hasRecommendation = tag.metadata?.source === 'recommendation';
  const recommendation = hasRecommendation ? tag.content.recommendation : null;
  const rawData = hasRecommendation ? tag.content.data : tag.content;

  // 如果推荐的是 table，默认显示表格视图
  const defaultViewType = (hasRecommendation && recommendation?.chartType === 'table') ? 'table' : 'chart';
  const [viewType, setViewType] = useState<'chart' | 'table'>(defaultViewType);

  // 解析 queryResult
  const queryResult = useMemo(() => {
    if (hasRecommendation) {
      // 新格式：从推荐数据中构建 QueryResult
      return {
        columns: rawData.length > 0 ? Object.keys(rawData[0]) : [],
        rows: rawData,
        totalRows: rawData.length,
      } as QueryResult;
    } else {
      // 旧格式
      return rawData as QueryResult;
    }
  }, [rawData, hasRecommendation]);

  // 从 metadata 中获取后端推荐的图表类型
  const recommendedChartType = (tag.metadata?.chartType as string) || null;

  // 检查是否是从 Python sandbox 传来的图表数据（包含 type 字段）
  const isPythonChart = queryResult.type && ['bar', 'line', 'pie', 'scatter'].includes(queryResult.type);

  // 分析数据（作为降级方案）
  const analysis = useMemo(() => {
    // 如果有新的推荐格式，使用推荐的配置
    if (hasRecommendation && recommendation) {
      // 先进行完整分析，然后覆盖推荐的字段
      const baseAnalysis = analyzeDataset(queryResult);

      // 验证推荐的字段是否存在于实际数据中
      const columns = queryResult.columns || [];
      const xFieldExists = recommendation.xField && columns.includes(recommendation.xField);
      const yFieldExists = recommendation.yField && columns.includes(recommendation.yField);

      console.log('[AutoChart] 推荐字段验证:', {
        xField: recommendation.xField,
        yField: recommendation.yField,
        xFieldExists,
        yFieldExists,
        actualColumns: columns,
        sampleRow: queryResult.rows[0]
      });

      // 如果推荐的字段不存在，回退到自动分析的结果
      // 优先使用 baseAnalysis 的智能分析，避免选中 ID 列或类型不匹配的列
      const dimensionCol = xFieldExists
        ? recommendation.xField
        : (baseAnalysis.dimensionCol || '');
      const measureCol = yFieldExists
        ? recommendation.yField
        : (baseAnalysis.measureCol || '');

      console.log('[AutoChart] 最终使用字段:', {
        dimensionCol,
        measureCol,
        baseAnalysisDimension: baseAnalysis.dimensionCol,
        baseAnalysisMeasure: baseAnalysis.measureCol
      });

      return {
        ...baseAnalysis,
        recommendedChart: recommendation.chartType as ChartType,
        dimensionCol,
        measureCol,
      } as DatasetAnalysis;
    }
    // 如果是 Python 图表数据，基于其 data 字段进行完整分析，再覆盖图表类型
    if (isPythonChart) {
      const pythonData = queryResult.data || queryResult;
      // 确保 pythonData 有 columns 和 rows
      const effectiveData: QueryResult = {
        columns: pythonData.columns || (pythonData.rows?.length > 0 ? Object.keys(pythonData.rows[0]) : []),
        rows: pythonData.rows || [],
        totalRows: pythonData.rows?.length || 0,
      };
      const baseAnalysis = analyzeDataset(effectiveData);
      return {
        ...baseAnalysis,
        recommendedChart: queryResult.type as ChartType,
      } as DatasetAnalysis;
    }
    return analyzeDataset(queryResult);
  }, [queryResult, isPythonChart, hasRecommendation, recommendation]);

  // 使用推荐的图表类型
  const chartType = hasRecommendation && recommendation
    ? recommendation.chartType
    : (isPythonChart ? queryResult.type : (recommendedChartType || analysis.recommendedChart));

  // 渲染图表
  const renderChart = () => {
    // 如果是 Python 图表数据，使用其 data 字段
    const chartData = isPythonChart ? (queryResult.data || queryResult) : queryResult;

    switch (chartType) {
      case 'bar':
        return <BarChartView data={chartData} analysis={analysis} config={queryResult.config} />;
      case 'line':
        return <LineChartView data={chartData} analysis={analysis} config={queryResult.config} />;
      case 'pie':
        return <PieChartView data={chartData} analysis={analysis} config={queryResult.config} />;
      case 'scatter':
        return <ScatterChartView data={chartData} analysis={analysis} config={queryResult.config} />;
      default:
        return (
          <div className="flex items-center justify-center h-64 text-gray-500 dark:text-gray-400">
            <div className="text-center">
              <p className="text-sm mb-2">当前数据不适合图表展示</p>
              <p className="text-xs">请切换到表格视图查看</p>
            </div>
          </div>
        );
    }
  };

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 overflow-hidden">
      {/* 视图切换器 */}
      <div className="flex items-center justify-between border-b border-gray-200 dark:border-gray-800 px-4 py-2 bg-gray-50 dark:bg-gray-800/50">
        <span className="text-xs font-semibold text-gray-700 dark:text-gray-300">
          {tag.title || "数据可视化"}
        </span>
        <div className="flex gap-1 bg-gray-100 dark:bg-gray-900 rounded-lg p-1">
          <button
            onClick={() => setViewType('chart')}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              viewType === 'chart'
                ? 'bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 shadow-sm'
                : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-200'
            }`}
            title="图表视图"
          >
            📈 图表
          </button>
          <button
            onClick={() => setViewType('table')}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              viewType === 'table'
                ? 'bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 shadow-sm'
                : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-200'
            }`}
            title="表格视图"
          >
            📊 表格
          </button>
        </div>
      </div>

      {/* 内容区域 */}
      <div className="p-4">
        {viewType === 'chart' ? renderChart() : (() => {
          const chartData = isPythonChart ? (queryResult.data || queryResult) : queryResult;
          return (
            <PaginatedTable
              columns={chartData.columns}
              rows={chartData.rows}
              totalRows={chartData.totalRows}
              dataRefId={chartData.dataRefId}
              executionTime={chartData.executionTime}
              title={tag.title}
            />
          );
        })()}
      </div>
    </div>
  );
}
