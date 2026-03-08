import { useState, useEffect } from "react";

interface LoadingSpinnerProps {
  stage?: string;
}

export default function LoadingSpinner({ stage }: LoadingSpinnerProps) {
  const [currentStage, setCurrentStage] = useState(0);

  // 默认阶段循环
  const stages = ["思考中", "计算中", "推荐中"];

  useEffect(() => {
    if (stage) return; // 如果有明确的 stage，不循环

    const interval = setInterval(() => {
      setCurrentStage((prev) => (prev + 1) % stages.length);
    }, 2000);

    return () => clearInterval(interval);
  }, [stage]);

  const displayText = stage || stages[currentStage];

  return (
    <div className="flex items-center justify-center py-4 gap-3">
      <div className="relative">
        {/* 外圈 */}
        <div className="w-8 h-8 border-4 border-gray-200 dark:border-gray-700 rounded-full"></div>
        {/* 内圈旋转动画 */}
        <div className="absolute top-0 left-0 w-8 h-8 border-4 border-transparent border-t-emerald-500 rounded-full animate-spin"></div>
      </div>
      <span className="text-sm text-gray-600 dark:text-gray-400 animate-pulse">
        {displayText}
      </span>
    </div>
  );
}
