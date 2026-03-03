"use client";

import { useState, useEffect } from 'react';

interface StepCardProps {
    stepId: string;
    stepTitle: string;
    stepIcon?: string;
    summary?: string;
    children: React.ReactNode;
    defaultExpanded?: boolean;
}

export default function StepCard({
    stepId,
    stepTitle,
    stepIcon = '📋',
    summary,
    children,
    defaultExpanded = false
}: StepCardProps) {
    const [expanded, setExpanded] = useState(defaultExpanded);

    // 当 defaultExpanded 改变时同步更新
    useEffect(() => {
        setExpanded(defaultExpanded);
    }, [defaultExpanded]);

    return (
        <div className={`analysis-step-card ${expanded ? 'analysis-step-expanded' : ''}`}>
            {/* 标题栏 */}
            <div
                className="analysis-step-header"
                onClick={() => setExpanded(!expanded)}
            >
                <div className="analysis-step-header-left">
                    <span className="analysis-step-icon">{stepIcon}</span>
                    <span className="analysis-step-title">{stepTitle}</span>
                </div>
                <div className="analysis-step-expand-btn">
                    <svg
                        className="analysis-step-expand-icon"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                    >
                        <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M9 5l7 7-7 7"
                        />
                    </svg>
                </div>
            </div>

            {/* 摘要区（始终可见） */}
            {summary && (
                <div className="analysis-step-summary">
                    <p className="analysis-step-conclusion">{summary}</p>
                </div>
            )}

            {/* 详情区（可折叠） */}
            <div className={`analysis-step-details ${!expanded ? 'analysis-step-details-collapsed' : ''}`}>
                {children}
            </div>
        </div>
    );
}
