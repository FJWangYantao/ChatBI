"use client";

import { useState, useEffect, useRef } from "react";

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
  const [copied, setCopied] = useState(false);
  const preRef = useRef<HTMLPreElement>(null);

  // 当代码内容变化且正在流式传输时，自动滚动到底部
  useEffect(() => {
    if (isStreaming && preRef.current) {
      preRef.current.scrollTop = preRef.current.scrollHeight;
    }
  }, [code, isStreaming]);

  const handleCopy = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const langColor = language === "sql" ? "text-accent" : "text-blue-400";
  const langLabel = language === "sql" ? "SQL" : "Python";

  return (
    <div className="relative rounded-lg bg-muted/50 border border-border/30 overflow-hidden">
      {/* 头部：语言标签 + 复制 */}
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-border/20">
        <span className={`text-xs font-semibold font-mono ${langColor}`}>
          {langLabel}
        </span>
        <button
          onClick={handleCopy}
          className="text-xs text-muted-foreground hover:text-foreground transition-colors px-2 py-0.5 rounded hover:bg-accent/10"
          title="复制代码"
        >
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      {/* 代码区：直接渲染真实流式内容 */}
      <pre ref={preRef} className="p-3 text-sm overflow-x-auto max-h-[400px] overflow-y-auto font-mono leading-relaxed">
        <code>{code}</code>
        {isStreaming && (
          <span className="inline-block w-2 h-4 bg-accent animate-pulse ml-0.5 align-middle" />
        )}
      </pre>
    </div>
  );
}
