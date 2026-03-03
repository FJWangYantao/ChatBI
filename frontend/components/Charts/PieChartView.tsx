"use client";

import { useMemo } from "react";
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from "recharts";
import { QueryResult, DatasetAnalysis } from "@/lib/utils/dataAnalyzer";
import { transformToPieData, getChartColors, chartColors } from "@/lib/utils/chartDataTransformer";
import { useTheme } from "@/contexts/ThemeContext";

interface PieChartViewProps {
  data: QueryResult;
  analysis: DatasetAnalysis;
  config?: {
    title?: string;
    xLabel?: string;
    yLabel?: string;
  };
}

export function PieChartView({ data, analysis, config }: PieChartViewProps) {
  const { theme } = useTheme();
  const isDark = theme === "dark";
  const colors = useMemo(() => getChartColors(isDark), [isDark]);

  const chartData = useMemo(() => transformToPieData(data, analysis), [data, analysis]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500 dark:text-gray-400">
        暂无图表数据
      </div>
    );
  }

  // 计算总数用于百分比
  const total = chartData.reduce((sum, item) => sum + item.value, 0);

  return (
    <div className="w-full">
      {config?.title && (
        <div className="text-center mb-4">
          <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">{config.title}</h3>
        </div>
      )}
      <ResponsiveContainer width="100%" height={300}>
        <PieChart margin={{ top: 20, right: 30, left: 20, bottom: 20 }}>
          <Pie
            data={chartData}
            cx="50%"
            cy="50%"
            labelLine={false}
            label={({ name, percent }) => `${name} ${((percent || 0) * 100).toFixed(1)}%`}
            outerRadius={80}
            dataKey="value"
          >
            {chartData.map((entry, index) => (
              <Cell key={`cell-${index}`} fill={chartColors[index % chartColors.length]} />
            ))}
          </Pie>
          <Tooltip
            contentStyle={{
              backgroundColor: colors.tooltip.background,
              border: `1px solid ${colors.tooltip.border}`,
              borderRadius: '8px',
              color: colors.tooltip.text
            }}
            formatter={(value: number | undefined) => [
              `${(value || 0).toLocaleString()} (${(((value || 0) / total) * 100).toFixed(1)}%)`,
              '数值'
            ]}
            labelStyle={{ color: colors.tooltip.text }}
          />
          <Legend
            wrapperStyle={{ fontSize: '12px', color: colors.text }}
            iconType="circle"
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
