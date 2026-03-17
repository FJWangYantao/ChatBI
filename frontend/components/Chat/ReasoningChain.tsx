"use client";

import { useState } from "react";
import { ReasoningStep } from "@/app/page";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface ReasoningChainProps {
  steps: ReasoningStep[];
  isStreaming?: boolean;
}

export default function ReasoningChain({ steps, isStreaming }: ReasoningChainProps) {
  const [expanded, setExpanded] = useState(false);

  if (!steps || steps.length === 0) return null;

  const thoughtCount = steps.filter(s => s.step === "thought").length;
  const observationCount = steps.filter(s => s.step === "observation").length;
  const actionCount = steps.filter(s => s.step === "action").length;

  return (
    <div className="mb-4">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-2 text-sm opacity-70 hover:opacity-100 transition-opacity py-2 px-3 rounded-lg border border-border/40 hover:border-accent/30 hover:bg-accent/5"
      >
        <span>🧠</span>
        <span>思考过程</span>
        <span className="text-xs opacity-60">
          {thoughtCount} 个思考 · {observationCount} 个观察{actionCount > 0 ? ` · ${actionCount} 个行动` : ""}
        </span>
        {isStreaming && (
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
        )}
        <svg
          className={`w-4 h-4 ml-auto transition-transform ${expanded ? "rotate-180" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {expanded && (
        <div className="mt-3 space-y-3">
          {steps.map((s, idx) => {
            const content = s.content.replace(/^【(思考|观察|行动)】/, "").trim();

            if (s.step === "thought") {
              return (
                <div key={idx} className="pl-4 border-l-2 border-border/40">
                  <div className="flex items-start gap-2 mb-1.5">
                    <span className="text-sm">💭</span>
                    <span className="text-xs font-medium opacity-70">思考 #{idx + 1}</span>
                  </div>
                  <div className="text-sm opacity-70 leading-relaxed italic prose prose-sm max-w-none prose-p:my-1 prose-ul:my-1 prose-ol:my-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
                  </div>
                </div>
              );
            } else if (s.step === "action") {
              return (
                <div key={idx} className="rounded-lg bg-accent/5 border border-accent/20 p-3">
                  <div className="flex items-start gap-2 mb-1.5">
                    <span className="text-sm">⚡</span>
                    <span className="text-xs font-medium opacity-70">行动 #{idx + 1}</span>
                  </div>
                  <div className="text-sm opacity-85 leading-relaxed prose prose-sm max-w-none prose-p:my-1 prose-ul:my-1 prose-ol:my-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
                  </div>
                </div>
              );
            } else {
              return (
                <div key={idx} className="rounded-lg bg-muted/30 border border-border/40 p-3">
                  <div className="flex items-start gap-2 mb-1.5">
                    <span className="text-sm">👁️</span>
                    <span className="text-xs font-medium opacity-70">观察 #{idx + 1}</span>
                  </div>
                  <div className="text-sm opacity-85 leading-relaxed prose prose-sm max-w-none prose-p:my-1 prose-ul:my-1 prose-ol:my-1">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
                  </div>
                </div>
              );
            }
          })}
          {isStreaming && (
            <div className="flex items-center gap-2 text-xs opacity-50 pl-4">
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
              <span>推理中...</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
