"use client";

import { useMemo } from "react";
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from "recharts";
import { QueryResult, DatasetAnalysis } from "@/lib/utils/dataAnalyzer";
import { transformToBarData, getChartColors, chartColors } from "@/lib/utils/chartDataTransformer";
import { useTheme } from "@/contexts/ThemeContext";

interface BarChartViewProps {
  data: QueryResult;
  analysis: DatasetAnalysis;
}

export function BarChartView({ data, analysis }: BarChartViewProps) {
  const { theme } = useTheme();
  const isDark = theme === "dark";
  const colors = useMemo(() => getChartColors(isDark), [isDark]);

  const chartData = useMemo(() => transformToBarData(data, analysis), [data, analysis]);

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
        <BarChart data={chartData} margin={{ top: 20, right: 30, left: 20, bottom: 60 }}>
          <XAxis
            dataKey="name"
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
          <Bar dataKey="value" radius={[4, 4, 0, 0]}>
            {chartData.map((entry, index) => (
              <Cell key={`cell-${index}`} fill={chartColors[index % chartColors.length]} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
