"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { BusinessTerm, ColumnMapping, TimeExpression, MCPStats } from "@/types/mcp-terms";
import {
  getAllTerms,
  deleteTerm,
  getAllMappings,
  deleteMapping,
  getAllExpressions,
  deleteExpression,
  getStats,
} from "@/lib/api/mcpTerms";
import TermFormModal from "@/components/MCPTerms/TermFormModal";
import ColumnMappingFormModal from "@/components/MCPTerms/ColumnMappingFormModal";
import TimeExpressionFormModal from "@/components/MCPTerms/TimeExpressionFormModal";

export default function MCPTermsPage() {
  // 状态管理
  const [terms, setTerms] = useState<BusinessTerm[]>([]);
  const [mappings, setMappings] = useState<ColumnMapping[]>([]);
  const [expressions, setExpressions] = useState<TimeExpression[]>([]);
  const [stats, setStats] = useState<MCPStats>({
    total_terms: 0,
    total_mappings: 0,
    total_expressions: 0,
    categories: {},
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"terms" | "mappings" | "expressions">("terms");

  // 搜索状态
  const [searchTerm, setSearchTerm] = useState("");

  // 模态框状态
  const [termModalOpen, setTermModalOpen] = useState(false);
  const [termEditId, setTermEditId] = useState<number | null>(null);
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [mappingEditData, setMappingEditData] = useState<ColumnMapping | null>(null);
  const [exprModalOpen, setExprModalOpen] = useState(false);
  const [exprEditData, setExprEditData] = useState<TimeExpression | null>(null);

  // 加载数据
  const loadData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [termsData, mappingsData, expressionsData, statsData] = await Promise.all([
        getAllTerms(),
        getAllMappings(),
        getAllExpressions(),
        getStats(),
      ]);
      setTerms(termsData);
      setMappings(mappingsData);
      setExpressions(expressionsData);
      setStats(statsData);
    } catch (err: any) {
      setError(err.message || "加载数据失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  // 删除术语
  const handleDeleteTerm = async (id: number, name: string) => {
    if (!confirm(`确定要删除术语 "${name}" 吗？关联的列映射也会被删除。`)) {
      return;
    }
    try {
      await deleteTerm(id);
      await loadData();
    } catch (err: any) {
      alert(err.message || "删除失败");
    }
  };

  // 删除列映射
  const handleDeleteMapping = async (id: number) => {
    if (!confirm("确定要删除此列映射吗？")) {
      return;
    }
    try {
      await deleteMapping(id);
      await loadData();
    } catch (err: any) {
      alert(err.message || "删除失败");
    }
  };

  // 删除时间表达式
  const handleDeleteExpression = async (id: number) => {
    if (!confirm("确定要删除此时间表达式吗？")) {
      return;
    }
    try {
      await deleteExpression(id);
      await loadData();
    } catch (err: any) {
      alert(err.message || "删除失败");
    }
  };

  // 过滤数据
  const filteredTerms = terms.filter(
    (term) =>
      term.term.toLowerCase().includes(searchTerm.toLowerCase()) ||
      term.definition.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const filteredMappings = mappings.filter(
    (mapping) =>
      mapping.table_name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      mapping.column_name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const filteredExpressions = expressions.filter(
    (expr) =>
      expr.pattern.toLowerCase().includes(searchTerm.toLowerCase()) ||
      expr.expression_type.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950">
      {/* 顶部导航栏 */}
      <header className="bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800">
        <div className="max-w-7xl mx-auto px-4 py-4 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Link
              href="/"
              className="text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100"
            >
              ← 返回主页
            </Link>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">
              MCP 业务术语知识库
            </h1>
          </div>
          <button
            onClick={loadData}
            className="px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-700 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800"
          >
            刷新
          </button>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 py-8">
        {/* 统计卡片 */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <p className="text-sm text-gray-500 dark:text-gray-400">总术语数</p>
            <p className="text-3xl font-bold mt-1 text-gray-900 dark:text-gray-100">
              {stats.total_terms}
            </p>
          </div>
          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <p className="text-sm text-gray-500 dark:text-gray-400">总列映射数</p>
            <p className="text-3xl font-bold mt-1 text-gray-900 dark:text-gray-100">
              {stats.total_mappings}
            </p>
          </div>
          <div className="bg-white dark:bg-gray-900 rounded-lg p-6 border border-gray-200 dark:border-gray-800">
            <p className="text-sm text-gray-500 dark:text-gray-400">总时间表达式数</p>
            <p className="text-3xl font-bold mt-1 text-gray-900 dark:text-gray-100">
              {stats.total_expressions}
            </p>
          </div>
        </div>

        {/* Tab 切换 */}
        <div className="bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-800">
          <div className="border-b border-gray-200 dark:border-gray-800">
            <div className="flex">
              <button
                onClick={() => setActiveTab("terms")}
                className={`px-6 py-3 text-sm font-medium border-b-2 ${
                  activeTab === "terms"
                    ? "border-gray-900 dark:border-gray-100 text-gray-900 dark:text-gray-100"
                    : "border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300"
                }`}
              >
                业务术语 ({terms.length})
              </button>
              <button
                onClick={() => setActiveTab("mappings")}
                className={`px-6 py-3 text-sm font-medium border-b-2 ${
                  activeTab === "mappings"
                    ? "border-gray-900 dark:border-gray-100 text-gray-900 dark:text-gray-100"
                    : "border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300"
                }`}
              >
                列映射 ({mappings.length})
              </button>
              <button
                onClick={() => setActiveTab("expressions")}
                className={`px-6 py-3 text-sm font-medium border-b-2 ${
                  activeTab === "expressions"
                    ? "border-gray-900 dark:border-gray-100 text-gray-900 dark:text-gray-100"
                    : "border-transparent text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300"
                }`}
              >
                时间表达式 ({expressions.length})
              </button>
            </div>
          </div>

          {/* Tab 内容 */}
          <div className="p-6">
            {/* 搜索栏 */}
            <div className="mb-6 flex items-center gap-4">
              <input
                type="text"
                placeholder="搜索..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100"
              />
              <button
                onClick={() => {
                  if (activeTab === "terms") { setTermEditId(null); setTermModalOpen(true); }
                  else if (activeTab === "mappings") { setMappingEditData(null); setMappingModalOpen(true); }
                  else { setExprEditData(null); setExprModalOpen(true); }
                }}
                className="px-4 py-2 bg-gray-900 dark:bg-gray-700 text-white rounded-lg hover:bg-gray-800 dark:hover:bg-gray-600"
              >
                添加
              </button>
            </div>

            {loading && (
              <div className="text-center py-12 text-gray-500 dark:text-gray-400">
                加载中...
              </div>
            )}

            {error && (
              <div className="text-center py-12 text-red-500">
                {error}
              </div>
            )}

            {!loading && !error && (
              <>
                {/* 业务术语列表 */}
                {activeTab === "terms" && (
                  <div className="space-y-4">
                    {filteredTerms.length === 0 ? (
                      <div className="text-center py-12 text-gray-500 dark:text-gray-400">
                        暂无术语数据
                      </div>
                    ) : (
                      filteredTerms.map((term) => (
                        <div
                          key={term.id}
                          className="bg-gray-50 dark:bg-gray-800 rounded-lg p-4 border border-gray-200 dark:border-gray-700"
                        >
                          <div className="flex items-start justify-between">
                            <div className="flex-1">
                              <div className="flex items-center gap-2 mb-2">
                                <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                                  {term.term}
                                </h3>
                                <span className="px-2 py-1 text-xs bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300 rounded">
                                  {term.category}
                                </span>
                              </div>
                              <p className="text-sm text-gray-600 dark:text-gray-400 mb-2">
                                {term.definition}
                              </p>
                              {term.aliases.length > 0 && (
                                <p className="text-xs text-gray-500 dark:text-gray-500">
                                  别名: {term.aliases.join(", ")}
                                </p>
                              )}
                            </div>
                            <div className="flex gap-2">
                              <button
                                onClick={() => { setTermEditId(term.id!); setTermModalOpen(true); }}
                                className="px-3 py-1 text-sm text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-700 rounded hover:bg-gray-100 dark:hover:bg-gray-700"
                              >
                                编辑
                              </button>
                              <button
                                onClick={() => handleDeleteTerm(term.id!, term.term)}
                                className="px-3 py-1 text-sm text-red-600 dark:text-red-400 border border-red-300 dark:border-red-700 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                              >
                                删除
                              </button>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}

                {/* 列映射列表 */}
                {activeTab === "mappings" && (
                  <div className="space-y-4">
                    {filteredMappings.length === 0 ? (
                      <div className="text-center py-12 text-gray-500 dark:text-gray-400">
                        暂无列映射数据
                      </div>
                    ) : (
                      filteredMappings.map((mapping) => (
                        <div
                          key={mapping.id}
                          className="bg-gray-50 dark:bg-gray-800 rounded-lg p-4 border border-gray-200 dark:border-gray-700"
                        >
                          <div className="flex items-start justify-between">
                            <div className="flex-1">
                              <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-2">
                                {mapping.table_name}.{mapping.column_name}
                              </h3>
                              <p className="text-sm text-gray-600 dark:text-gray-400 mb-1">
                                类型: {mapping.data_type}
                              </p>
                              {mapping.description && (
                                <p className="text-sm text-gray-600 dark:text-gray-400">
                                  {mapping.description}
                                </p>
                              )}
                            </div>
                            <div className="flex gap-2">
                              <button
                                onClick={() => { setMappingEditData(mapping); setMappingModalOpen(true); }}
                                className="px-3 py-1 text-sm text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-700 rounded hover:bg-gray-100 dark:hover:bg-gray-700"
                              >
                                编辑
                              </button>
                              <button
                                onClick={() => handleDeleteMapping(mapping.id!)}
                                className="px-3 py-1 text-sm text-red-600 dark:text-red-400 border border-red-300 dark:border-red-700 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                              >
                                删除
                              </button>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}

                {/* 时间表达式列表 */}
                {activeTab === "expressions" && (
                  <div className="space-y-4">
                    {filteredExpressions.length === 0 ? (
                      <div className="text-center py-12 text-gray-500 dark:text-gray-400">
                        暂无时间表达式数据
                      </div>
                    ) : (
                      filteredExpressions.map((expr) => (
                        <div
                          key={expr.id}
                          className="bg-gray-50 dark:bg-gray-800 rounded-lg p-4 border border-gray-200 dark:border-gray-700"
                        >
                          <div className="flex items-start justify-between">
                            <div className="flex-1">
                              <div className="flex items-center gap-2 mb-2">
                                <h3 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                                  {expr.pattern}
                                </h3>
                                <span className="px-2 py-1 text-xs bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300 rounded">
                                  {expr.expression_type}
                                </span>
                              </div>
                              {expr.examples.length > 0 && (
                                <p className="text-xs text-gray-500 dark:text-gray-500">
                                  示例: {expr.examples.join(", ")}
                                </p>
                              )}
                            </div>
                            <div className="flex gap-2">
                              <button
                                onClick={() => { setExprEditData(expr); setExprModalOpen(true); }}
                                className="px-3 py-1 text-sm text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-gray-700 rounded hover:bg-gray-100 dark:hover:bg-gray-700"
                              >
                                编辑
                              </button>
                              <button
                                onClick={() => handleDeleteExpression(expr.id!)}
                                className="px-3 py-1 text-sm text-red-600 dark:text-red-400 border border-red-300 dark:border-red-700 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                              >
                                删除
                              </button>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </main>

      {/* 模态框 */}
      <TermFormModal
        isOpen={termModalOpen}
        onClose={() => setTermModalOpen(false)}
        onSuccess={loadData}
        editId={termEditId}
      />
      <ColumnMappingFormModal
        isOpen={mappingModalOpen}
        onClose={() => setMappingModalOpen(false)}
        onSuccess={loadData}
        editData={mappingEditData}
        terms={terms}
      />
      <TimeExpressionFormModal
        isOpen={exprModalOpen}
        onClose={() => setExprModalOpen(false)}
        onSuccess={loadData}
        editData={exprEditData}
      />
    </div>
  );
}


