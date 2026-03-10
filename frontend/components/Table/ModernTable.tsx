"use client";

import { useState } from "react";

interface ModernTableProps {
  columns: string[];
  rows: any[][];
  pageSize?: number;
  emptyText?: string;
  className?: string;
}

export default function ModernTable({
  columns,
  rows,
  pageSize = 10,
  emptyText = "暂无数据",
  className = "",
}: ModernTableProps) {
  const [currentPage, setCurrentPage] = useState(1);

  // 计算分页
  const totalPages = Math.ceil(rows.length / pageSize);
  const startIndex = (currentPage - 1) * pageSize;
  const endIndex = startIndex + pageSize;
  const currentRows = rows.slice(startIndex, endIndex);

  // 空状态
  if (rows.length === 0) {
    return (
      <div className={`flex items-center justify-center py-16 ${className}`}>
        <p className="text-gray-400 text-sm">{emptyText}</p>
      </div>
    );
  }

  return (
    <div className={`space-y-4 ${className}`}>
      {/* 表格容器 */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse">
          {/* 表头 */}
          <thead>
            <tr className="border-b border-gray-200">
              {columns.map((col, idx) => (
                <th
                  key={idx}
                  className="px-6 py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
                >
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          {/* 表格内容 */}
          <tbody className="divide-y divide-gray-100">
            {currentRows.map((row, rowIdx) => (
              <tr
                key={rowIdx}
                className="hover:bg-gray-50/50 transition-colors duration-150"
              >
                {row.map((cell, cellIdx) => (
                  <td
                    key={cellIdx}
                    className="px-6 py-4 text-sm text-gray-700 whitespace-nowrap"
                  >
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* 分页控制 */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-2 py-3">
          {/* 分页信息 */}
          <div className="text-sm text-gray-500">
            显示 {startIndex + 1}-{Math.min(endIndex, rows.length)} 条，共 {rows.length} 条
          </div>

          {/* 分页按钮 */}
          <div className="flex items-center gap-2">
            <button
              onClick={() => setCurrentPage((prev) => Math.max(1, prev - 1))}
              disabled={currentPage === 1}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-150"
            >
              上一页
            </button>
            <span className="text-sm text-gray-600">
              {currentPage} / {totalPages}
            </span>
            <button
              onClick={() => setCurrentPage((prev) => Math.min(totalPages, prev + 1))}
              disabled={currentPage === totalPages}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-150"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
