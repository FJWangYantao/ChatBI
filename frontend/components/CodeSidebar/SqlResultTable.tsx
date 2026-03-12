"use client";

import { useState, useEffect } from "react";
import { QueryResult } from "@/types/code-sidebar";

interface SqlResultTableProps {
  queryResult: QueryResult;
}

export default function SqlResultTable({ queryResult }: SqlResultTableProps) {
  const [currentPage, setCurrentPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remoteData, setRemoteData] = useState<Record<number, Array<Record<string, any>>>>({});

  const pageSize = 10;
  const totalPages = Math.ceil(queryResult.totalRows / pageSize);
  const isLocalPreview = queryResult.totalRows <= 50;

  // 获取当前页数据
  const getCurrentPageData = () => {
    if (isLocalPreview) {
      // 本地预览模式：直接从 queryResult.rows 切片
      const startIdx = (currentPage - 1) * pageSize;
      const endIdx = startIdx + pageSize;
      return queryResult.rows.slice(startIdx, endIdx);
    } else {
      // 远程分页模式
      if (currentPage <= 5) {
        // 前 50 行使用本地数据
        const startIdx = (currentPage - 1) * pageSize;
        const endIdx = startIdx + pageSize;
        return queryResult.rows.slice(startIdx, endIdx);
      } else {
        // 超出预览范围，使用远程数据
        return remoteData[currentPage] || [];
      }
    }
  };

  // 加载远程数据
  const loadRemotePage = async (page: number) => {
    if (!queryResult.dataRefId || remoteData[page]) {
      return; // 已有缓存或无 dataRefId
    }

    setLoading(true);
    setError(null);

    try {
      const offset = (page - 1) * pageSize;
      const response = await fetch(
        `/api/chat/data/${queryResult.dataRefId}?offset=${offset}&limit=${pageSize}`
      );

      if (!response.ok) {
        if (response.status === 404) {
          throw new Error("数据已过期");
        }
        throw new Error("数据加载失败");
      }

      const data = await response.json();
      setRemoteData(prev => ({ ...prev, [page]: data.rows }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
    }
  };

  // 翻页处理
  const handlePageChange = (newPage: number) => {
    if (newPage < 1 || newPage > totalPages || loading) return;

    setCurrentPage(newPage);

    // 如果需要远程数据且未缓存，则加载
    if (!isLocalPreview && newPage > 5 && !remoteData[newPage]) {
      loadRemotePage(newPage);
    }
  };

  // 当前页数据
  const currentData = getCurrentPageData();

  // 错误状态
  if (queryResult.error) {
    return (
      <div className="glass-card rounded-lg border border-red-500/50 p-3">
        <div className="text-sm text-red-500">查询失败：{queryResult.error}</div>
      </div>
    );
  }

  // 空数据
  if (queryResult.totalRows === 0) {
    return (
      <div className="glass-card rounded-lg border border-border/50 p-3">
        <div className="text-sm text-muted-foreground text-center">无数据</div>
      </div>
    );
  }

  return (
    <div className="glass-card rounded-lg border border-border/50 overflow-hidden">
      {/* 表格容器 */}
      <div className="max-h-[300px] overflow-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-background/80 backdrop-blur-sm border-b border-border/50">
            <tr>
              {queryResult.columns.map((col, idx) => (
                <th
                  key={idx}
                  className="px-3 py-2 text-left text-xs font-semibold text-muted-foreground"
                >
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading && currentData.length === 0 ? (
              <tr>
                <td colSpan={queryResult.columns.length} className="px-3 py-8 text-center">
                  <div className="flex items-center justify-center gap-2">
                    <div className="w-4 h-4 border-2 border-accent border-t-transparent rounded-full animate-spin" />
                    <span className="text-muted-foreground">加载中...</span>
                  </div>
                </td>
              </tr>
            ) : error ? (
              <tr>
                <td colSpan={queryResult.columns.length} className="px-3 py-4 text-center">
                  <div className="text-red-500 text-sm">{error}</div>
                  <button
                    onClick={() => loadRemotePage(currentPage)}
                    className="mt-2 text-xs text-accent hover:underline"
                  >
                    重试
                  </button>
                </td>
              </tr>
            ) : (
              currentData.map((row, rowIdx) => (
                <tr
                  key={rowIdx}
                  className="border-b border-border/30 hover:bg-accent/5 transition-colors"
                >
                  {queryResult.columns.map((col, colIdx) => (
                    <td
                      key={colIdx}
                      className="px-3 py-2 text-xs font-mono max-w-[200px] truncate"
                      title={String(row[col] ?? "")}
                    >
                      {row[col] !== null && row[col] !== undefined
                        ? String(row[col])
                        : <span className="text-muted-foreground italic">null</span>}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 分页控件 */}
      <div className="flex items-center justify-between px-3 py-2 border-t border-border/50 bg-background/50">
        <div className="text-xs text-muted-foreground">
          共 {queryResult.totalRows} 行
          {queryResult.executionTime && (
            <span className="ml-2">· {queryResult.executionTime}ms</span>
          )}
        </div>

        {totalPages > 1 && (
          <div className="flex items-center gap-2">
            <button
              onClick={() => handlePageChange(currentPage - 1)}
              disabled={currentPage === 1 || loading}
              className="text-xs px-2 py-1 rounded hover:bg-accent/10 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              上一页
            </button>

            <span className="text-xs text-muted-foreground">
              <span className="text-accent font-medium">{currentPage}</span> / {totalPages}
            </span>

            <button
              onClick={() => handlePageChange(currentPage + 1)}
              disabled={currentPage === totalPages || loading}
              className="text-xs px-2 py-1 rounded hover:bg-accent/10 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              下一页
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
