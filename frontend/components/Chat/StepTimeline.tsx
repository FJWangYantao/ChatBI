"use client";

import { useState, useEffect, useRef } from "react";
import { CompletedStep } from "@/app/page";

interface StepTimelineProps {
  completedSteps?: CompletedStep[];
  currentStage?: string;
  currentMessage?: string;
  isStreaming?: boolean;
}

const stepIcons: Record<string, string> = {
  query_rewrite: "🔄",
  intent_detection: "🔍",
  prompt_enhancement: "✨",
  clarification: "❓",
  data_analysis: "📋",
  suggestions: "💡",
  planning: "📋",
  llm_generation: "💬",
  diagnostic: "🔬",
};

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function StepResultSummary({ step }: { step: CompletedStep }) {
  const r = step.result;
  if (!r) return null;

  switch (step.stepName) {
    case "query_rewrite":
      return (
        <div className="text-xs opacity-70 space-y-0.5">
          <div>{r.isRewritten ? "已改写" : "无需改写"}</div>
          {r.isRewritten && r.rewrittenMessage && (
            <div className="mt-1 p-2 rounded-lg bg-accent/5 border border-accent/10 font-mono text-[11px] line-clamp-2">
              {r.originalMessage} → {r.rewrittenMessage}
            </div>
          )}
        </div>
      );

    case "intent_detection":
      return (
        <div className="text-xs opacity-70 space-y-0.5">
          <div>类别: <span className="text-accent font-medium">{r.categoryCn}</span></div>
          {r.subtypeCn && <div>子类型: {r.subtypeCn}</div>}
          {r.confidence != null && (
            <div>置信度: {(r.confidence * 100).toFixed(0)}%</div>
          )}
        </div>
      );

    case "prompt_enhancement":
      return (
        <div className="text-xs opacity-70 space-y-0.5">
          <div>{r.isEnhanced ? "已优化" : "无需优化"}</div>
          {r.isEnhanced && r.enhancedPrompt && (
            <div className="mt-1 p-2 rounded-lg bg-accent/5 border border-accent/10 font-mono text-[11px] line-clamp-2">
              {r.enhancedPrompt}
            </div>
          )}
        </div>
      );

    case "clarification":
      return (
        <div className="text-xs opacity-70">
          {r.needsClarification ? `需要澄清 (${r.clarifications?.length || 0} 个问题)` : "无需澄清"}
        </div>
      );

    case "data_analysis":
      return (
        <div className="text-xs opacity-70">
          {r.hasResult ? "分析完成" : "无结果"}
          {r.hasReasoning && " · 含推理过程"}
        </div>
      );

    case "suggestions":
      return (
        <div className="text-xs opacity-70">
          已生成 {r.count || 0} 个推荐问题
        </div>
      );

    default:
      return null;
  }
}

export default function StepTimeline({
  completedSteps,
  currentStage,
  currentMessage,
  isStreaming,
}: StepTimelineProps) {
  const [collapsed, setCollapsed] = useState(true); // 默认折叠
  const [expandedSteps, setExpandedSteps] = useState<Set<string>>(new Set());
  const wasStreamingRef = useRef(false);

  // 当前步骤的实时计时器
  const [elapsed, setElapsed] = useState(0);
  const stageStartRef = useRef<number>(0);

  useEffect(() => {
    if (!isStreaming || !currentStage) {
      setElapsed(0);
      return;
    }
    stageStartRef.current = Date.now();
    setElapsed(0);
    const timer = setInterval(() => {
      setElapsed(Date.now() - stageStartRef.current);
    }, 1000);
    return () => clearInterval(timer);
  }, [currentStage, isStreaming]);

  // 流式结束后 1s 自动折叠
  useEffect(() => {
    if (wasStreamingRef.current && !isStreaming) {
      const timer = setTimeout(() => setCollapsed(true), 1000);
      return () => clearTimeout(timer);
    }
    wasStreamingRef.current = !!isStreaming;
  }, [isStreaming]);

  // 流式中自动展开
  useEffect(() => {
    if (isStreaming) {
      setCollapsed(false);
    }
  }, [isStreaming]);

  // 流式中自动展开新步骤
  useEffect(() => {
    if (isStreaming && completedSteps?.length) {
      const latest = completedSteps[completedSteps.length - 1];
      setExpandedSteps((prev) => new Set(prev).add(latest.stepName));
    }
  }, [completedSteps?.length, isStreaming]);

  const steps = completedSteps || [];
  const hasSteps = steps.length > 0 || (isStreaming && currentStage);

  if (!hasSteps) return null;

  const totalDuration = steps.reduce((sum, s) => sum + s.duration, 0);

  // 折叠态：一行摘要
  if (collapsed && !isStreaming) {
    return (
      <button
        onClick={() => setCollapsed(false)}
        className="flex items-center gap-2 text-xs opacity-60 hover:opacity-100 transition-opacity py-1.5 mb-2 group"
      >
        <svg className="w-3.5 h-3.5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        <span className="font-mono">
          已完成 {steps.length} 个步骤 · {formatDuration(totalDuration)}
        </span>
        <span className="text-accent opacity-0 group-hover:opacity-100 transition-opacity">展开</span>
      </button>
    );
  }

  const toggleStep = (stepName: string) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(stepName)) next.delete(stepName);
      else next.add(stepName);
      return next;
    });
  };

  return (
    <div className="py-2 mb-2">
      {/* 折叠按钮（仅非流式时显示） */}
      {!isStreaming && steps.length > 0 && (
        <button
          onClick={() => setCollapsed(true)}
          className="flex items-center gap-1.5 text-xs opacity-50 hover:opacity-100 transition-opacity mb-2"
        >
          <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
          <span>折叠</span>
        </button>
      )}

      {/* 时间线 */}
      <div className="relative pl-6">
        {/* 垂直连线 */}
        <div className="absolute left-[9px] top-2 bottom-2 w-px bg-border/50" />

        {/* 已完成步骤 */}
        {steps.map((step, idx) => (
          <div key={step.stepName + idx} className="relative mb-3 last:mb-0">
            {/* 节点 */}
            <div className="absolute -left-6 top-0.5 w-[18px] h-[18px] rounded-full flex items-center justify-center bg-background border-2 border-accent/60">
              <svg className="w-2.5 h-2.5 text-accent" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
              </svg>
            </div>

            {/* 步骤内容 */}
            <button
              onClick={() => toggleStep(step.stepName)}
              className="w-full text-left group"
            >
              <div className="flex items-center gap-2 text-sm">
                <span>{stepIcons[step.stepName] || "✅"}</span>
                <span className="font-medium">{step.stepLabel}</span>
                <span className="text-xs font-mono opacity-50">{formatDuration(step.duration)}</span>
                {step.result && (
                  <svg
                    className={`w-3 h-3 opacity-40 group-hover:opacity-70 transition-all ${expandedSteps.has(step.stepName) ? "rotate-90" : ""}`}
                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                )}
              </div>
            </button>

            {/* 展开的结果摘要 */}
            {expandedSteps.has(step.stepName) && step.result && (
              <div className="mt-1.5 ml-6 pl-3 border-l-2 border-accent/20">
                <StepResultSummary step={step} />
              </div>
            )}
          </div>
        ))}

        {/* 当前进行中的步骤 */}
        {isStreaming && currentStage && (
          <div className="relative mb-1">
            {/* 动画节点 */}
            <div className="absolute -left-6 top-0.5 w-[18px] h-[18px] rounded-full flex items-center justify-center bg-background border-2 border-accent">
              <div className="w-2 h-2 rounded-full bg-accent animate-ping" />
            </div>

            <div className="flex items-center gap-2 text-sm animate-shimmer-text">
              <span>{stepIcons[currentStage] || "⏳"}</span>
              <span className="font-medium">{currentMessage || "处理中..."}</span>
              <span className="text-xs font-mono opacity-70">{formatDuration(elapsed)}</span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}