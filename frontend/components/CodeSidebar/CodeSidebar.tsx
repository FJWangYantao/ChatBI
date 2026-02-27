"use client";

import { useState, useEffect, useRef } from "react";
import { CodeEntry } from "@/types/code-sidebar";
import CodeEntryCard from "./CodeEntryCard";

type FilterType = "all" | "sql" | "python" | "execution";

interface CodeSidebarProps {
  entries: CodeEntry[];
  isOpen: boolean;
  onToggle: () => void;
  activeEntryId: string | null;
}

export default function CodeSidebar({
  entries,
  isOpen,
  onToggle,
  activeEntryId,
}: CodeSidebarProps) {
  const [filter, setFilter] = useState<FilterType>("all");
  const listRef = useRef<HTMLDivElement>(null);
  const prevEntriesLenRef = useRef(entries.length);

  // 新条目时自动滚到底部
  useEffect(() => {
    if (entries.length > prevEntriesLenRef.current && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
    prevEntriesLenRef.current = entries.length;
  }, [entries.length]);

  const filteredEntries =
    filter === "all" ? entries : entries.filter((e) => e.type === filter);

  const filterTabs: { key: FilterType; label: string }[] = [
    { key: "all", label: "全部" },
    { key: "sql", label: "SQL" },
    { key: "python", label: "Python" },
    { key: "execution", label: "执行" },
  ];

  if (!isOpen) return null;

  return (
    <div className="hidden lg:flex flex-col w-[480px] flex-shrink-0 border-l border-border/50 bg-background/50 backdrop-blur-sm">
      {/* 头部 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border/50">
        <div className="flex items-center gap-2">
          <span className="font-semibold font-display">代码</span>
          <span className="text-xs text-muted-foreground bg-muted/50 px-2 py-0.5 rounded-full">
            {entries.length}
          </span>
        </div>
        <button
          onClick={onToggle}
          className="p-1.5 rounded-lg hover:bg-accent/10 transition-colors text-muted-foreground hover:text-foreground"
          title="关闭代码面板"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* 筛选栏 */}
      <div className="flex gap-1 px-4 py-2 border-b border-border/30">
        {filterTabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setFilter(tab.key)}
            className={`px-3 py-1 text-xs font-medium rounded-lg transition-all duration-200 ${
              filter === tab.key
                ? "bg-accent/20 text-accent border border-accent/30"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50 border border-transparent"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* 列表区 */}
      <div ref={listRef} className="flex-1 overflow-y-auto p-4 space-y-3 scrollbar-thin">
        {filteredEntries.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
            <svg className="w-12 h-12 mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
            </svg>
            <span className="text-sm">暂无代码记录</span>
          </div>
        ) : (
          filteredEntries.map((entry) => (
            <CodeEntryCard
              key={entry.id}
              entry={entry}
              isActive={entry.id === activeEntryId}
            />
          ))
        )}
      </div>
    </div>
  );
}
