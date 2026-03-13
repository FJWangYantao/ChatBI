"use client";

import { useState, useMemo, useEffect } from "react";
import { MessageTag } from "@/app/page";
import { QueryResult, DatasetAnalysis, analyzeDataset, ChartType } from "@/lib/utils/dataAnalyzer";
import { fetchPagedData } from "@/lib/api/chat";
import { BarChartView } from "./BarChartView";
import { LineChartView } from "./LineChartView";
import { PieChartView } from "./PieChartView";
import { ScatterChartView } from "./ScatterChartView";

interface AutoChartProps {
  tag: MessageTag;
}

// 分页表格组件
function PaginatedTable({ tag, queryResult }: { tag: MessageTag; queryResult: QueryResult }) {
  const [currentPage, setCurrentPage] = useState(1);
  const [remoteRows, setRemoteRows] = useState<Record<string, any>[] | null>(null);
  const [loading, setLoading] = useState(false);
  const pageSize = 10;

  const previewRows = queryResult.rows?.length || 0;
  const previewPages = Math.ceil(previewRows / pageSize);
  const totalRows = queryResult.totalRows || previewRows;
  const totalPages = Math.ceil(totalRows / pageSize);
  const hasDataRef = !!queryResult.dataRefId;

  useEffect(() => {
    if (!hasDataRef || currentPage <= previewPages) {
      setRemoteRows(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    const offset = (currentPage - 1) * pageSize;
    fetchPagedData(queryResult.dataRefId!, offset, pageSize)
      .then((res) => {
        if (!cancelled && res.success) {
          setRemoteRows(res.rows);
        }
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [currentPage, hasDataRef, previewPages, queryResult.dataRefId]);

  const getCurrentPageData = () => {
    if (hasDataRef && currentPage > previewPages && remoteRows) {
      return remoteRows;
    }
    if (!queryResult.rows) return [];
    const startIndex = (currentPage - 1) * pageSize;
    const endIndex = startIndex + pageSize;
    return queryResult.rows.slice(startIndex, endIndex);
  };

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 overflow-hidden">
      <div className="border-b border-gray-200 dark:border-gray-800 px-4 py-2">
        <span className="text-xs font-semibold text-gray-700 dark:text-gray-300">
          {tag.title || "查询结果"}
        </span>
        {queryResult.executionTime && (
          <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
            ({queryResult.executionTime}ms)
          </span>
        )}
      </div>

      <div className="max-w-[500px] overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-800">
          <thead className="bg-gray-50 dark:bg-gray-800">
            <tr>
              {queryResult.columns?.map((column: string, idx: number) => (
                <th
                  key={idx}
                  className="px-4 py-2 text-left text-xs font-medium text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap"
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 dark:divide-gray-800 bg-white dark:bg-gray-900">
            {loading ? (
              <tr>
                <td
                  colSpan={queryResult.columns?.length || 1}
                  className="px-4 py-8 text-center text-sm text-gray-500 dark:text-gray-400"
                >
                  加载中...
                </td>
              </tr>
            ) : (
            <>
            {getCurrentPageData().map((row: any, idx: number) => (
              <tr key={idx} className={idx % 2 === 0 ? "bg-white dark:bg-gray-900 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors" : "bg-gray-50 dark:bg-gray-850 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"}>
                {queryResult.columns?.map((column: string, colIdx: number) => (
                  <td
                    key={colIdx}
                    className="px-4 py-2 text-sm text-gray-900 dark:text-gray-100 whitespace-nowrap"
                  >
                    {row[column]?.toString() || "-"}
                  </td>
                ))}
              </tr>
            ))}
            {getCurrentPageData().length === 0 && (
              <tr>
                <td
                  colSpan={queryResult.columns?.length || 1}
                  className="px-4 py-8 text-center text-sm text-gray-500 dark:text-gray-400"
                >
                  暂无数据
                </td>
              </tr>
            )}
            </>
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="border-t border-gray-200 dark:border-gray-800 px-4 py-3 flex items-center justify-between bg-gray-50 dark:bg-gray-800/50">
          <div className="text-xs font-medium text-gray-700 dark:text-gray-400">
            共 {queryResult.totalRows || totalRows} 行 ·
            第 {currentPage} / {totalPages} 页
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
              disabled={currentPage === 1}
              className={`px-3 py-1 text-xs rounded-md border font-medium transition-colors ${
                currentPage === 1
                  ? "border-gray-200 dark:border-gray-800 text-gray-400 dark:text-gray-600 bg-gray-50 dark:bg-gray-900/50"
                  : "border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 hover:bg-white dark:hover:bg-gray-800"
              }`}
            >
              上一页
            </button>
            <button
              onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))}
              disabled={currentPage === totalPages}
              className={`px-3 py-1 text-xs rounded-md border font-medium transition-colors ${
                currentPage === totalPages
                  ? "border-gray-200 dark:border-gray-800 text-gray-400 dark:text-gray-600 bg-gray-50 dark:bg-gray-900/50"
                  : "border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 hover:bg-white dark:hover:bg-gray-800"
              }`}
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
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
      const dimensionCol = xFieldExists
        ? recommendation.xField
        : (baseAnalysis.dimensionCol || columns[0] || '');
      const measureCol = yFieldExists
        ? recommendation.yField
        : (baseAnalysis.measureCol || columns[1] || '');

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
    // 如果是 Python 图表数据，直接使用其配置
    if (isPythonChart) {
      return {
        recommendedChart: queryResult.type as ChartType,
        numericColumns: [],
        categoricalColumns: [],
        totalRows: queryResult.data?.rows?.length || 0,
        columns: [],
        confidence: 1.0
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
        {viewType === 'chart' ? renderChart() : <PaginatedTable tag={tag} queryResult={isPythonChart ? (queryResult.data || queryResult) : queryResult} />}
      </div>
    </div>
  );
}
