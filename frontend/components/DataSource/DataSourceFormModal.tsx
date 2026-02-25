"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { DataSource } from "@/types/datasource";
import {
  addDataSource,
  updateDataSource,
  testConnection,
  getDataSource,
} from "@/lib/api/datasource";

interface DataSourceFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  editId?: number | null;
}

export default function DataSourceFormModal({
  isOpen,
  onClose,
  onSuccess,
  editId,
}: DataSourceFormModalProps) {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 表单数据
  const [formData, setFormData] = useState({
    name: "",
    host: "localhost",
    port: 3306,
    dbName: "",
    username: "root",
    password: "",
  });

  // 表单验证错误
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 加载编辑数据
  useEffect(() => {
    if (editId && isOpen) {
      loadDataSource(editId);
    } else if (isOpen) {
      // 重置表单
      setFormData({
        name: "",
        host: "localhost",
        port: 3306,
        dbName: "",
        username: "root",
        password: "",
      });
      setTestResult(null);
      setError(null);
      setErrors({});
    }
  }, [editId, isOpen]);

  const loadDataSource = async (id: number) => {
    setLoading(true);
    try {
      const data = await getDataSource(id);
      setFormData({
        name: data.name,
        host: data.host,
        port: data.port,
        dbName: data.dbName,
        username: data.username,
        password: data.password,
      });
    } catch (err: any) {
      setError(err.message || "加载数据源失败");
    } finally {
      setLoading(false);
    }
  };

  // 验证表单
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!formData.name.trim()) {
      newErrors.name = "请输入数据源名称";
    }
    if (!formData.host.trim()) {
      newErrors.host = "请输入主机地址";
    }
    if (!formData.dbName.trim()) {
      newErrors.dbName = "请输入数据库名";
    }
    if (!formData.username.trim()) {
      newErrors.username = "请输入用户名";
    }
    if (!formData.password.trim()) {
      newErrors.password = "请输入密码";
    }
    if (formData.port < 1 || formData.port > 65535) {
      newErrors.port = "端口必须在 1-65535 之间";
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 测试连接
  const handleTestConnection = async () => {
    if (!validateForm()) {
      return;
    }

    setTesting(true);
    setTestResult(null);

    try {
      const result = await testConnection(formData);
      setTestResult(result);
    } catch (err: any) {
      setTestResult({
        success: false,
        message: err.message || "测试连接失败",
      });
    } finally {
      setTesting(false);
    }
  };

  // 保存数据源
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) {
      return;
    }

    setLoading(true);
    setError(null);

    try {
      if (editId) {
        await updateDataSource(editId, formData);
      } else {
        await addDataSource(formData);
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || "保存失败");
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
      <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-md">
        {/* 标题栏 */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-800">
          <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
            {editId ? "编辑数据源" : "添加数据源"}
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

        {/* 表单内容 */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {/* 错误提示 */}
          {error && (
            <div className="p-3 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg">
              <p className="text-sm text-red-800 dark:text-red-200">{error}</p>
            </div>
          )}

          {/* 数据源名称 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              数据源名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              placeholder="如：销售数据、库存数据"
              className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                errors.name
                  ? "border-red-300 dark:border-red-700"
                  : "border-gray-300 dark:border-gray-700"
              } focus:outline-none focus:ring-2 focus:ring-blue-500`}
            />
            {errors.name && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.name}</p>}
          </div>

          {/* 主机地址 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              主机地址 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={formData.host}
              onChange={(e) => setFormData({ ...formData, host: e.target.value })}
              placeholder="localhost 或 IP 地址"
              className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                errors.host
                  ? "border-red-300 dark:border-red-700"
                  : "border-gray-300 dark:border-gray-700"
              } focus:outline-none focus:ring-2 focus:ring-blue-500`}
            />
            {errors.host && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.host}</p>}
          </div>

          {/* 端口和数据库名 */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                端口 <span className="text-red-500">*</span>
              </label>
              <input
                type="number"
                value={formData.port}
                onChange={(e) => setFormData({ ...formData, port: parseInt(e.target.value) || 3306 })}
                min={1}
                max={65535}
                className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                  errors.port
                    ? "border-red-300 dark:border-red-700"
                    : "border-gray-300 dark:border-gray-700"
                } focus:outline-none focus:ring-2 focus:ring-blue-500`}
              />
              {errors.port && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.port}</p>}
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                数据库名 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.dbName}
                onChange={(e) => setFormData({ ...formData, dbName: e.target.value })}
                placeholder="数据库名称"
                className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                  errors.dbName
                    ? "border-red-300 dark:border-red-700"
                    : "border-gray-300 dark:border-gray-700"
                } focus:outline-none focus:ring-2 focus:ring-blue-500`}
              />
              {errors.dbName && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.dbName}</p>}
            </div>
          </div>

          {/* 用户名和密码 */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                用户名 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.username}
                onChange={(e) => setFormData({ ...formData, username: e.target.value })}
                placeholder="root"
                className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                  errors.username
                    ? "border-red-300 dark:border-red-700"
                    : "border-gray-300 dark:border-gray-700"
                } focus:outline-none focus:ring-2 focus:ring-blue-500`}
              />
              {errors.username && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.username}</p>}
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                密码 <span className="text-red-500">*</span>
              </label>
              <input
                type="password"
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                placeholder="密码"
                className={`w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
                  errors.password
                    ? "border-red-300 dark:border-red-700"
                    : "border-gray-300 dark:border-gray-700"
                } focus:outline-none focus:ring-2 focus:ring-blue-500`}
              />
              {errors.password && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.password}</p>}
            </div>
          </div>

          {/* 测试连接结果 */}
          {testResult && (
            <div
              className={`p-3 rounded-lg border ${
                testResult.success
                  ? "bg-green-50 dark:bg-green-950/30 border-green-200 dark:border-green-900"
                  : "bg-red-50 dark:bg-red-950/30 border-red-200 dark:border-red-900"
              }`}
            >
              <div className="flex items-center gap-2">
                {testResult.success ? (
                  <svg className="w-5 h-5 text-green-600 dark:text-green-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                ) : (
                  <svg className="w-5 h-5 text-red-600 dark:text-red-400" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                  </svg>
                )}
                <p
                  className={`text-sm ${
                    testResult.success
                      ? "text-green-800 dark:text-green-200"
                      : "text-red-800 dark:text-red-200"
                  }`}
                >
                  {testResult.message}
                </p>
              </div>
            </div>
          )}

          {/* 按钮组 */}
          <div className="flex gap-3 pt-4">
            <button
              type="button"
              onClick={handleTestConnection}
              disabled={testing || loading}
              className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {testing ? "测试中..." : "测试连接"}
            </button>
            <button
              type="submit"
              disabled={loading || testing}
              className="flex-1 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading ? "保存中..." : editId ? "更新" : "添加"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
