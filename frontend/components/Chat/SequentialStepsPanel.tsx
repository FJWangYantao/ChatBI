"use client";

import { useState, useEffect } from 'react';

interface SequentialStep {
    step_index: number;
    title: string;
    status: 'pending' | 'running' | 'completed' | 'failed';
    result?: string;
    error?: string;
    duration?: number;
}

interface SequentialStepsPanelProps {
    steps: SequentialStep[];
    totalSteps: number;
}

export default function SequentialStepsPanel({ steps, totalSteps }: SequentialStepsPanelProps) {
    const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());

    const toggleStep = (stepIndex: number) => {
        const newExpanded = new Set(expandedSteps);
        if (newExpanded.has(stepIndex)) {
            newExpanded.delete(stepIndex);
        } else {
            newExpanded.add(stepIndex);
        }
        setExpandedSteps(newExpanded);
    };

    const getStepIcon = (status: string) => {
        switch (status) {
            case 'completed':
                return '✅';
            case 'running':
                return '⏳';
            case 'failed':
                return '❌';
            default:
                return '⭕';
        }
    };

    const getStepStatusClass = (status: string) => {
        switch (status) {
            case 'completed':
                return 'sequential-step-completed';
            case 'running':
                return 'sequential-step-running';
            case 'failed':
                return 'sequential-step-failed';
            default:
                return 'sequential-step-pending';
        }
    };

    return (
        <div className="sequential-steps-panel">
            <div className="sequential-steps-header">
                <span className="sequential-steps-title">📊 多步骤分析</span>
                <span className="sequential-steps-progress">
                    {steps.filter(s => s.status === 'completed').length} / {totalSteps} 完成
                </span>
            </div>

            <div className="sequential-steps-list">
                {steps.map((step) => (
                    <div
                        key={step.step_index}
                        className={`sequential-step-card ${getStepStatusClass(step.status)}`}
                    >
                        <div
                            className="sequential-step-header"
                            onClick={() => toggleStep(step.step_index)}
                        >
                            <div className="sequential-step-header-left">
                                <span className="sequential-step-icon">{getStepIcon(step.status)}</span>
                                <span className="sequential-step-number">步骤 {step.step_index + 1}</span>
                                <span className="sequential-step-title">{step.title}</span>
                            </div>
                            <div className="sequential-step-header-right">
                                {step.duration && (
                                    <span className="sequential-step-duration">{step.duration}ms</span>
                                )}
                                {step.status === 'running' && (
                                    <span className="sequential-step-spinner">⏳</span>
                                )}
                                <svg
                                    className={`sequential-step-expand-icon ${expandedSteps.has(step.step_index) ? 'expanded' : ''}`}
                                    fill="none"
                                    stroke="currentColor"
                                    viewBox="0 0 24 24"
                                >
                                    <path
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                        strokeWidth={2}
                                        d="M19 9l-7 7-7-7"
                                    />
                                </svg>
                            </div>
                        </div>

                        {expandedSteps.has(step.step_index) && (
                            <div className="sequential-step-details">
                                {step.status === 'completed' && step.result && (
                                    <div className="sequential-step-result">
                                        <div className="sequential-step-result-label">结果：</div>
                                        <pre className="sequential-step-result-content">{step.result}</pre>
                                    </div>
                                )}
                                {step.status === 'failed' && step.error && (
                                    <div className="sequential-step-error">
                                        <div className="sequential-step-error-label">错误：</div>
                                        <pre className="sequential-step-error-content">{step.error}</pre>
                                    </div>
                                )}
                                {step.status === 'running' && (
                                    <div className="sequential-step-running-text">
                                        正在执行中...
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                ))}
            </div>

            <style jsx>{`
                .sequential-steps-panel {
                    background: #f8f9fa;
                    border: 1px solid #e0e0e0;
                    border-radius: 8px;
                    padding: 16px;
                    margin: 12px 0;
                }

                .sequential-steps-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin-bottom: 16px;
                    padding-bottom: 12px;
                    border-bottom: 2px solid #e0e0e0;
                }

                .sequential-steps-title {
                    font-size: 16px;
                    font-weight: 600;
                    color: #333;
                }

                .sequential-steps-progress {
                    font-size: 14px;
                    color: #666;
                    background: #fff;
                    padding: 4px 12px;
                    border-radius: 12px;
                    border: 1px solid #e0e0e0;
                }

                .sequential-steps-list {
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                }

                .sequential-step-card {
                    background: #fff;
                    border: 1px solid #e0e0e0;
                    border-radius: 6px;
                    overflow: hidden;
                    transition: all 0.2s;
                }

                .sequential-step-card:hover {
                    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
                }

                .sequential-step-completed {
                    border-left: 4px solid #4caf50;
                }

                .sequential-step-running {
                    border-left: 4px solid #2196f3;
                    animation: pulse 1.5s ease-in-out infinite;
                }

                .sequential-step-failed {
                    border-left: 4px solid #f44336;
                }

                .sequential-step-pending {
                    border-left: 4px solid #9e9e9e;
                }

                @keyframes pulse {
                    0%, 100% { opacity: 1; }
                    50% { opacity: 0.7; }
                }

                .sequential-step-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 12px 16px;
                    cursor: pointer;
                    user-select: none;
                }

                .sequential-step-header:hover {
                    background: #f5f5f5;
                }

                .sequential-step-header-left {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    flex: 1;
                }

                .sequential-step-header-right {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .sequential-step-icon {
                    font-size: 18px;
                }

                .sequential-step-number {
                    font-size: 13px;
                    font-weight: 600;
                    color: #666;
                    background: #f0f0f0;
                    padding: 2px 8px;
                    border-radius: 4px;
                }

                .sequential-step-title {
                    font-size: 14px;
                    color: #333;
                    font-weight: 500;
                }

                .sequential-step-duration {
                    font-size: 12px;
                    color: #999;
                }

                .sequential-step-spinner {
                    animation: spin 1s linear infinite;
                }

                @keyframes spin {
                    from { transform: rotate(0deg); }
                    to { transform: rotate(360deg); }
                }

                .sequential-step-expand-icon {
                    width: 20px;
                    height: 20px;
                    transition: transform 0.2s;
                }

                .sequential-step-expand-icon.expanded {
                    transform: rotate(180deg);
                }

                .sequential-step-details {
                    padding: 12px 16px;
                    border-top: 1px solid #f0f0f0;
                    background: #fafafa;
                }

                .sequential-step-result,
                .sequential-step-error {
                    margin: 0;
                }

                .sequential-step-result-label,
                .sequential-step-error-label {
                    font-size: 13px;
                    font-weight: 600;
                    margin-bottom: 8px;
                    color: #666;
                }

                .sequential-step-result-content,
                .sequential-step-error-content {
                    background: #fff;
                    border: 1px solid #e0e0e0;
                    border-radius: 4px;
                    padding: 12px;
                    font-size: 13px;
                    line-height: 1.6;
                    overflow-x: auto;
                    margin: 0;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                }

                .sequential-step-error-content {
                    color: #d32f2f;
                    background: #ffebee;
                    border-color: #ffcdd2;
                }

                .sequential-step-running-text {
                    font-size: 13px;
                    color: #666;
                    font-style: italic;
                }
            `}</style>
        </div>
    );
}
