"use client";

import { useRef, useEffect } from "react";

interface StreamingCodeBlockProps {
  code: string;
  language: "sql" | "python";
  isStreaming: boolean;
}

export default function StreamingCodeBlock({
  code,
  language,
  isStreaming,
}: StreamingCodeBlockProps) {
  const preRef = useRef<HTMLPreElement>(null);
  const rafRef = useRef<number | null>(null);

  // rAF 节流滚动：避免每次 code 变化都触发 reflow，只在 rAF 回调中同步
  useEffect(() => {
    if (isStreaming && preRef.current) {
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
      }
      rafRef.current = requestAnimationFrame(() => {
        if (preRef.current) {
          preRef.current.scrollTop = preRef.current.scrollHeight;
        }
        rafRef.current = null;
      });
    }
    return () => {
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [code, isStreaming]);

  return (
    <pre ref={preRef} className="text-sm overflow-x-auto font-mono leading-relaxed">
      <code>{code}</code>
      {isStreaming && (
        <span className="inline-block w-2 h-4 bg-accent animate-pulse ml-0.5 align-middle" />
      )}
    </pre>
  );
}
