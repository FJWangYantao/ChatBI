"use client";

import { useMemo } from "react";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { QueryResult, DatasetAnalysis } from "@/lib/utils/dataAnalyzer";
import { transformToLineData, getChartColors } from "@/lib/utils/chartDataTransformer";
import { useTheme } from "@/contexts/ThemeContext";

interface LineChartViewProps {
  data: QueryResult;
  analysis: DatasetAnalysis;
}

export function LineChartView({ data, analysis }: LineChartViewProps) {
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
          />
          <YAxis stroke={colors.text} fontSize={12} />
          <Tooltip
            contentStyle={{
              backgroundColor: colors.tooltip.background,
              border: `1px solid ${colors.tooltip.border}`,
              borderRadius: '8px',
              color: colors.tooltip.text
            }}
            formatter={(value: number) => [value.toLocaleString(), '数值']}
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
