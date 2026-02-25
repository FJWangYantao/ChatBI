"use client";

import { useState, useEffect } from "react";
import { getDatabaseSchema } from "@/lib/api/schema";
import { SchemaResponse, TableMetadata, ColumnMetadata } from "@/types/schema";
import { DataSource } from "@/types/datasource";

interface DatabasePreviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  dataSource: DataSource | null;
}

export default function DatabasePreviewModal({
  isOpen,
  onClose,
  dataSource,
}: DatabasePreviewModalProps) {
  const [schema, setSchema] = useState<SchemaResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [expandedTables, setExpandedTables] = useState<Set<string>>(new Set());

  // 加载 Schema
  useEffect(() => {
    if (!isOpen || !dataSource) return;

    // 如果数据源未激活，提示用户
    if (!dataSource.isActive) {
      setError(`数据源 "${dataSource.name}" 未激活，请先激活后再预览`);
      setSchema(null);
      return;
    }

    setLoading(true);
    setError(null);

    getDatabaseSchema()
      .then((data) => {
        setSchema(data);
      })
      .catch((err) => {
        setError(err.message || "加载数据库 Schema 失败");
      })
      .finally(() => {
        setLoading(false);
      });
  }, [isOpen, dataSource]);

  // 重置状态
  const handleClose = () => {
    setSchema(null);
    setError(null);
    setSearchQuery("");
    setExpandedTables(new Set());
    onClose();
  };

  // 切换表展开状态
  const toggleTable = (tableName: string) => {
    setExpandedTables((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(tableName)) {
        newSet.delete(tableName);
      } else {
        newSet.add(tableName);
      }
      return newSet;
    });
  };

  // 过滤表
  const filteredTables = schema?.tables.filter((table) =>
    table.tableName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    table.tableComment?.toLowerCase().includes(searchQuery.toLowerCase())
  ) ?? [];

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
      <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-4xl max-h-[80vh] flex flex-col">
        {/* 标题栏 */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-800 shrink-0">
          <div>
            <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
              数据库预览
            </h2>
            {dataSource && (
              <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                {dataSource.name} → {dataSource.dbName}
              </p>
            )}
          </div>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 内容区域 */}
        <div className="flex-1 overflow-hidden flex flex-col p-6">
          {/* 加载状态 */}
          {loading && (
            <div className="flex items-center justify-center flex-1">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900 dark:border-gray-100"></div>
            </div>
          )}

          {/* 错误状态 */}
          {error && !loading && (
            <div className="flex-1 flex items-center justify-center">
              <div className="text-center p-6">
                <svg className="w-12 h-12 mx-auto text-red-500 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <p className="text-gray-700 dark:text-gray-300">{error}</p>
              </div>
            </div>
          )}

          {/* Schema 内容 */}
          {schema && !loading && !error && (
            <div className="flex-1 flex flex-col overflow-hidden">
              {/* 搜索栏 */}
              <div className="mb-4 flex items-center gap-4 shrink-0">
                <div className="flex-1 relative">
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="搜索表名或注释..."
                    className="w-full px-4 py-2 pl-10 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <svg className="w-5 h-5 absolute left-3 top-2.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                  </svg>
                </div>
                <div className="text-sm text-gray-500 dark:text-gray-400">
                  表数: <span className="font-medium">{filteredTables.length}</span>
                </div>
              </div>

              {/* 表列表 */}
              <div className="flex-1 overflow-y-auto space-y-2">
                {filteredTables.length === 0 ? (
                  <div className="text-center py-8">
                    <svg className="w-12 h-12 mx-auto text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                    <p className="text-gray-500 dark:text-gray-400">
                      {searchQuery ? "没有找到匹配的表" : "数据库为空"}
                    </p>
                  </div>
                ) : (
                  filteredTables.map((table) => {
                    const isExpanded = expandedTables.has(table.tableName);
                    return (
                      <div
                        key={table.tableName}
                        className="border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden"
                      >
                        {/* 表标题 */}
                        <button
                          onClick={() => toggleTable(table.tableName)}
                          className="w-full px-4 py-3 flex items-center justify-between bg-gray-50 dark:bg-gray-800 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
                        >
                          <div className="flex items-center gap-3">
                            <svg
                              className={`w-5 h-5 text-gray-500 transition-transform ${isExpanded ? "rotate-90" : ""}`}
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                            </svg>
                            <svg className="w-5 h-5 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
                            </svg>
                            <div className="text-left">
                              <div className="font-medium text-gray-900 dark:text-gray-100">
                                {table.tableName}
                              </div>
                              {table.tableComment && (
                                <div className="text-xs text-gray-500 dark:text-gray-400">
                                  {table.tableComment}
                                </div>
                              )}
                            </div>
                          </div>
                          <div className="text-xs text-gray-500 dark:text-gray-400">
                            {table.columns.length} 列
                            {table.foreignKeys.length > 0 && ` • ${table.foreignKeys.length} 外键`}
                          </div>
                        </button>

                        {/* 表详情 */}
                        {isExpanded && (
                          <div className="p-4 bg-white dark:bg-gray-900 border-t border-gray-200 dark:border-gray-700">
                            {/* 列表 */}
                            <div className="mb-4">
                              <h4 className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">列信息</h4>
                              <div className="overflow-x-auto">
                                <table className="w-full text-sm">
                                  <thead>
                                    <tr className="border-b border-gray-200 dark:border-gray-700">
                                      <th className="text-left py-2 px-3 font-medium text-gray-700 dark:text-gray-300">列名</th>
                                      <th className="text-left py-2 px-3 font-medium text-gray-700 dark:text-gray-300">类型</th>
                                      <th className="text-center py-2 px-3 font-medium text-gray-700 dark:text-gray-300">主键</th>
                                      <th className="text-center py-2 px-3 font-medium text-gray-700 dark:text-gray-300">可空</th>
                                      <th className="text-left py-2 px-3 font-medium text-gray-700 dark:text-gray-300">注释</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {table.columns.map((column: ColumnMetadata, idx: number) => (
                                      <tr key={idx} className="border-b border-gray-100 dark:border-gray-800">
                                        <td className="py-2 px-3 font-mono text-gray-900 dark:text-gray-100">
                                          {column.columnName}
                                        </td>
                                        <td className="py-2 px-3 text-gray-600 dark:text-gray-400">
                                          {column.dataType}
                                          {column.columnSize && `(${column.columnSize})`}
                                        </td>
                                        <td className="py-2 px-3 text-center">
                                          {column.isPrimaryKey && (
                                            <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium text-yellow-700 bg-yellow-100 dark:bg-yellow-900/30 dark:text-yellow-400 rounded">
                                              PK
                                            </span>
                                          )}
                                        </td>
                                        <td className="py-2 px-3 text-center">
                                          {column.nullable ? (
                                            <span className="text-green-600 dark:text-green-400">✓</span>
                                          ) : (
                                            <span className="text-red-600 dark:text-red-400">✗</span>
                                          )}
                                        </td>
                                        <td className="py-2 px-3 text-gray-600 dark:text-gray-400">
                                          {column.columnComment || "-"}
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            </div>

                            {/* 外键关系 */}
                            {table.foreignKeys.length > 0 && (
                              <div>
                                <h4 className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">外键关系</h4>
                                <div className="space-y-2">
                                  {table.foreignKeys.map((fk, idx) => (
                                    <div
                                      key={idx}
                                      className="flex items-center gap-2 text-sm p-2 bg-blue-50 dark:bg-blue-900/20 rounded"
                                    >
                                      <span className="font-mono text-blue-700 dark:text-blue-300">
                                        {fk.columnName}
                                      </span>
                                      <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
                                      </svg>
                                      <span className="font-mono text-blue-700 dark:text-blue-300">
                                        {fk.referencedTableName}.{fk.referencedColumnName}
                                      </span>
                                    </div>
                                  ))}
                                </div>
                              </div>
                            )}

                            {table.foreignKeys.length === 0 && (
                              <div className="text-sm text-gray-500 dark:text-gray-400 italic">
                                无外键关系
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
