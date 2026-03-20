import React, { useState, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import StepCard from './StepCard';
import { MessageTag } from '@/app/page';
import { AutoChart } from '@/components/Charts';
import { ModernTable } from '@/components/Table';

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
    embedded?: boolean;      // 嵌入推理链时为 true，去掉外层卡片和标题栏
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────

export default function AnalysisResultRenderer({ content, title, allTags = [], embedded = false }: AnalysisResultRendererProps) {
    const [collapsed, setCollapsed] = useState(false);
    const [expandAll, setExpandAll] = useState(false);

    // ⚠️ 所有 hooks 必须在条件判断之前调用（React Hooks 规则）
    const structured = useMemo(() => {
        if (!content) return { sections: [] };
        if (typeof content === 'object' && content.sections) {
            return content as StructuredContent;
        }
        return { sections: [] };
    }, [content]);

    const steps = useMemo(() => {
        const sections = structured.sections ?? [];
        const grouped: Array<{ title: string; icon: string; sections: Section[] }> = [];
        let currentStep: { title: string; icon: string; sections: Section[] } | null = null;

        const chartTags = allTags.filter(tag => tag.type === 'chart');
        let chartIndex = 0;

        sections.forEach((section, idx) => {
            if (section.type === 'stats') {
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
    }, [structured.sections, allTags]);

    // 检测流式状态
    const isStreaming = content?._streaming === true;
    const streamedText: string = content?._streamedText || '';

    // 流式状态：实时渲染已接收的 Markdown，或显示骨架屏
    if (isStreaming) {
        const streamContent = (
            <div className={embedded ? "space-y-2" : "p-6 space-y-4"}>
                {streamedText ? (
                    <div className="prose prose-sm prose-gray max-w-none">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                            {streamedText}
                        </ReactMarkdown>
                        <span className="inline-block w-2 h-4 bg-blue-500/60 animate-pulse ml-0.5 align-text-bottom rounded-sm" />
                    </div>
                ) : (
                    <AnalysisSkeleton />
                )}
            </div>
        );
        if (embedded) return streamContent;
        return (
            <div className="bg-white border border-gray-200 rounded-xl shadow-sm">
                <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
                    <div className="flex items-center gap-3">
                        <span className="text-2xl">📋</span>
                        <span className="text-lg font-semibold text-gray-900">{title || '分析详情'}</span>
                        <span className="px-2.5 py-1 text-xs font-medium text-gray-600 bg-gray-100 rounded-full animate-pulse">正在排版...</span>
                    </div>
                </div>
                {streamContent}
            </div>
        );
    }

    // Markdown 字符串内容（新版流式完成后 / 历史加载）
    if (typeof content === 'string') {
        const mdContent = (
            <div className={embedded ? "space-y-2" : "p-6 space-y-4"}>
                <div className="prose prose-sm prose-gray max-w-none">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {content}
                    </ReactMarkdown>
                </div>
            </div>
        );
        if (embedded) return mdContent;
        return (
            <div className="bg-white border border-gray-200 rounded-xl shadow-sm">
                <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
                    <div className="flex items-center gap-3">
                        <span className="text-2xl">📋</span>
                        <span className="text-lg font-semibold text-gray-900">{title || '分析详情'}</span>
                    </div>
                    <button
                        className="px-3 py-1.5 text-sm font-medium text-gray-700 hover:text-gray-900 hover:bg-gray-50 rounded-lg transition-colors duration-150"
                        onClick={() => setCollapsed(v => !v)}
                        aria-label={collapsed ? '展开' : '折叠'}
                    >
                        <span className="mr-1">{collapsed ? '▼' : '▲'}</span>
                        <span>{collapsed ? '展开' : '折叠'}</span>
                    </button>
                </div>
                {!collapsed && mdContent}
            </div>
        );
    }

    // Legacy 结构化 JSON 内容（旧版历史数据兼容）
    const sections = structured.sections ?? [];

    const structuredContent = (
        <div className={embedded ? "space-y-3" : "p-6 space-y-4"}>
            {steps.length === 0 && (
                <p className="text-center text-gray-400 py-8">暂无详细分析内容</p>
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
    );

    if (embedded) return structuredContent;

    return (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm">
            {/* ── 标题栏 ── */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
                <div className="flex items-center gap-3">
                    <span className="text-2xl">📋</span>
                    <span className="text-lg font-semibold text-gray-900">{title || '分析详情'}</span>
                    <span className="px-2.5 py-1 text-xs font-medium text-gray-600 bg-gray-100 rounded-full">
                        {steps.length} 个分析步骤
                    </span>
                </div>
                <button
                    className="px-3 py-1.5 text-sm font-medium text-gray-700 hover:text-gray-900 hover:bg-gray-50 rounded-lg transition-colors duration-150"
                    onClick={() => setExpandAll(v => !v)}
                    aria-label={expandAll ? '全部折叠' : '全部展开'}
                >
                    <span>{expandAll ? '全部折叠' : '全部展开'}</span>
                    <span className="ml-1">{expandAll ? '▲' : '▼'}</span>
                </button>
            </div>

            {/* ── 内容区 ── */}
            {structuredContent}
        </div>
    );
}

// ─── Section 分发渲染 ─────────────────────────────────────────────────────────

function SectionRenderer({ section }: { section: Section }) {
    return (
        <div className="space-y-4">
            {section.type === 'stats' && <StatsRenderer section={section} />}
            {section.type === 'table' && <TableRenderer section={section} />}
            {(section.type === 'text' || section.type === 'markdown') && <TextRenderer section={section} />}
            {section.type === 'chart' && <ChartRenderer section={section} />}
        </div>
    );
}

// ─── Stats 卡片网格 ───────────────────────────────────────────────────────────

function StatsRenderer({ section }: { section: StatsSection }) {
    return (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {section.items.map((item, i) => {
                // 检测趋势指示符（↑/↓/→），拆分主值和趋势部分
                const trendMatch = item.value.match(/^(.+?)\s*(↑|↓|→)\s*(.*)$/);
                const mainValue = trendMatch ? trendMatch[1] : item.value;
                const trendArrow = trendMatch ? trendMatch[2] : null;
                const trendChange = trendMatch ? trendMatch[3] : null;
                const trendColor = trendArrow === '↑' ? '#16a34a' : trendArrow === '↓' ? '#dc2626' : '#6b7280';

                return (
                    <div
                        key={i}
                        className="bg-white border border-gray-200 rounded-lg p-6 hover:shadow-sm transition-shadow duration-200"
                    >
                        <div className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-2">
                            {item.label}
                        </div>
                        <div className="text-2xl font-semibold text-gray-900">
                            {mainValue}
                            {trendArrow && (
                                <span style={{ color: trendColor, fontSize: '0.7em', marginLeft: '8px' }}>
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

function TableRenderer({ section }: { section: TableSection }) {
    return (
        <ModernTable
            columns={section.columns ?? []}
            rows={section.rows ?? []}
            pageSize={10}
            emptyText="暂无数据"
        />
    );
}

// ─── Text Markdown 渲染 ───────────────────────────────────────────────────────

function TextRenderer({ section }: { section: TextSection | MarkdownSection }) {
    return (
        <div className="prose prose-sm prose-gray max-w-none">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {section.content}
            </ReactMarkdown>
        </div>
    );
}

// ─── Chart 渲染 ───────────────────────────────────────────────────────────────

function ChartRenderer({ section }: { section: ChartSection }) {
    return (
        <div className="my-4">
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
