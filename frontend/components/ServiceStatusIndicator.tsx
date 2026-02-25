"use client";

import { useState, useEffect, useCallback } from "react";
import { checkServicesHealth, ServicesHealth } from "@/lib/api/health";

const SERVICE_LABELS: Record<string, string> = {
  backend: "后端",
  intent: "意图识别",
  ner: "实体识别",
  sandbox: "沙箱",
};

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 30000;

export default function ServiceStatusIndicator() {
  const [health, setHealth] = useState<ServicesHealth | null>(null);
  const [expanded, setExpanded] = useState(false);

  const refresh = useCallback(async () => {
    const result = await checkServicesHealth();
    setHealth(result);
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_INTERVAL);
    return () => clearInterval(timer);
  }, [refresh]);

  if (!health) return null;

  const entries = Object.entries(health) as [string, { status: string; port: number; message?: string }][];
  const allOk = entries.every(([, v]) => v.status === "ok");
  const okCount = entries.filter(([, v]) => v.status === "ok").length;

  return (
    <div className="space-y-2">
      {/* 汇总行 */}
      <button
        onClick={() => setExpanded((v) => !v)}
        className="w-full flex items-center justify-between text-xs text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors"
      >
        <span className="flex items-center gap-1.5">
          <span
            className={`inline-block w-2 h-2 rounded-full ${
              allOk ? "bg-emerald-500" : "bg-amber-500"
            }`}
          />
          服务状态 {okCount}/{entries.length}
        </span>
        <span className="text-[10px]">{expanded ? "▲" : "▼"}</span>
      </button>

      {/* 展开详情 */}
      {expanded && (
        <div className="space-y-1.5 pt-1">
          {entries.map(([key, svc]) => (
            <div
              key={key}
              className="flex items-center justify-between text-xs"
              title={svc.status === "error" ? svc.message : undefined}
            >
              <span className="flex items-center gap-1.5 text-gray-600 dark:text-gray-400">
                <span
                  className={`inline-block w-1.5 h-1.5 rounded-full ${
                    svc.status === "ok" ? "bg-emerald-500" : "bg-red-500"
                  }`}
                />
                {SERVICE_LABELS[key] || key}
              </span>
              <span
                className={
                  svc.status === "ok"
                    ? "text-emerald-600 dark:text-emerald-400"
                    : "text-red-500 dark:text-red-400"
                }
              >
                {svc.status === "ok" ? "正常" : "离线"}
              </span>
            </div>
          ))}
          {/* 手动刷新 */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              refresh();
            }}
            className="w-full text-[10px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors pt-1"
          >
            刷新状态
          </button>
        </div>
      )}
    </div>
  );
}
