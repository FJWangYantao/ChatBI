"use client";

import { useMemo } from "react";
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, ZAxis } from "recharts";
import { QueryResult, DatasetAnalysis } from "@/lib/utils/dataAnalyzer";
import { transformToScatterData, getChartColors } from "@/lib/utils/chartDataTransformer";
import { useTheme } from "@/contexts/ThemeContext";

interface ScatterChartViewProps {
  data: QueryResult;
  analysis: DatasetAnalysis;
  config?: {
    title?: string;
    xLabel?: string;
    yLabel?: string;
  };
}

export function ScatterChartView({ data, analysis, config }: ScatterChartViewProps) {
  const { theme } = useTheme();
  const isDark = theme === "dark";
  const colors = useMemo(() => getChartColors(isDark), [isDark]);

  const chartData = useMemo(() => transformToScatterData(data, analysis), [data, analysis]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500 dark:text-gray-400">
        暂无图表数据
      </div>
    );
  }

  // 获取坐标轴名称（优先使用 config，否则从 analysis 中获取）
  const numberCols = analysis.columns.filter(c => c.dataType === 'number');
  const xLabel = config?.xLabel || numberCols[0]?.name || 'X';
  const yLabel = config?.yLabel || numberCols[1]?.name || 'Y';
  const hasZAxis = numberCols.length >= 3 && chartData.some(d => d.z !== undefined);

  return (
    <div className="w-full">
      {config?.title && (
        <div className="text-center mb-4">
          <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">{config.title}</h3>
        </div>
      )}
      <ResponsiveContainer width="100%" height={300}>
        <ScatterChart margin={{ top: 20, right: 30, left: 20, bottom: 60 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.grid} />
          <XAxis
            type="number"
            dataKey="x"
            name={xLabel}
            stroke={colors.text}
            fontSize={12}
            label={{ value: xLabel, position: 'insideBottom', offset: -10, fill: colors.text, fontSize: 11 }}
          />
          <YAxis
            type="number"
            dataKey="y"
            name={yLabel}
            stroke={colors.text}
            fontSize={12}
            label={{ value: yLabel, angle: -90, position: 'insideLeft', fill: colors.text, fontSize: 11 }}
          />
          {hasZAxis && (
            <ZAxis
              dataKey="z"
              range={[50, 300]}
              name={numberCols[2]?.name || 'Z'}
            />
          )}
          <Tooltip
            cursor={{ strokeDasharray: '3 3' }}
            contentStyle={{
              backgroundColor: colors.tooltip.background,
              border: `1px solid ${colors.tooltip.border}`,
              borderRadius: '8px',
              color: colors.tooltip.text
            }}
            formatter={(value: number | undefined, name: string | undefined) => [(value || 0).toLocaleString(), name || '']}
            labelStyle={{ color: colors.tooltip.text }}
          />
          <Scatter name="数据点" data={chartData} fill="#3b82f6" />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}
