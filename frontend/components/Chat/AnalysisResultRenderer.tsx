import React, { useState, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

// ─── 类型定义 ────────────────────────────────────────────────────────────────

interface StatsItem {
    label: string;
    value: string;
}

interface StatsSection {
    type: 'stats';
    title?: string;
    items: StatsItem[];
}

interface TableSection {
    type: 'table';
    title?: string;
    columns: string[];
    rows: string[][];
}

interface TextSection {
    type: 'text';
    title?: string;
    content: string;
}

interface MarkdownSection {
    type: 'markdown';
    title?: string;
    content: string;
}

type Section = StatsSection | TableSection | TextSection | MarkdownSection;

interface StructuredContent {
    sections: Section[];
}

interface AnalysisResultRendererProps {
    content: any;   // 兼容 Markdown 字符串、结构化 JSON 对象、流式占位对象
    title?: string;
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────

export default function AnalysisResultRenderer({ content, title }: AnalysisResultRendererProps) {
    const [collapsed, setCollapsed] = useState(false);

    // 检测流式状态
    const isStreaming = content?._streaming === true;
    const streamedText: string = content?._streamedText || '';

    // 流式状态：实时渲染已接收的 Markdown，或显示骨架屏
    if (isStreaming) {
        return (
            <div className="analysis-result-card">
                <div className="analysis-result-header">
                    <div className="analysis-result-header-left">
                        <span className="analysis-result-icon">📋</span>
                        <span className="analysis-result-title">{title || '分析详情'}</span>
                        <span className="analysis-result-badge animate-pulse">正在排版...</span>
                    </div>
                </div>
                <div className="analysis-result-body">
                    {streamedText ? (
                        <div className="analysis-text prose prose-sm dark:prose-invert max-w-none">
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                {streamedText}
                            </ReactMarkdown>
                            <span className="inline-block w-2 h-4 bg-accent/60 animate-pulse ml-0.5 align-text-bottom rounded-sm" />
                        </div>
                    ) : (
                        <AnalysisSkeleton />
                    )}
                </div>
            </div>
        );
    }

    // Markdown 字符串内容（新版流式完成后 / 历史加载）
    if (typeof content === 'string') {
        return (
            <div className="analysis-result-card">
                <div className="analysis-result-header">
                    <div className="analysis-result-header-left">
                        <span className="analysis-result-icon">📋</span>
                        <span className="analysis-result-title">{title || '分析详情'}</span>
                    </div>
                    <button
                        className="analysis-collapse-btn"
                        onClick={() => setCollapsed(v => !v)}
                        aria-label={collapsed ? '展开' : '折叠'}
                    >
                        <span className="analysis-collapse-icon">{collapsed ? '▼' : '▲'}</span>
                        <span>{collapsed ? '展开' : '折叠'}</span>
                    </button>
                </div>
                {!collapsed && (
                    <div className="analysis-result-body">
                        <div className="analysis-text prose prose-sm dark:prose-invert max-w-none">
                            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                {content}
                            </ReactMarkdown>
                        </div>
                    </div>
                )}
            </div>
        );
    }

    // Legacy 结构化 JSON 内容（旧版历史数据兼容）
    const structured: StructuredContent = useMemo(() => {
        if (!content) return { sections: [] };
        if (typeof content === 'object' && content.sections) {
            return content as StructuredContent;
        }
        return { sections: [] };
    }, [content]);

    const sections = structured.sections ?? [];

    return (
        <div className="analysis-result-card">
            {/* ── 标题栏 ── */}
            <div className="analysis-result-header">
                <div className="analysis-result-header-left">
                    <span className="analysis-result-icon">📋</span>
                    <span className="analysis-result-title">{title || '分析详情'}</span>
                    <span className="analysis-result-badge">{sections.length} 个分析块</span>
                </div>
                <button
                    className="analysis-collapse-btn"
                    onClick={() => setCollapsed(v => !v)}
                    aria-label={collapsed ? '展开' : '折叠'}
                >
                    <span className="analysis-collapse-icon">{collapsed ? '▼' : '▲'}</span>
                    <span>{collapsed ? '展开' : '折叠'}</span>
                </button>
            </div>

            {/* ── 内容区 ── */}
            {!collapsed && (
                <div className="analysis-result-body">
                    {sections.length === 0 && (
                        <p className="analysis-empty">暂无详细分析内容</p>
                    )}
                    {sections.map((section, idx) => (
                        <SectionRenderer key={idx} section={section} />
                    ))}
                </div>
            )}
        </div>
    );
}

// ─── Section 分发渲染 ─────────────────────────────────────────────────────────

function SectionRenderer({ section }: { section: Section }) {
    return (
        <div className="analysis-section">
            {section.title && (
                <h4 className="analysis-section-title">
                    <span className="analysis-section-title-bar" />
                    {section.title}
                </h4>
            )}
            {section.type === 'stats' && <StatsRenderer section={section} />}
            {section.type === 'table' && <TableRenderer section={section} />}
            {(section.type === 'text' || section.type === 'markdown') && <TextRenderer section={section} />}
        </div>
    );
}

// ─── Stats 卡片网格 ───────────────────────────────────────────────────────────

const STAT_COLORS = [
    { bg: '#eff6ff', border: '#bfdbfe', label: '#1d4ed8', value: '#1e3a8a' },
    { bg: '#f0fdf4', border: '#bbf7d0', label: '#15803d', value: '#14532d' },
    { bg: '#fdf4ff', border: '#e9d5ff', label: '#7e22ce', value: '#581c87' },
    { bg: '#fff7ed', border: '#fed7aa', label: '#c2410c', value: '#7c2d12' },
    { bg: '#f0f9ff', border: '#bae6fd', label: '#0369a1', value: '#0c4a6e' },
    { bg: '#fefce8', border: '#fde68a', label: '#a16207', value: '#713f12' },
];

function StatsRenderer({ section }: { section: StatsSection }) {
    return (
        <div className="analysis-stats-grid">
            {section.items.map((item, i) => {
                const color = STAT_COLORS[i % STAT_COLORS.length];
                // 检测趋势指示符（↑/↓/→），拆分主值和趋势部分
                const trendMatch = item.value.match(/^(.+?)\s*(↑|↓|→)\s*(.*)$/);
                const mainValue = trendMatch ? trendMatch[1] : item.value;
                const trendArrow = trendMatch ? trendMatch[2] : null;
                const trendChange = trendMatch ? trendMatch[3] : null;
                const trendColor = trendArrow === '↑' ? '#16a34a' : trendArrow === '↓' ? '#dc2626' : '#6b7280';

                return (
                    <div
                        key={i}
                        className="analysis-stat-card"
                        style={{
                            background: color.bg,
                            borderColor: color.border,
                        }}
                    >
                        <div className="analysis-stat-label" style={{ color: color.label }}>
                            {item.label}
                        </div>
                        <div className="analysis-stat-value" style={{ color: color.value }}>
                            {mainValue}
                            {trendArrow && (
                                <span style={{ color: trendColor, fontSize: '0.8em', marginLeft: '6px' }}>
                                    {trendArrow} {trendChange}
                                </span>
                            )}
                        </div>
                    </div>
                );
            })}
        </div>
    );
}

// ─── Table 带分页 ─────────────────────────────────────────────────────────────

const PAGE_SIZE = 10;

function TableRenderer({ section }: { section: TableSection }) {
    const [page, setPage] = useState(1);
    const totalPages = Math.ceil((section.rows?.length ?? 0) / PAGE_SIZE);
    const pageRows = (section.rows ?? []).slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

    return (
        <div className="analysis-table-wrap">
            <div className="analysis-table-scroll">
                <table className="analysis-table">
                    <thead>
                        <tr>
                            {(section.columns ?? []).map((col, i) => (
                                <th key={i} className="analysis-th">{col}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {pageRows.map((row, ri) => (
                            <tr key={ri} className={ri % 2 === 0 ? 'analysis-tr-even' : 'analysis-tr-odd'}>
                                {row.map((cell, ci) => (
                                    <td key={ci} className="analysis-td">{cell}</td>
                                ))}
                            </tr>
                        ))}
                        {pageRows.length === 0 && (
                            <tr>
                                <td colSpan={section.columns?.length || 1} className="analysis-td-empty">
                                    暂无数据
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
            {totalPages > 1 && (
                <div className="analysis-pagination">
                    <span className="analysis-pagination-info">
                        共 {section.rows?.length} 行 · 第 {page} / {totalPages} 页
                    </span>
                    <div className="analysis-pagination-btns">
                        <button
                            className="analysis-page-btn"
                            onClick={() => setPage(p => Math.max(1, p - 1))}
                            disabled={page === 1}
                        >
                            上一页
                        </button>
                        <button
                            className="analysis-page-btn"
                            onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                            disabled={page === totalPages}
                        >
                            下一页
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}

// ─── Text Markdown 渲染 ───────────────────────────────────────────────────────

function TextRenderer({ section }: { section: TextSection | MarkdownSection }) {
    return (
        <div className="analysis-text prose prose-sm dark:prose-invert max-w-none">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {section.content}
            </ReactMarkdown>
        </div>
    );
}

// ─── 骨架屏（流式排版期间显示） ──────────────────────────────────────────────

function AnalysisSkeleton() {
    return (
        <div className="space-y-4 p-2">
            {/* Stats 卡片占位 */}
            <div className="grid grid-cols-3 gap-3">
                {[0, 1, 2].map(i => (
                    <div key={i} className="rounded-lg border border-border/30 p-3 space-y-2">
                        <div className="h-3 w-16 rounded bg-muted/50 animate-pulse" />
                        <div className="h-5 w-24 rounded bg-muted/50 animate-pulse" />
                    </div>
                ))}
            </div>
            {/* 表格行占位 */}
            <div className="space-y-2">
                <div className="h-4 w-32 rounded bg-muted/50 animate-pulse" />
                {[0, 1, 2, 3].map(i => (
                    <div key={i} className="flex gap-4">
                        <div className="h-3 flex-1 rounded bg-muted/50 animate-pulse" />
                        <div className="h-3 flex-1 rounded bg-muted/50 animate-pulse" />
                        <div className="h-3 flex-1 rounded bg-muted/50 animate-pulse" />
                    </div>
                ))}
            </div>
            {/* 文本行占位 */}
            <div className="space-y-2">
                <div className="h-3 w-full rounded bg-muted/50 animate-pulse" />
                <div className="h-3 w-3/4 rounded bg-muted/50 animate-pulse" />
            </div>
        </div>
    );
}
