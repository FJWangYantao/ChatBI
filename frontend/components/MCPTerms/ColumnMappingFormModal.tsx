"use client";

import { useState, useEffect } from "react";
import { ColumnMapping, BusinessTerm } from "@/types/mcp-terms";
import { createMapping, updateMapping } from "@/lib/api/mcpTerms";

interface ColumnMappingFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  editData?: ColumnMapping | null;
  terms: BusinessTerm[];
}

export default function ColumnMappingFormModal({ isOpen, onClose, onSuccess, editData, terms }: ColumnMappingFormModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formData, setFormData] = useState({
    term_id: 0,
    table_name: "",
    column_name: "",
    data_type: "VARCHAR",
    description: "",
    sample_values: [""],
  });

  const dataTypes = ["VARCHAR", "INT", "BIGINT", "DECIMAL", "FLOAT", "DOUBLE", "DATE", "DATETIME", "TIMESTAMP", "BOOLEAN", "TEXT"];

  useEffect(() => {
    if (editData && isOpen) {
      setFormData({
        term_id: editData.term_id,
        table_name: editData.table_name,
        column_name: editData.column_name,
        data_type: editData.data_type,
        description: editData.description || "",
        sample_values: editData.sample_values?.length > 0 ? editData.sample_values : [""],
      });
    } else if (isOpen) {
      setFormData({ term_id: terms[0]?.id || 0, table_name: "", column_name: "", data_type: "VARCHAR", description: "", sample_values: [""] });
      setError(null);
      setErrors({});
    }
  }, [editData, isOpen, terms]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!formData.term_id) newErrors.term_id = "请选择关联术语";
    if (!formData.table_name.trim()) newErrors.table_name = "请输入表名";
    if (!formData.column_name.trim()) newErrors.column_name = "请输入列名";
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm()) return;
    setLoading(true);
    setError(null);
    try {
      const payload = {
        term_id: formData.term_id,
        table_name: formData.table_name,
        column_name: formData.column_name,
        data_type: formData.data_type,
        description: formData.description,
        sample_values: formData.sample_values.filter((v) => v.trim()),
      };
      if (editData?.id) {
        await updateMapping(editData.id, payload);
      } else {
        await createMapping(payload);
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || "保存失败");
    } finally {
      setLoading(false);
    }
  };

  const addSampleValue = () => setFormData({ ...formData, sample_values: [...formData.sample_values, ""] });
  const removeSampleValue = (i: number) => {
    const arr = [...formData.sample_values];
    arr.splice(i, 1);
    if (arr.length === 0) arr.push("");
    setFormData({ ...formData, sample_values: arr });
  };
  const updateSampleValue = (i: number, v: string) => {
    const arr = [...formData.sample_values];
    arr[i] = v;
    setFormData({ ...formData, sample_values: arr });
  };

  if (!isOpen) return null;

  const inputCls = (field: string) =>
    `w-full px-3 py-2 border rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 ${
      errors[field] ? "border-red-300 dark:border-red-700" : "border-gray-300 dark:border-gray-700"
    } focus:outline-none focus:ring-2 focus:ring-blue-500`;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50">
      <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-md max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-800">
          <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100">
            {editData ? "编辑列映射" : "添加列映射"}
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300">
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="p-3 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg">
              <p className="text-sm text-red-800 dark:text-red-200">{error}</p>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              关联术语 <span className="text-red-500">*</span>
            </label>
            <select value={formData.term_id} onChange={(e) => setFormData({ ...formData, term_id: Number(e.target.value) })} className={inputCls("term_id")}>
              <option value={0}>请选择术语</option>
              {terms.map((t) => (<option key={t.id} value={t.id}>{t.term}</option>))}
            </select>
            {errors.term_id && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.term_id}</p>}
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                表名 <span className="text-red-500">*</span>
              </label>
              <input type="text" value={formData.table_name} onChange={(e) => setFormData({ ...formData, table_name: e.target.value })} placeholder="表名" className={inputCls("table_name")} />
              {errors.table_name && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.table_name}</p>}
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                列名 <span className="text-red-500">*</span>
              </label>
              <input type="text" value={formData.column_name} onChange={(e) => setFormData({ ...formData, column_name: e.target.value })} placeholder="列名" className={inputCls("column_name")} />
              {errors.column_name && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.column_name}</p>}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">数据类型</label>
            <select value={formData.data_type} onChange={(e) => setFormData({ ...formData, data_type: e.target.value })} className={inputCls("data_type")}>
              {dataTypes.map((dt) => (<option key={dt} value={dt}>{dt}</option>))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">描述</label>
            <textarea value={formData.description} onChange={(e) => setFormData({ ...formData, description: e.target.value })} rows={2} placeholder="列的业务含义" className={inputCls("description")} />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">示例值</label>
            {formData.sample_values.map((sv, i) => (
              <div key={i} className="flex gap-2 mb-2">
                <input type="text" value={sv} onChange={(e) => updateSampleValue(i, e.target.value)} placeholder="示例值" className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100" />
                <button type="button" onClick={() => removeSampleValue(i)} className="px-2 text-red-500 hover:text-red-700">×</button>
              </div>
            ))}
            <button type="button" onClick={addSampleValue} className="text-sm text-blue-600 dark:text-blue-400 hover:underline">+ 添加示例值</button>
          </div>

          <div className="flex gap-3 pt-4">
            <button type="button" onClick={onClose} className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800">取消</button>
            <button type="submit" disabled={loading} className="flex-1 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 disabled:opacity-50">
              {loading ? "保存中..." : editData ? "更新" : "添加"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
