export default function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center py-4">
      <div className="relative">
        {/* 外圈 */}
        <div className="w-8 h-8 border-4 border-gray-200 dark:border-gray-700 rounded-full"></div>
        {/* 内圈旋转动画 */}
        <div className="absolute top-0 left-0 w-8 h-8 border-4 border-transparent border-t-emerald-500 rounded-full animate-spin"></div>
      </div>
    </div>
  );
}
