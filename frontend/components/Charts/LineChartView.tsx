"use client";

import { useMemo } from "react";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { QueryResult, DatasetAnalysis } from "@/lib/utils/dataAnalyzer";
import { transformToLineData, getChartColors } from "@/lib/utils/chartDataTransformer";
import { useTheme } from "@/contexts/ThemeContext";

interface LineChartViewProps {
  data: QueryResult;
  analysis: DatasetAnalysis;
  config?: {
    title?: string;
    xLabel?: string;
    yLabel?: string;
  };
}

export function LineChartView({ data, analysis, config }: LineChartViewProps) {
  const { theme } = useTheme();
  const isDark = theme === "dark";
  const colors = useMemo(() => getChartColors(isDark), [isDark]);

  const chartData = useMemo(() => transformToLineData(data, analysis), [data, analysis]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500 dark:text-gray-400">
        暂无图表数据
      </div>
    );
  }

  return (
    <div className="w-full">
      {config?.title && (
        <div className="text-center mb-4">
          <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">{config.title}</h3>
        </div>
      )}
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={chartData} margin={{ top: 20, right: 30, left: 20, bottom: 60 }}>
          <CartesianGrid strokeDasharray="3 3" stroke={colors.grid} />
          <XAxis
            dataKey="date"
            stroke={colors.text}
            angle={-45}
            textAnchor="end"
            height={80}
            fontSize={12}
            label={config?.xLabel ? { value: config.xLabel, position: 'insideBottom', offset: -10 } : undefined}
          />
          <YAxis
            stroke={colors.text}
            fontSize={12}
            label={config?.yLabel ? { value: config.yLabel, angle: -90, position: 'insideLeft' } : undefined}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: colors.tooltip.background,
              border: `1px solid ${colors.tooltip.border}`,
              borderRadius: '8px',
              color: colors.tooltip.text
            }}
            formatter={(value: number | undefined) => [value?.toLocaleString() || '0', '数值']}
            labelStyle={{ color: colors.tooltip.text }}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="#3b82f6"
            strokeWidth={2}
            dot={{ fill: '#3b82f6', r: 4 }}
            activeDot={{ r: 6 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
