"use client";

import { useState, useEffect } from "react";
import { BusinessTerm } from "@/types/mcp-terms";
import { createTerm, updateTerm, getTerm } from "@/lib/api/mcpTerms";

interface TermFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  editId?: number | null;
}

export default function TermFormModal({ isOpen, onClose, onSuccess, editId }: TermFormModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formData, setFormData] = useState({
    term: "",
    category: "指标",
    definition: "",
    aliases: [""],
    examples: [""],
  });

  const categories = ["指标", "维度", "实体", "时间", "业务规则", "其他"];

  useEffect(() => {
    if (editId && isOpen) {
      loadTerm(editId);
    } else if (isOpen) {
      setFormData({ term: "", category: "指标", definition: "", aliases: [""], examples: [""] });
      setError(null);
      setErrors({});
    }
  }, [editId, isOpen]);

  const loadTerm = async (id: number) => {
    setLoading(true);
    try {
      const data = await getTerm(id);
      setFormData({
        term: data.term,
        category: data.category,
        definition: data.definition,
        aliases: data.aliases.length > 0 ? data.aliases : [""],
        examples: data.examples.length > 0 ? data.examples : [""],
      });
    } catch (err: any) {
      setError(err.message || "加载术语失败");
    } finally {
      setLoading(false);
    }
  };

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!formData.term.trim()) newErrors.term = "请输入术语名称";
    if (!formData.definition.trim()) newErrors.definition = "请输入术语定义";
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
        term: formData.term,
        category: formData.category,
        definition: formData.definition,
        aliases: formData.aliases.filter((a) => a.trim()),
        examples: formData.examples.filter((e) => e.trim()),
      };
      if (editId) {
        await updateTerm(editId, payload);
      } else {
        await createTerm(payload);
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || "保存失败");
    } finally {
      setLoading(false);
    }
  };

  const addArrayItem = (field: "aliases" | "examples") => {
    setFormData({ ...formData, [field]: [...formData[field], ""] });
  };

  const removeArrayItem = (field: "aliases" | "examples", index: number) => {
    const arr = [...formData[field]];
    arr.splice(index, 1);
    if (arr.length === 0) arr.push("");
    setFormData({ ...formData, [field]: arr });
  };

  const updateArrayItem = (field: "aliases" | "examples", index: number, value: string) => {
    const arr = [...formData[field]];
    arr[index] = value;
    setFormData({ ...formData, [field]: arr });
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
            {editId ? "编辑术语" : "添加术语"}
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
              术语名称 <span className="text-red-500">*</span>
            </label>
            <input type="text" value={formData.term} onChange={(e) => setFormData({ ...formData, term: e.target.value })} placeholder="如：销售额、毛利率" className={inputCls("term")} />
            {errors.term && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.term}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">类别</label>
            <select value={formData.category} onChange={(e) => setFormData({ ...formData, category: e.target.value })} className={inputCls("category")}>
              {categories.map((c) => (<option key={c} value={c}>{c}</option>))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              定义 <span className="text-red-500">*</span>
            </label>
            <textarea value={formData.definition} onChange={(e) => setFormData({ ...formData, definition: e.target.value })} rows={3} placeholder="术语的业务含义" className={inputCls("definition")} />
            {errors.definition && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.definition}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">别名</label>
            {formData.aliases.map((alias, i) => (
              <div key={i} className="flex gap-2 mb-2">
                <input type="text" value={alias} onChange={(e) => updateArrayItem("aliases", i, e.target.value)} placeholder="别名" className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100" />
                <button type="button" onClick={() => removeArrayItem("aliases", i)} className="px-2 text-red-500 hover:text-red-700">×</button>
              </div>
            ))}
            <button type="button" onClick={() => addArrayItem("aliases")} className="text-sm text-blue-600 dark:text-blue-400 hover:underline">+ 添加别名</button>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">示例</label>
            {formData.examples.map((ex, i) => (
              <div key={i} className="flex gap-2 mb-2">
                <input type="text" value={ex} onChange={(e) => updateArrayItem("examples", i, e.target.value)} placeholder="使用示例" className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100" />
                <button type="button" onClick={() => removeArrayItem("examples", i)} className="px-2 text-red-500 hover:text-red-700">×</button>
              </div>
            ))}
            <button type="button" onClick={() => addArrayItem("examples")} className="text-sm text-blue-600 dark:text-blue-400 hover:underline">+ 添加示例</button>
          </div>

          <div className="flex gap-3 pt-4">
            <button type="button" onClick={onClose} className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-700 text-gray-700 dark:text-gray-300 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800">取消</button>
            <button type="submit" disabled={loading} className="flex-1 px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600 disabled:opacity-50">
              {loading ? "保存中..." : editId ? "更新" : "添加"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
