"use client";

import { useState } from "react";
import { ReasoningStep } from "@/app/page";

interface ReasoningChainProps {
  steps: ReasoningStep[];
  isStreaming?: boolean;
}

export default function ReasoningChain({ steps, isStreaming }: ReasoningChainProps) {
  const [expanded, setExpanded] = useState(false); // 默认折叠

  if (!steps || steps.length === 0) return null;

  return (
    <div className="mb-3">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-2 text-xs font-medium opacity-70 hover:opacity-100 transition-opacity duration-200 py-1.5 px-3 rounded-lg glass-card border border-border/50 hover:border-accent/20"
      >
        <span className="text-sm">🧠</span>
        <span>思考过程 ({steps.length} 步)</span>
        {isStreaming && (
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
        )}
        <svg
          className={`w-3.5 h-3.5 transition-transform duration-200 ${expanded ? "rotate-180" : ""}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {expanded && (
        <div className="mt-2 ml-1 border-l-2 border-accent/30 pl-4 space-y-3">
          {steps.map((s, idx) => (
            <div key={idx} className="text-sm">
              <div className="flex items-center gap-1.5 mb-1">
                <span className="text-xs">
                  {s.step === "thought" ? "💭" : "👁️"}
                </span>
                <span className="text-xs font-semibold opacity-80">
                  {s.step === "thought" ? "思考" : "观察"}
                </span>
              </div>
              <p className="text-sm opacity-80 leading-relaxed whitespace-pre-wrap">
                {s.content}
              </p>
            </div>
          ))}
          {isStreaming && (
            <div className="flex items-center gap-2 text-xs opacity-50">
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
              <span>推理中...</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
