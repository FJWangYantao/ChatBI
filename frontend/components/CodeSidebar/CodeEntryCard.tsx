"use client";

import { CodeEntry } from "@/types/code-sidebar";
import StreamingCodeBlock from "./StreamingCodeBlock";
import SqlResultTable from "./SqlResultTable";

interface CodeEntryCardProps {
  entry: CodeEntry;
  isActive: boolean;
}

export default function CodeEntryCard({ entry, isActive }: CodeEntryCardProps) {
  const handleCopy = () => {
    navigator.clipboard.writeText(entry.code);
  };

  const typeIcon = entry.type === "sql" ? "⚡" : entry.type === "python" ? "🐍" : "▶";
  const typeColor =
    entry.type === "sql"
      ? "text-accent"
      : entry.type === "python"
      ? "text-blue-400"
      : "text-purple-400";

  const timeStr = new Date(entry.timestamp).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  return (
    <div
      className={`glass-card rounded-2xl border p-4 space-y-3 transition-all duration-200 ${
        isActive
          ? "glow-border"
          : "border-border/50 hover:border-border"
      }`}
    >
      {/* 卡片头部 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className={`text-sm ${typeColor}`}>{typeIcon}</span>
          <span className="text-sm font-semibold font-display truncate max-w-[280px]">
            {entry.title}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground font-mono">{timeStr}</span>
          {entry.type !== "execution" && (
            <button
              onClick={handleCopy}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors px-1.5 py-0.5 rounded hover:bg-accent/10"
              title="复制代码"
            >
              复制
            </button>
          )}
        </div>
      </div>

      {/* 代码块 */}
      {entry.code && (entry.type === "sql" || entry.type === "python") && (
        <>
          <StreamingCodeBlock
            code={entry.code}
            language={entry.type}
            isStreaming={entry.isStreaming}
          />
          {/* SQL 执行结果表格 */}
          {entry.type === "sql" && entry.queryResult && (
            <div className="mt-3">
              <SqlResultTable queryResult={entry.queryResult} />
            </div>
          )}
        </>
      )}

      {/* execution 类型特有内容 */}
      {entry.type === "execution" && (
        <div className="space-y-2">
          {/* 执行状态 */}
          <div className="flex items-center gap-2">
            <span
              className={`text-xs font-medium ${
                entry.stage === "executing"
                  ? "text-blue-500"
                  : entry.success
                  ? "text-green-500"
                  : "text-red-500"
              }`}
            >
              {entry.stage === "executing"
                ? "执行中..."
                : entry.success
                ? "执行成功"
                : "执行失败"}
            </span>
            {entry.executionTime !== undefined && entry.executionTime > 0 && (
              <span className="text-xs text-muted-foreground font-mono">
                {entry.executionTime}ms
              </span>
            )}
          </div>

          {/* 代码 */}
          {entry.code && (
            <StreamingCodeBlock
              code={entry.code}
              language="python"
              isStreaming={entry.isStreaming}
            />
          )}

          {/* stdout */}
          {entry.stdout && (
            <div className="bg-muted/50 rounded-lg p-3 border border-border/30">
              <div className="text-xs text-muted-foreground mb-1 font-mono">输出：</div>
              <pre className="text-sm whitespace-pre-wrap font-mono max-h-[200px] overflow-y-auto">
                {entry.stdout}
              </pre>
            </div>
          )}

          {/* stderr */}
          {entry.stderr && (
            <div className="bg-red-50 dark:bg-red-950/20 rounded-lg p-3 border border-red-200 dark:border-red-800">
              <div className="text-xs text-red-600 dark:text-red-400 mb-1 font-mono">错误：</div>
              <pre className="text-sm text-red-700 dark:text-red-300 whitespace-pre-wrap font-mono max-h-[200px] overflow-y-auto">
                {entry.stderr}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
