"use client";

import { useState, useEffect, useRef } from "react";
import { SubtaskInfo } from "@/app/page";

interface SubtaskPanelProps {
  subtasks: SubtaskInfo[];
  isStreaming?: boolean;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/** 单个子任务行内实时计时器 */
function LiveTimer({ startTime }: { startTime: number }) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setElapsed(Date.now() - startTime), 200);
    return () => clearInterval(t);
  }, [startTime]);
  return <span>{formatDuration(elapsed)}</span>;
}

/** 状态对应的样式 / 文案 */
function StatusIndicator({ status }: { status: SubtaskInfo["status"] }) {
  switch (status) {
    case "pending":
      return (
        <span className="flex items-center gap-1 text-xs font-mono opacity-50">
          <span className="inline-block w-1.5 h-1.5 rounded-full bg-current opacity-40" />
          等待中
        </span>
      );
    case "generating":
      return (
        <span className="flex items-center gap-1.5 text-xs font-mono text-blue-500">
          {/* 旋转 spinner */}
          <svg className="w-3.5 h-3.5 animate-spin" viewBox="0 0 16 16" fill="none">
            <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeWidth="2" opacity="0.25" />
            <path d="M14.5 8a6.5 6.5 0 00-6.5-6.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          生成代码
        </span>
      );
    case "executing":
      return (
        <span className="flex items-center gap-1.5 text-xs font-mono text-amber-500">
          {/* 跳动的点 */}
          <span className="flex gap-0.5">
            <span className="w-1 h-1 rounded-full bg-current animate-bounce" style={{ animationDelay: "0ms" }} />
            <span className="w-1 h-1 rounded-full bg-current animate-bounce" style={{ animationDelay: "150ms" }} />
            <span className="w-1 h-1 rounded-full bg-current animate-bounce" style={{ animationDelay: "300ms" }} />
          </span>
          执行中
        </span>
      );
    case "completed":
      return (
        <span className="flex items-center gap-1 text-xs font-mono text-emerald-500">
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
          </svg>
          完成
        </span>
      );
    case "failed":
      return (
        <span className="flex items-center gap-1 text-xs font-mono text-red-500">
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M6 18L18 6M6 6l12 12" />
          </svg>
          失败
        </span>
      );
  }
}

export default function SubtaskPanel({ subtasks, isStreaming }: SubtaskPanelProps) {
  const [collapsed, setCollapsed] = useState(true); // 默认折叠
  const wasStreamingRef = useRef(false);
  // 记录每个子任务进入 active 状态的时间，用于实时计时
  const activeStartRef = useRef<Record<number, number>>({});

  const completedCount = subtasks.filter(s => s.status === "completed").length;
  const failedCount = subtasks.filter(s => s.status === "failed").length;
  const finishedCount = completedCount + failedCount;
  const total = subtasks.length;
  const allDone = finishedCount === total;
  const progressPct = total > 0 ? (finishedCount / total) * 100 : 0;

  // 追踪 active 开始时间
  useEffect(() => {
    subtasks.forEach((t) => {
      const isActive = t.status === "generating" || t.status === "executing";
      if (isActive && !activeStartRef.current[t.index]) {
        activeStartRef.current[t.index] = Date.now();
      }
      if (!isActive) {
        delete activeStartRef.current[t.index];
      }
    });
  }, [subtasks]);

  // 流式中自动展开，流式结束后 1.5s 自动折叠
  useEffect(() => {
    if (isStreaming) {
      setCollapsed(false);
    }
    if (wasStreamingRef.current && !isStreaming) {
      const timer = setTimeout(() => setCollapsed(true), 1500);
      return () => clearTimeout(timer);
    }
    wasStreamingRef.current = !!isStreaming;
  }, [isStreaming]);

  // 折叠态
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
          并行子任务 · {completedCount}/{total} 完成
          {failedCount > 0 && ` · ${failedCount} 失败`}
        </span>
        <span className="text-accent opacity-0 group-hover:opacity-100 transition-opacity">展开</span>
      </button>
    );
  }

  return (
    <div className="py-2 mb-3 animate-fade-in-up">
      {/* 标题 + 进度条 */}
      <div className="flex items-center justify-between mb-1.5">
        <div className="flex items-center gap-2 text-sm font-medium">
          <span className={!allDone ? "animate-pulse" : ""}>⚡</span>
          <span>并行子任务</span>
          <span className="text-xs font-mono opacity-60">
            {completedCount}/{total}
          </span>
        </div>
        {!isStreaming && (
          <button
            onClick={() => setCollapsed(true)}
            className="flex items-center gap-1.5 text-xs opacity-50 hover:opacity-100 transition-opacity"
          >
            <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
            折叠
          </button>
        )}
      </div>

      {/* 总进度条 */}
      <div className="h-1 rounded-full bg-border/30 mb-3 overflow-hidden">
        <div
          className="h-full rounded-full transition-all duration-500 ease-out"
          style={{
            width: `${progressPct}%`,
            background: failedCount > 0
              ? "linear-gradient(90deg, var(--color-accent), #ef4444)"
              : "linear-gradient(90deg, var(--color-accent), #10b981)",
          }}
        />
      </div>

      {/* 子任务列表 */}
      <div className="space-y-0.5">
        {subtasks.map((task, i) => {
          const isActive = task.status === "generating" || task.status === "executing";
          const isDone = task.status === "completed" || task.status === "failed";
          return (
            <div
              key={task.index}
              className={`flex items-center gap-3 text-sm py-1.5 px-2.5 rounded-xl transition-all duration-300 ${
                isActive
                  ? "bg-accent/8 border border-accent/20"
                  : isDone
                    ? "opacity-70"
                    : "opacity-50"
              }`}
              style={{ animationDelay: `${i * 60}ms` }}
            >
              {/* 左侧：序号圆点 */}
              <div className={`flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold transition-all duration-300 ${
                task.status === "completed"
                  ? "bg-emerald-500/20 text-emerald-500"
                  : task.status === "failed"
                    ? "bg-red-500/20 text-red-500"
                    : isActive
                      ? "bg-accent/20 text-accent ring-2 ring-accent/30 ring-offset-1 ring-offset-background"
                      : "bg-border/30 text-foreground/40"
              }`}>
                {task.status === "completed" ? (
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                  </svg>
                ) : task.status === "failed" ? (
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                ) : (
                  <span>{i + 1}</span>
                )}
              </div>

              {/* 标题 */}
              <span className="flex-1 truncate">{task.title}</span>

              {/* 右侧：状态 + 耗时 */}
              <div className="flex items-center gap-2 flex-shrink-0">
                {isActive && activeStartRef.current[task.index] && (
                  <span className="text-xs font-mono opacity-50">
                    <LiveTimer startTime={activeStartRef.current[task.index]} />
                  </span>
                )}
                {isDone && task.duration != null && (
                  <span className="text-xs font-mono opacity-50">
                    {formatDuration(task.duration)}
                  </span>
                )}
                <StatusIndicator status={task.status} />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
