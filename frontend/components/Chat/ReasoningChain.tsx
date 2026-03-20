"use client";

import { useState } from "react";
import { ReasoningStep, MessageTag } from "@/app/page";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import AnalysisResultRenderer from "./AnalysisResultRenderer";

interface ReasoningChainProps {
  steps: ReasoningStep[];
  isStreaming?: boolean;
  analysisTags?: MessageTag[];
}

/** 按 round 字段分组，round 为 undefined 归入 key=0 */
function groupByRound(steps: ReasoningStep[]): Map<number, ReasoningStep[]> {
  const groups = new Map<number, ReasoningStep[]>();
  for (const step of steps) {
    const key = step.round ?? 0;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key)!.push(step);
  }
  return groups;
}

/** 从一组步骤中提取摘要预览（取第一个思考步骤的前 50 字） */
function getPreview(steps: ReasoningStep[]): string {
  const first = steps.find(s => s.step === "thought") ?? steps[0];
  if (!first) return "";
  const text = first.content.replace(/^【(思考|观察|行动)】/, "").trim();
  // 取第一行，截断
  const line = text.split("\n")[0];
  return line.length > 50 ? line.slice(0, 50) + "…" : line;
}

/** 单个步骤渲染 */
function StepItem({ step }: { step: ReasoningStep }) {
  const content = step.content.replace(/^【(思考|观察|行动)】/, "").trim();

  const labels: Record<string, { icon: string; label: string; borderClass: string }> = {
    thought:     { icon: "💭", label: "思考", borderClass: "border-border/40" },
    action:      { icon: "⚡", label: "行动", borderClass: "border-accent/30" },
    observation: { icon: "👁️", label: "观察", borderClass: "border-muted-foreground/20" },
  };
  const { icon, label, borderClass } = labels[step.step] ?? labels.thought;

  return (
    <div className={`pl-4 border-l-2 ${borderClass}`}>
      <div className="flex items-start gap-1.5 mb-1">
        <span className="text-xs font-medium opacity-50">{icon} {label}</span>
      </div>
      <div className={`text-sm leading-relaxed prose prose-sm max-w-none prose-p:my-1 prose-ul:my-1 prose-ol:my-1 ${step.step === "thought" ? "opacity-65 italic" : "opacity-80"}`}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
      </div>
    </div>
  );
}

/** 单轮折叠行 */
function RoundSection({ roundNum, steps, isStreaming }: {
  roundNum: number;
  steps: ReasoningStep[];
  isStreaming?: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const preview = getPreview(steps);

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className="group flex items-center gap-1.5 py-1 text-xs text-muted-foreground/50 hover:text-muted-foreground/80 transition-colors max-w-full"
      >
        <svg
          className={`w-3 h-3 shrink-0 transition-transform ${expanded ? "rotate-90" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        <span className="shrink-0">第 {roundNum} 轮</span>
        {!expanded && preview && (
          <span className="truncate opacity-60">{preview}</span>
        )}
        {isStreaming && (
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse shrink-0" />
        )}
      </button>

      {expanded && (
        <div className="ml-3 pl-3 border-l border-border/20 mt-1 mb-2 space-y-3">
          {steps.map((step, idx) => (
            <StepItem key={idx} step={step} />
          ))}
          {isStreaming && (
            <div className="flex items-center gap-2 text-xs opacity-40">
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
              <span>推理中...</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** 分析详情折叠行（放在推理链末尾） */
function AnalysisSection({ tag, label }: { tag: MessageTag; label: string }) {
  const [expanded, setExpanded] = useState(false);
  const isStreaming = tag.content?._streaming === true;

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className="group flex items-center gap-1.5 py-1 text-xs text-muted-foreground/50 hover:text-muted-foreground/80 transition-colors max-w-full"
      >
        <svg
          className={`w-3 h-3 shrink-0 transition-transform ${expanded ? "rotate-90" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        <span className="shrink-0">{label}</span>
        {isStreaming && (
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse shrink-0" />
        )}
      </button>

      {expanded && (
        <div className="ml-3 pl-3 border-l border-border/20 mt-1 mb-2">
          <AnalysisResultRenderer
            content={tag.content}
            title={tag.title}
            embedded
          />
        </div>
      )}
    </div>
  );
}

export default function ReasoningChain({ steps, isStreaming, analysisTags }: ReasoningChainProps) {
  if (!steps || steps.length === 0) return null;

  const groups = groupByRound(steps);
  const hasMultipleRounds = groups.size > 1 || (groups.size === 1 && !groups.has(0));
  const sortedRounds = Array.from(groups.keys()).sort((a, b) => a - b);

  // 按 round 分组 analysis tags
  const analysisByRound = new Map<number, MessageTag[]>();
  const unassigned: MessageTag[] = [];
  analysisTags?.forEach(tag => {
    const round = tag.metadata?.round;
    if (round != null && round > 0) {
      if (!analysisByRound.has(round)) analysisByRound.set(round, []);
      analysisByRound.get(round)!.push(tag);
    } else {
      unassigned.push(tag);
    }
  });

  // 渲染单个轮次 + 其后的分析详情
  const renderRound = (roundNum: number, roundKey: number, roundSteps: ReasoningStep[], isLast: boolean) => {
    const tagsForRound = analysisByRound.get(roundNum) || [];
    const showNum = tagsForRound.length > 1;
    return (
      <div key={roundKey}>
        <RoundSection
          roundNum={roundNum === 0 ? 1 : roundNum}
          steps={roundSteps}
          isStreaming={isStreaming && isLast}
        />
        {tagsForRound.map((tag, idx) => (
          <AnalysisSection
            key={`analysis-${roundNum}-${idx}`}
            tag={tag}
            label={showNum ? `📋 分析详情 ${idx + 1}` : "📋 分析详情"}
          />
        ))}
      </div>
    );
  };

  return (
    <div className="mb-3 space-y-0.5">
      {hasMultipleRounds ? (
        sortedRounds.map((roundNum, idx) =>
          renderRound(roundNum, roundNum, groups.get(roundNum)!, idx === sortedRounds.length - 1)
        )
      ) : (
        renderRound(sortedRounds[0] ?? 0, 0, steps, true)
      )}
      {/* 没有 round 信息的 analysis tags 兜底放末尾（历史消息兼容） */}
      {unassigned.map((tag, idx) => (
        <AnalysisSection
          key={`analysis-unassigned-${idx}`}
          tag={tag}
          label={unassigned.length > 1 ? `📋 分析详情 ${idx + 1}` : "📋 分析详情"}
        />
      ))}
    </div>
  );
}
