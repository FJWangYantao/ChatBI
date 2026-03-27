"use client";

import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { CodeEntry } from "@/types/code-sidebar";
import CodeEntryCard from "./CodeEntryCard";

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
  const [splitRatio, setSplitRatio] = useState(50);
  const [sidebarWidth, setSidebarWidth] = useState(480);
  const isDraggingRef = useRef<"split" | "width" | null>(null);
  const sidebarRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const topPanelRef = useRef<HTMLDivElement>(null);
  const bottomPanelRef = useRef<HTMLDivElement>(null);
  const splitRectRef = useRef<{ top: number; height: number } | null>(null);
  const sidebarStartX = useRef(0);
  const sidebarStartWidth = useRef(0);
  // 拖拽期间的实时值（不触发 React 渲染）
  const liveWidth = useRef(480);
  const liveRatio = useRef(50);

  const handleDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDraggingRef.current = "split";
    if (sidebarRef.current) {
      const rect = sidebarRef.current.getBoundingClientRect();
      splitRectRef.current = { top: rect.top, height: rect.height };
    }
    document.body.style.cursor = "ns-resize";
    document.body.style.userSelect = "none";
  }, []);

  const handleWidthMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDraggingRef.current = "width";
    sidebarStartX.current = e.clientX;
    sidebarStartWidth.current = liveWidth.current;
    document.body.style.cursor = "ew-resize";
    document.body.style.userSelect = "none";
  }, []);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDraggingRef.current === "split" && sidebarRef.current) {
        const rect = splitRectRef.current;
        if (!rect || rect.height <= 0) return;
        const y = e.clientY - rect.top;
        const ratio = Math.min(Math.max((y / rect.height) * 100, 20), 80);
        liveRatio.current = ratio;
        if (topPanelRef.current) topPanelRef.current.style.height = `${ratio}%`;
        if (bottomPanelRef.current) bottomPanelRef.current.style.height = `${100 - ratio}%`;
      } else if (isDraggingRef.current === "width") {
        const delta = sidebarStartX.current - e.clientX;
        const w = Math.min(Math.max(sidebarStartWidth.current + delta, 320), 800);
        liveWidth.current = w;
        if (containerRef.current) containerRef.current.style.width = `${w}px`;
      }
    };

    const handleMouseUp = () => {
      if (isDraggingRef.current) {
        splitRectRef.current = null;
        setSidebarWidth(liveWidth.current);
        setSplitRatio(liveRatio.current);
        isDraggingRef.current = null;
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
      }
    };

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
    };
  }, []);

  // 数据分流：useMemo 缓存，避免每次渲染都重新 filter
  const pythonEntries = useMemo(() =>
    entries.filter((e) => e.type === "python" || e.type === "execution"),
    [entries]
  );
  const sqlEntries = useMemo(() =>
    entries.filter((e) => e.type === "sql"),
    [entries]
  );

  if (!isOpen) return null;

  return (
    <div
      ref={containerRef}
      className="hidden lg:flex relative flex-col flex-shrink-0 bg-background/95"
      style={{ width: sidebarWidth }}
    >
      {/* 左侧可拖拽调整宽度 */}
      <div
        onMouseDown={handleWidthMouseDown}
        className="absolute left-0 top-0 bottom-0 cursor-ew-resize z-10 flex items-center justify-center"
        style={{ width: "12px" }}
      >
        <div className="h-full w-px bg-border/50 hover:bg-border transition-colors" />
      </div>

      {/* 上下分割区域 */}
      <div ref={sidebarRef} className="flex-1 flex flex-col overflow-hidden">
        {/* Python 区域 */}
        <div
          ref={topPanelRef}
          className="overflow-y-auto p-4 divide-y divide-border/20 scrollbar-thin"
          style={{ height: `${splitRatio}%` }}
        >
          {pythonEntries.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
              <span className="text-sm">暂无 Python 代码记录</span>
            </div>
          ) : (
            pythonEntries.map((entry) => (
              <CodeEntryCard
                key={entry.id}
                entry={entry}
                isActive={entry.id === activeEntryId}
              />
            ))
          )}
        </div>

        {/* 可拖拽分割线 */}
        <div
          onMouseDown={handleDividerMouseDown}
          className="group relative flex-shrink-0 cursor-ns-resize flex items-center justify-center"
          style={{ height: "12px" }}
        >
          <div className="h-px w-full bg-border/50 group-hover:bg-border transition-colors" />
        </div>

        {/* SQL 区域 */}
        <div
          ref={bottomPanelRef}
          className="overflow-y-auto p-4 divide-y divide-border/20 scrollbar-thin flex-1"
          style={{ height: `${100 - splitRatio}%` }}
        >
          {sqlEntries.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
              <span className="text-sm">暂无 SQL 查询记录</span>
            </div>
          ) : (
            sqlEntries.map((entry) => (
              <CodeEntryCard
                key={entry.id}
                entry={entry}
                isActive={entry.id === activeEntryId}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
}
