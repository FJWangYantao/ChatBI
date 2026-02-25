import { IntentInfo } from "@/app/page";

interface IntentBadgeProps {
  intentInfo: IntentInfo;
}

/**
 * 意图标签组件
 * 显示意图识别结果，包括类别、子类型和置信度
 */
export default function IntentBadge({ intentInfo }: IntentBadgeProps) {
  // 根据意图类别选择颜色
  const getCategoryColor = (category: string) => {
    switch (category) {
      case "DATA_QUERY":
        return {
          bg: "bg-blue-50 dark:bg-blue-900/20",
          border: "border-blue-200 dark:border-blue-800",
          text: "text-blue-700 dark:text-blue-300",
          icon: "text-blue-500 dark:text-blue-400",
        };
      case "GENERAL_CHAT":
        return {
          bg: "bg-gray-50 dark:bg-gray-800/50",
          border: "border-gray-200 dark:border-gray-700",
          text: "text-gray-700 dark:text-gray-300",
          icon: "text-gray-500 dark:text-gray-400",
        };
      case "HYBRID":
        return {
          bg: "bg-purple-50 dark:bg-purple-900/20",
          border: "border-purple-200 dark:border-purple-800",
          text: "text-purple-700 dark:text-purple-300",
          icon: "text-purple-500 dark:text-purple-400",
        };
      case "DATA_OPERATION":
        return {
          bg: "bg-orange-50 dark:bg-orange-900/20",
          border: "border-orange-200 dark:border-orange-800",
          text: "text-orange-700 dark:text-orange-300",
          icon: "text-orange-500 dark:text-orange-400",
        };
      default:
        return {
          bg: "bg-gray-50 dark:bg-gray-800/50",
          border: "border-gray-200 dark:border-gray-700",
          text: "text-gray-700 dark:text-gray-300",
          icon: "text-gray-500 dark:text-gray-400",
        };
    }
  };

  // 获取意图图标
  const getCategoryIcon = (category: string) => {
    switch (category) {
      case "DATA_QUERY":
        return (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
          </svg>
        );
      case "GENERAL_CHAT":
        return (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
          </svg>
        );
      case "HYBRID":
        return (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        );
      case "DATA_OPERATION":
        return (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
          </svg>
        );
      default:
        return (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        );
    }
  };

  const colors = getCategoryColor(intentInfo.category);
  const confidencePercent = Math.round(intentInfo.categoryConfidence * 100);

  return (
    <div className={`inline-flex items-center gap-2 px-2.5 py-1 rounded-md border ${colors.bg} ${colors.border} text-xs font-medium`}>
      {/* 意图图标 */}
      <span className={colors.icon}>
        {getCategoryIcon(intentInfo.category)}
      </span>

      {/* 意图类别 */}
      <span className={colors.text}>
        {intentInfo.categoryCn}
      </span>

      {/* 分隔符 */}
      <span className="text-gray-300 dark:text-gray-600">·</span>

      {/* 子类型 */}
      <span className={`${colors.text} opacity-80`}>
        {intentInfo.subtypeCn}
      </span>

      {/* 置信度 */}
      <span className={`${colors.text} opacity-60 font-mono`}>
        {confidencePercent}%
      </span>
    </div>
  );
}
