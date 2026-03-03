import React, { useState, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import StepCard from './StepCard';
import { MessageTag } from '@/app/page';
import { AutoChart } from '@/components/Charts';

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

interface ChartSection {
    type: 'chart';
    title?: string;
    chartTag: MessageTag;  // 图表的 tag 数据
}

type Section = StatsSection | TableSection | TextSection | MarkdownSection | ChartSection;

interface StructuredContent {
    sections: Section[];
}

interface AnalysisResultRendererProps {
    content: any;   // 兼容 Markdown 字符串、结构化 JSON 对象、流式占位对象
    title?: string;
    allTags?: MessageTag[];  // 传入所有 tags，用于关联图表
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────

export default function AnalysisResultRenderer({ content, title, allTags = [] }: AnalysisResultRendererProps) {
    const [collapsed, setCollapsed] = useState(false);
    const [expandAll, setExpandAll] = useState(false);

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

    // 自动将 sections 分组为步骤，并关联图表
    const steps = useMemo(() => {
        const grouped: Array<{ title: string; icon: string; sections: Section[] }> = [];
        let currentStep: { title: string; icon: string; sections: Section[] } | null = null;

        // 找到所有图表 tags
        const chartTags = allTags.filter(tag => tag.type === 'chart');
        let chartIndex = 0;

        sections.forEach((section, idx) => {
            // 根据 section 类型自动分组
            if (section.type === 'stats') {
                // Stats 作为新步骤的开始
                if (currentStep) grouped.push(currentStep);
                currentStep = {
                    title: section.title || '数据概览',
                    icon: '📊',
                    sections: [section]
                };
            } else if (section.type === 'table') {
                if (!currentStep) {
                    currentStep = {
                        title: section.title || '数据详情',
                        icon: '📋',
                        sections: [section]
                    };
                } else {
                    currentStep.sections.push(section);
                }

                // 表格后面尝试关联图表
                if (chartIndex < chartTags.length) {
                    const chartSection: ChartSection = {
                        type: 'chart',
                        title: chartTags[chartIndex].title,
                        chartTag: chartTags[chartIndex]
                    };
                    currentStep.sections.push(chartSection);
                    chartIndex++;
                }
            } else {
                // text/markdown
                if (!currentStep) {
                    currentStep = {
                        title: section.title || '分析说明',
                        icon: '📝',
                        sections: [section]
                    };
                } else {
                    currentStep.sections.push(section);
                }
            }
        });

        if (currentStep) grouped.push(currentStep);

        // 如果还有未关联的图表，创建独立的图表步骤
        while (chartIndex < chartTags.length) {
            grouped.push({
                title: chartTags[chartIndex].title || '数据可视化',
                icon: '📈',
                sections: [{
                    type: 'chart',
                    title: chartTags[chartIndex].title,
                    chartTag: chartTags[chartIndex]
                }]
            });
            chartIndex++;
        }

        return grouped;
    }, [sections, allTags]);

    return (
        <div className="analysis-result-card">
            {/* ── 标题栏 ── */}
            <div className="analysis-result-header">
                <div className="analysis-result-header-left">
                    <span className="analysis-result-icon">📋</span>
                    <span className="analysis-result-title">{title || '分析详情'}</span>
                    <span className="analysis-result-badge">{steps.length} 个分析步骤</span>
                </div>
                <button
                    className="analysis-expand-all-btn"
                    onClick={() => setExpandAll(v => !v)}
                    aria-label={expandAll ? '全部折叠' : '全部展开'}
                >
                    <span>{expandAll ? '全部折叠' : '全部展开'}</span>
                    <span>{expandAll ? '▲' : '▼'}</span>
                </button>
            </div>

            {/* ── 内容区 ── */}
            <div className="analysis-result-body">
                {steps.length === 0 && (
                    <p className="analysis-empty">暂无详细分析内容</p>
                )}
                {steps.map((step, idx) => (
                    <StepCard
                        key={idx}
                        stepId={`step-${idx}`}
                        stepTitle={step.title}
                        stepIcon={step.icon}
                        defaultExpanded={expandAll}
                    >
                        {step.sections.map((section, sIdx) => (
                            <SectionRenderer key={sIdx} section={section} />
                        ))}
                    </StepCard>
                ))}
            </div>
        </div>
    );
}

// ─── Section 分发渲染 ─────────────────────────────────────────────────────────

function SectionRenderer({ section }: { section: Section }) {
    // 不再显示 section 自己的标题，因为已经在 StepCard 中显示了
    return (
        <div className="analysis-section">
            {section.type === 'stats' && <StatsRenderer section={section} />}
            {section.type === 'table' && <TableRenderer section={section} />}
            {(section.type === 'text' || section.type === 'markdown') && <TextRenderer section={section} />}
            {section.type === 'chart' && <ChartRenderer section={section} />}
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

// ─── Chart 渲染 ───────────────────────────────────────────────────────────────

function ChartRenderer({ section }: { section: ChartSection }) {
    return (
        <div className="analysis-chart-wrapper">
            <AutoChart tag={section.chartTag} />
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
