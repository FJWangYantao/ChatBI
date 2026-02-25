"use client";

import { useState, useRef } from "react";
import { importFile } from "@/lib/api/file-import";
import { DataSource } from "@/types/datasource";

interface FileImportModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess?: () => void;
  dataSource: DataSource | null;
}

interface ImportFileItem {
  id: string;
  file: File;
  tableName: string;
  status: 'pending' | 'loading' | 'success' | 'error';
  message?: string;
}

export default function FileImportModal({
  isOpen,
  onClose,
  onSuccess,
  dataSource,
}: FileImportModalProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [fileList, setFileList] = useState<ImportFileItem[]>([]);
  const [skipIfExists, setSkipIfExists] = useState(true);
  const [firstRowAsHeader, setFirstRowAsHeader] = useState(true);
  const [importing, setImporting] = useState(false);

  // 规范化表名
  const normalizeTableName = (fileName: string) => {
    return fileName
      .replace(/\.[^/.]+$/, "") // 去除扩展名
      .toLowerCase()
      .replace(/[^a-z0-9\u4e00-\u9fa5]/g, "_")
      .replace(/^_+|_+$/g, "");
  };

  // 重置表单
  const resetForm = () => {
    setFileList([]);
    setSkipIfExists(true);
    setFirstRowAsHeader(true);
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  // 处理文件添加
  const handleFiles = (files: FileList | null) => {
    if (!files) return;

    const newItems: ImportFileItem[] = Array.from(files)
      .filter(file => file.name.endsWith('.csv') || file.name.endsWith('.xlsx') || file.name.endsWith('.xls'))
      .map(file => ({
        id: Math.random().toString(36).substr(2, 9),
        file,
        tableName: normalizeTableName(file.name),
        status: 'pending'
      }));

    setFileList(prev => [...prev, ...newItems]);
    
    // 重置 input 以便允许重复选择相同文件
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleFiles(e.target.files);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    handleFiles(e.dataTransfer.files);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
  };

  // 修改表名
  const handleTableNameChange = (id: string, newName: string) => {
    setFileList(prev => prev.map(item => 
      item.id === id ? { ...item, tableName: newName } : item
    ));
  };

  // 移除文件
  const handleRemoveFile = (id: string) => {
    setFileList(prev => prev.filter(item => item.id !== id));
  };

  // 执行批量导入
  const handleImport = async () => {
    if (fileList.length === 0 || !dataSource?.id) return;

    // 检查是否有空表名
    if (fileList.some(item => !item.tableName.trim())) {
      alert("请确保所有文件都有对应的表名");
      return;
    }

    setImporting(true);

    // 逐个处理待处理或失败的项目
    const newFileList = [...fileList];
    let hasSuccess = false;

    for (let i = 0; i < newFileList.length; i++) {
      const item = newFileList[i];
      
      // 跳过已成功的
      if (item.status === 'success') continue;

      // 更新状态为加载中
      newFileList[i] = { ...item, status: 'loading', message: undefined };
      setFileList([...newFileList]);

      try {
        const response = await importFile(
          item.file,
          dataSource.id,
          item.tableName,
          skipIfExists,
          firstRowAsHeader
        );

        if (response.success) {
          newFileList[i] = { ...item, status: 'success', message: response.message };
          hasSuccess = true;
        } else {
          newFileList[i] = { ...item, status: 'error', message: response.message };
        }
      } catch (error: any) {
        newFileList[i] = { ...item, status: 'error', message: error.message || "导入失败" };
      }

      // 更新列表状态
      setFileList([...newFileList]);
    }

    setImporting(false);

    // 如果全部成功，延迟关闭
    if (newFileList.every(item => item.status === 'success')) {
      if (onSuccess) {
        setTimeout(() => {
          onSuccess();
          onClose();
          resetForm();
        }, 1500);
      }
    } else if (hasSuccess && onSuccess) {
      // 部分成功也刷新数据源列表
      onSuccess();
    }
  };

  if (!isOpen) return null;
  if (!dataSource) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
      <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-2xl max-h-[90vh] flex flex-col">
        {/* 标题栏 */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-800 shrink-0">
          <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
            批量导入文件
          </h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 内容区域 - 可滚动 */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          {/* 数据源信息 */}
          <div className="p-3 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
            <p className="text-sm text-blue-700 dark:text-blue-300">
              目标数据源: <span className="font-medium">{dataSource.name}</span>
              <span className="ml-2 text-xs">({dataSource.dbName})</span>
            </p>
          </div>

          {/* 文件选择区域 */}
          <div
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            className={`border-2 border-dashed rounded-lg p-6 text-center transition-colors ${
              fileList.length > 0
                ? "border-gray-300 dark:border-gray-700"
                : "border-blue-300 dark:border-blue-700 bg-blue-50 dark:bg-blue-900/10"
            }`}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,.xlsx,.xls"
              multiple
              onChange={handleFileChange}
              className="hidden"
              id="file-upload"
            />
            <label htmlFor="file-upload" className="cursor-pointer block">
              {fileList.length === 0 ? (
                <div className="space-y-2 py-4">
                  <svg className="w-12 h-12 mx-auto text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  <p className="text-sm text-gray-600 dark:text-gray-400">
                    点击或拖拽多个文件到此处上传
                  </p>
                  <p className="text-xs text-gray-500 dark:text-gray-500">
                    支持 CSV, Excel (xlsx, xls) 格式
                  </p>
                </div>
              ) : (
                <div className="text-sm text-blue-600 dark:text-blue-400 hover:underline py-2">
                  + 继续添加文件
                </div>
              )}
            </label>
          </div>

          {/* 文件列表 */}
          {fileList.length > 0 && (
            <div className="space-y-3">
              <div className="flex justify-between items-center">
                <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  待导入文件 ({fileList.length})
                </h3>
                <button 
                  onClick={() => setFileList([])}
                  className="text-xs text-red-500 hover:text-red-600"
                  disabled={importing}
                >
                  清空列表
                </button>
              </div>
              
              <div className="space-y-2">
                {fileList.map((item) => (
                  <div 
                    key={item.id}
                    className={`flex items-center gap-3 p-3 rounded-lg border ${
                      item.status === 'error' ? 'border-red-200 bg-red-50 dark:bg-red-900/10 dark:border-red-900' :
                      item.status === 'success' ? 'border-green-200 bg-green-50 dark:bg-green-900/10 dark:border-green-900' :
                      'border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700'
                    }`}
                  >
                    {/* 状态图标 */}
                    <div className="shrink-0">
                      {item.status === 'pending' && (
                        <div className="w-5 h-5 rounded-full border-2 border-gray-300" />
                      )}
                      {item.status === 'loading' && (
                        <svg className="animate-spin w-5 h-5 text-blue-500" fill="none" viewBox="0 0 24 24">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                        </svg>
                      )}
                      {item.status === 'success' && (
                        <svg className="w-5 h-5 text-green-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                      {item.status === 'error' && (
                        <svg className="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                      )}
                    </div>

                    {/* 文件信息 */}
                    <div className="flex-1 min-w-0 grid grid-cols-2 gap-4">
                      <div className="truncate">
                        <p className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate" title={item.file.name}>
                          {item.file.name}
                        </p>
                        <p className="text-xs text-gray-500">
                          {(item.file.size / 1024).toFixed(1)} KB
                        </p>
                      </div>
                      
                      {/* 表名输入 */}
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-gray-500 shrink-0">表名:</span>
                        <input
                          type="text"
                          value={item.tableName}
                          onChange={(e) => handleTableNameChange(item.id, e.target.value)}
                          disabled={item.status === 'success' || item.status === 'loading'}
                          className="flex-1 min-w-0 px-2 py-1 text-sm border border-gray-300 dark:border-gray-600 rounded bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-1 focus:ring-blue-500"
                        />
                      </div>
                    </div>

                    {/* 错误信息或删除按钮 */}
                    <div className="shrink-0">
                      {item.status === 'error' ? (
                        <span className="text-xs text-red-500" title={item.message}>
                          失败
                        </span>
                      ) : item.status === 'success' ? (
                        <span className="text-xs text-green-500">
                          完成
                        </span>
                      ) : (
                        <button
                          onClick={() => handleRemoveFile(item.id)}
                          disabled={importing}
                          className="text-gray-400 hover:text-red-500 transition-colors"
                        >
                          <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                          </svg>
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 选项 */}
          <div className="space-y-2 pt-2 border-t border-gray-200 dark:border-gray-800">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={skipIfExists}
                onChange={(e) => setSkipIfExists(e.target.checked)}
                className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700 dark:text-gray-300">
                表已存在时跳过（否则会删除重建）
              </span>
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={firstRowAsHeader}
                onChange={(e) => setFirstRowAsHeader(e.target.checked)}
                className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700 dark:text-gray-300">
                第一行作为列标题
              </span>
            </label>
          </div>
        </div>

        {/* 底部按钮栏 */}
        <div className="p-6 border-t border-gray-200 dark:border-gray-800 shrink-0 flex gap-3 bg-gray-50 dark:bg-gray-900/50 rounded-b-xl">
          <button
            onClick={onClose}
            disabled={importing}
            className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 rounded-lg hover:bg-white dark:hover:bg-gray-800 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            关闭
          </button>
          <button
            onClick={handleImport}
            disabled={fileList.length === 0 || importing}
            className="flex-1 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            {importing && (
              <svg className="animate-spin w-4 h-4 text-white" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
            )}
            {importing ? "正在导入..." : `开始导入 (${fileList.length})`}
          </button>
        </div>
      </div>
    </div>
  );
}
