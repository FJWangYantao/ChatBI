"use client";

import { useState, useEffect, useMemo } from "react";
import { fetchPagedData } from "@/lib/api/chat";

interface PaginatedTableProps {
  columns: string[];
  rows: Record<string, any>[];
  totalRows: number;
  dataRefId?: string;
  executionTime?: number;
  title?: string;
  pageSize?: number;
}

export function PaginatedTable({
  columns,
  rows,
  totalRows,
  dataRefId,
  executionTime,
  title,
  pageSize = 10,
}: PaginatedTableProps) {
  const [currentPage, setCurrentPage] = useState(1);
  const [remoteRows, setRemoteRows] = useState<Record<string, any>[] | null>(null);
  const [loading, setLoading] = useState(false);

  const previewRows = rows?.length || 0;
  const previewPages = Math.ceil(previewRows / pageSize);
  const effectiveTotalRows = totalRows || previewRows;
  const totalPages = Math.ceil(effectiveTotalRows / pageSize);
  const hasDataRef = !!dataRefId;

  useEffect(() => {
    if (!hasDataRef || currentPage <= previewPages) {
      setRemoteRows(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    const offset = (currentPage - 1) * pageSize;
    fetchPagedData(dataRefId!, offset, pageSize)
      .then((res) => {
        if (!cancelled && res.success) {
          setRemoteRows(res.rows);
        }
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [currentPage, hasDataRef, previewPages, dataRefId, pageSize]);

  // 用 useMemo 缓存当前页数据，避免每次渲染都重新 slice
  const currentPageData = useMemo(() => {
    if (hasDataRef && currentPage > previewPages && remoteRows) {
      return remoteRows;
    }
    if (!rows) return [];
    const startIndex = (currentPage - 1) * pageSize;
    const endIndex = startIndex + pageSize;
    return rows.slice(startIndex, endIndex);
  }, [hasDataRef, currentPage, previewPages, remoteRows, rows, pageSize]);

  return (
    <div className="glass-card border border-border/50 rounded-2xl overflow-hidden hover:border-accent/30 transition-border-color duration-200">
      <div className="border-b border-border/50 px-5 py-3 bg-gradient-to-r from-muted/50 to-background">
        <span className="text-sm font-semibold font-display flex items-center gap-2">
          <span className="text-accent">📊</span>
          {title || "查询结果"}
        </span>
        {!!executionTime && (
          <span className="ml-2 text-xs opacity-60 font-mono">
            ({executionTime}ms)
          </span>
        )}
      </div>

      <div className="max-w-[500px] overflow-x-auto scrollbar-thin">
        <table className="min-w-full divide-y divide-border/30">
          <thead className="bg-gradient-to-r from-muted/50 to-background">
            <tr>
              {columns?.map((column, idx) => (
                <th
                  key={idx}
                  className="px-5 py-3 text-left text-xs font-semibold font-display uppercase tracking-wider whitespace-nowrap"
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border/20">
            {loading ? (
              <tr>
                <td
                  colSpan={columns?.length || 1}
                  className="px-5 py-10 text-center text-sm opacity-50"
                >
                  加载中...
                </td>
              </tr>
            ) : (
              <>
                {currentPageData.map((row, idx) => (
                  <tr key={`${currentPage}-${idx}`} className="hover:bg-accent/5 transition-colors duration-150">
                    {columns?.map((column, colIdx) => (
                      <td
                        key={`${currentPage}-${idx}-${colIdx}`}
                        className="px-5 py-3 text-sm whitespace-nowrap font-mono"
                      >
                        {row[column]?.toString() || "-"}
                      </td>
                    ))}
                  </tr>
                ))}
                {currentPageData.length === 0 && (
                  <tr>
                    <td
                      colSpan={columns?.length || 1}
                      className="px-5 py-10 text-center text-sm opacity-50"
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
        <div className="border-t border-border/50 px-5 py-3 flex items-center justify-between bg-gradient-to-r from-muted/30 to-background">
          <div className="text-xs opacity-70 font-mono">
            共 {effectiveTotalRows} 行 · 第 {currentPage} / {totalPages} 页
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
              disabled={currentPage === 1}
              className={`px-4 py-1.5 text-xs font-medium rounded-xl transition-colors duration-200 ${
                currentPage === 1
                  ? "glass-card border border-border/30 opacity-30 cursor-not-allowed"
                  : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
              }`}
            >
              上一页
            </button>
            <button
              onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))}
              disabled={currentPage === totalPages}
              className={`px-4 py-1.5 text-xs font-medium rounded-xl transition-colors duration-200 ${
                currentPage === totalPages
                  ? "glass-card border border-border/30 opacity-30 cursor-not-allowed"
                  : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
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
