"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { DataSource, DataSourceStats } from "@/types/datasource";
import {
  getAllDataSources,
  getActiveDataSource,
  deleteDataSource,
  activateDataSource,
} from "@/lib/api/datasource";
import DataSourceFormModal from "@/components/DataSource/DataSourceFormModal";
import FileImportModal from "@/components/DataSource/FileImportModal";
import DatabasePreviewModal from "@/components/DataSource/DatabasePreviewModal";

export default function DataSourceManagePage() {
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [activeDataSource, setActiveDataSource] = useState<DataSource | null>(null);
  const [stats, setStats] = useState<DataSourceStats>({ total: 0, active: 0, inactive: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 弹窗状态
  const [modalOpen, setModalOpen] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importDataSource, setImportDataSource] = useState<DataSource | null>(null);
  const [previewModalOpen, setPreviewModalOpen] = useState(false);
  const [previewDataSource, setPreviewDataSource] = useState<DataSource | null>(null);

  // 加载数据源列表
  const loadDataSources = async () => {
    setLoading(true);
    setError(null);
    try {
      const [sources, active] = await Promise.all([
        getAllDataSources(),
        getActiveDataSource(),
      ]);
      setDataSources(sources);
      setActiveDataSource(active);

      // 计算统计
      const total = sources.length;
      const activeCount = sources.filter((s) => s.isActive).length;
      setStats({
        total,
        active: activeCount,
        inactive: total - activeCount,
      });
    } catch (err: any) {
      setError(err.message || "加载数据源失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDataSources();
  }, []);

  // 删除数据源
  const handleDelete = async (id: number, name: string) => {
    if (!confirm(`确定要删除数据源 "${name}" 吗？`)) {
      return;
    }

    try {
      await deleteDataSource(id);
      await loadDataSources();
    } catch (err: any) {
      alert(err.message || "删除失败");
    }
  };

  // 激活数据源
  const handleActivate = async (id: number) => {
    try {
      await activateDataSource(id);
      await loadDataSources();
    } catch (err: any) {
      alert(err.message || "激活失败");
    }
  };

  // 打开添加弹窗
  const handleAddClick = () => {
    setEditId(null);
    setModalOpen(true);
  };

  // 打开编辑弹窗
  const handleEditClick = (id: number) => {
    setEditId(id);
    setModalOpen(true);
  };

  // 打开导入弹窗
  const handleImportClick = (dataSource: DataSource) => {
    setImportDataSource(dataSource);
    setImportModalOpen(true);
  };

  // 打开预览弹窗
  const handlePreviewClick = (dataSource: DataSource) => {
    setPreviewDataSource(dataSource);
    setPreviewModalOpen(true);
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950">
      {/* 顶部导航栏 */}
      <header className="bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link
                href="/"
                className="text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
                </svg>
              </Link>
              <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">
                数据源管理
              </h1>
            </div>
            <button
              onClick={handleAddClick}
              className="inline-flex items-center gap-2 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 transition-colors"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
              添加数据源
            </button>
          </div>
        </div>
      </header>

      {/* 主内容区域 */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* 统计卡片 */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-500 dark:text-gray-400">总数据源</p>
                <p className="text-3xl font-bold text-gray-900 dark:text-gray-100 mt-1">{stats.total}</p>
              </div>
              <div className="w-12 h-12 bg-blue-100 dark:bg-blue-900/30 rounded-lg flex items-center justify-center">
                <svg className="w-6 h-6 text-blue-600 dark:text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4" />
                </svg>
              </div>
            </div>
          </div>

          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-500 dark:text-gray-400">已激活</p>
                <p className="text-3xl font-bold text-green-600 dark:text-green-400 mt-1">{stats.active}</p>
              </div>
              <div className="w-12 h-12 bg-green-100 dark:bg-green-900/30 rounded-lg flex items-center justify-center">
                <svg className="w-6 h-6 text-green-600 dark:text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
            </div>
          </div>

          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-500 dark:text-gray-400">未激活</p>
                <p className="text-3xl font-bold text-gray-600 dark:text-gray-400 mt-1">{stats.inactive}</p>
              </div>
              <div className="w-12 h-12 bg-gray-100 dark:bg-gray-800 rounded-lg flex items-center justify-center">
                <svg className="w-6 h-6 text-gray-600 dark:text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
                </svg>
              </div>
            </div>
          </div>
        </div>

        {/* 错误提示 */}
        {error && (
          <div className="mb-6 p-4 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg">
            <p className="text-sm text-red-800 dark:text-red-200">{error}</p>
          </div>
        )}

        {/* 加载状态 */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900 dark:border-gray-100"></div>
          </div>
        ) : (
          /* 数据源列表 */
          <div className="space-y-4">
            {dataSources.length === 0 ? (
              <div className="text-center py-12">
                <svg className="w-16 h-16 mx-auto text-gray-400 dark:text-gray-600 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4" />
                </svg>
                <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-2">暂无数据源</h3>
                <p className="text-gray-500 dark:text-gray-400 mb-4">添加第一个数据源开始使用</p>
                <button
                  onClick={handleAddClick}
                  className="inline-flex items-center gap-2 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 transition-colors"
                >
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                  </svg>
                  添加数据源
                </button>
              </div>
            ) : (
              dataSources.map((ds) => (
                <div
                  key={ds.id}
                  className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800 hover:shadow-md transition-shadow"
                >
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <div className="flex items-center gap-3 mb-3">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                          {ds.name}
                        </h3>
                        {ds.isActive && (
                          <span className="inline-flex items-center px-2 py-1 text-xs font-medium text-green-700 bg-green-100 dark:bg-green-900/30 dark:text-green-400 rounded-full">
                            当前使用
                          </span>
                        )}
                      </div>
                      <div className="space-y-1 text-sm text-gray-600 dark:text-gray-400">
                        <p>
                          <span className="font-medium">地址:</span> {ds.host}:{ds.port} / {ds.dbName}
                        </p>
                        <p>
                          <span className="font-medium">用户:</span> {ds.username}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {!ds.isActive && (
                        <button
                          onClick={() => ds.id && handleActivate(ds.id)}
                          className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium text-green-700 bg-green-50 hover:bg-green-100 dark:bg-green-900/20 dark:text-green-400 dark:hover:bg-green-900/30 rounded-lg transition-colors"
                        >
                          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                          </svg>
                          激活
                        </button>
                      )}
                      <button
                        onClick={() => handlePreviewClick(ds)}
                        className={`inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                          ds.isActive
                            ? "text-purple-700 bg-purple-50 hover:bg-purple-100 dark:bg-purple-900/20 dark:text-purple-400 dark:hover:bg-purple-900/30"
                            : "text-gray-400 bg-gray-50 dark:bg-gray-800 cursor-not-allowed"
                        }`}
                        disabled={!ds.isActive}
                        title={!ds.isActive ? "请先激活数据源" : "预览数据库"}
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                        </svg>
                        预览
                      </button>
                      <button
                        onClick={() => handleImportClick(ds)}
                        className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 hover:bg-blue-100 dark:bg-blue-900/20 dark:text-blue-400 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                        </svg>
                        导入
                      </button>
                      <button
                        onClick={() => ds.id && handleEditClick(ds.id)}
                        className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium text-gray-700 bg-gray-50 hover:bg-gray-100 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700 rounded-lg transition-colors"
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                        </svg>
                        编辑
                      </button>
                      <button
                        onClick={() => ds.id && handleDelete(ds.id, ds.name)}
                        className="inline-flex items-center gap-1 px-3 py-1.5 text-sm font-medium text-red-700 bg-red-50 hover:bg-red-100 dark:bg-red-900/20 dark:text-red-400 dark:hover:bg-red-900/30 rounded-lg transition-colors"
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                        </svg>
                        删除
                      </button>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </main>

      {/* 表单弹窗 */}
      <DataSourceFormModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onSuccess={loadDataSources}
        editId={editId}
      />

      {/* 文件导入弹窗 */}
      <FileImportModal
        isOpen={importModalOpen}
        onClose={() => setImportModalOpen(false)}
        onSuccess={loadDataSources}
        dataSource={importDataSource}
      />

      {/* 数据库预览弹窗 */}
      <DatabasePreviewModal
        isOpen={previewModalOpen}
        onClose={() => setPreviewModalOpen(false)}
        dataSource={previewDataSource}
      />
    </div>
  );
}
