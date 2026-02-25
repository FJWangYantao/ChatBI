"use client";

import { useState, useEffect } from "react";
import { TimeExpression } from "@/types/mcp-terms";
import { createExpression, updateExpression } from "@/lib/api/mcpTerms";

interface TimeExpressionFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  editData?: TimeExpression | null;
}

export default function TimeExpressionFormModal({ isOpen, onClose, onSuccess, editData }: TimeExpressionFormModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formData, setFormData] = useState({
    pattern: "",
    expression_type: "relative",
    parse_rule_json: '{"type": "relative", "description": ""}',
    examples: [""],
  });

  const expressionTypes = ["relative", "absolute", "range", "fiscal", "periodic"];

  useEffect(() => {
    if (editData && isOpen) {
      setFormData({
        pattern: editData.pattern,
        expression_type: editData.expression_type,
        parse_rule_json: JSON.stringify(editData.parse_rule, null, 2),
        examples: editData.examples.length > 0 ? editData.examples : [""],
      });
    } else if (isOpen) {
      setFormData({ pattern: "", expression_type: "relative", parse_rule_json: '{"type": "relative", "description": ""}', examples: [""] });
      setError(null);
      setErrors({});
    }
  }, [editData, isOpen]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!formData.pattern.trim()) newErrors.pattern = "请输入匹配模式";
    try {
      JSON.parse(formData.parse_rule_json);
    } catch {
      newErrors.parse_rule_json = "解析规则必须是有效的 JSON";
    }
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
        pattern: formData.pattern,
        expression_type: formData.expression_type,
        parse_rule: JSON.parse(formData.parse_rule_json),
        examples: formData.examples.filter((e) => e.trim()),
      };
      if (editData?.id) {
        await updateExpression(editData.id, payload);
      } else {
        await createExpression(payload);
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || "保存失败");
    } finally {
      setLoading(false);
    }
  };

  const addExample = () => setFormData({ ...formData, examples: [...formData.examples, ""] });
  const removeExample = (i: number) => {
    const arr = [...formData.examples];
    arr.splice(i, 1);
    if (arr.length === 0) arr.push("");
    setFormData({ ...formData, examples: arr });
  };
  const updateExample = (i: number, v: string) => {
    const arr = [...formData.examples];
    arr[i] = v;
    setFormData({ ...formData, examples: arr });
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
            {editData ? "编辑时间表达式" : "添加时间表达式"}
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
              匹配模式 <span className="text-red-500">*</span>
            </label>
            <input type="text" value={formData.pattern} onChange={(e) => setFormData({ ...formData, pattern: e.target.value })} placeholder="如：上个月、今年Q1" className={inputCls("pattern")} />
            {errors.pattern && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.pattern}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">表达式类型</label>
            <select value={formData.expression_type} onChange={(e) => setFormData({ ...formData, expression_type: e.target.value })} className={inputCls("expression_type")}>
              {expressionTypes.map((t) => (<option key={t} value={t}>{t}</option>))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              解析规则 (JSON) <span className="text-red-500">*</span>
            </label>
            <textarea value={formData.parse_rule_json} onChange={(e) => setFormData({ ...formData, parse_rule_json: e.target.value })} rows={4} className={`${inputCls("parse_rule_json")} font-mono text-sm`} />
            {errors.parse_rule_json && <p className="mt-1 text-sm text-red-600 dark:text-red-400">{errors.parse_rule_json}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">示例</label>
            {formData.examples.map((ex, i) => (
              <div key={i} className="flex gap-2 mb-2">
                <input type="text" value={ex} onChange={(e) => updateExample(i, e.target.value)} placeholder="示例" className="flex-1 px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100" />
                <button type="button" onClick={() => removeExample(i)} className="px-2 text-red-500 hover:text-red-700">×</button>
              </div>
            ))}
            <button type="button" onClick={addExample} className="text-sm text-blue-600 dark:text-blue-400 hover:underline">+ 添加示例</button>
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
