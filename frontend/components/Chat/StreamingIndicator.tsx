interface StreamingIndicatorProps {
  stage?: string;
  message?: string;
  progress?: number;
  totalSteps?: number;
}

const stageIcons: Record<string, string> = {
  intent_detection: "🔍",
  prompt_enhancement: "✨",
  llm_generation: "💬",
  clarification: "❓",
  planning: "📋",
  code_execution: "⚙️",
  summary: "📝",
  suggestions: "💡",
  diagnostic: "🔬",
  report_generation: "📊",
};

export default function StreamingIndicator({ stage, message, progress, totalSteps }: StreamingIndicatorProps) {
  const icon = stage ? stageIcons[stage] || "⏳" : "⏳";
  const percent = totalSteps && totalSteps > 0 ? Math.round((progress || 0) / totalSteps * 100) : 0;

  return (
    <div className="flex flex-col gap-2 py-2">
      {/* 阶段文字 */}
      <div className="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
        <span>{icon}</span>
        <span>{message || "处理中..."}</span>
      </div>

      {/* 进度条 */}
      {totalSteps && totalSteps > 1 && (
        <div className="flex items-center gap-2">
          <div className="flex-1 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
            <div
              className="h-full bg-emerald-500 rounded-full transition-all duration-500 ease-out"
              style={{ width: `${percent}%` }}
            />
          </div>
          <span className="text-xs text-gray-500 dark:text-gray-500 tabular-nums min-w-[3ch] text-right">
            {progress}/{totalSteps}
          </span>
        </div>
      )}
    </div>
  );
}
