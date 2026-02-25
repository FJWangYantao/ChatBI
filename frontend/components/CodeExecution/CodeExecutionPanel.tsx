// frontend/components/CodeExecution/CodeExecutionPanel.tsx
import { CodeExecution } from "@/types/code-execution";

interface CodeExecutionPanelProps {
  executions: CodeExecution[];
  isOpen: boolean;
  onClose: () => void;
}

export default function CodeExecutionPanel({
  executions,
  isOpen,
  onClose
}: CodeExecutionPanelProps) {
  if (!isOpen) return null;

  return (
    <div className="fixed right-0 top-0 h-full w-[600px] bg-background border-l border-border shadow-2xl z-50 flex flex-col">
      {/* 标题栏 */}
      <div className="flex items-center justify-between p-4 border-b border-border bg-gradient-to-r from-muted/50 to-background">
        <h3 className="font-semibold font-display text-lg">代码执行详情</h3>
        <button
          onClick={onClose}
          className="px-3 py-1 text-sm rounded-lg hover:bg-accent/10 transition-colors"
        >
          关闭
        </button>
      </div>

      {/* 执行历史列表 */}
      <div className="overflow-y-auto flex-1 p-4 space-y-4">
        {executions.length === 0 && (
          <div className="text-center text-muted-foreground py-10">
            暂无代码执行记录
          </div>
        )}

        {executions.map((exec, index) => (
          <div
            key={exec.executionId}
            className="glass-card border border-border/50 rounded-2xl p-4 space-y-3"
          >
            {/* 执行头部 */}
            <div className="flex items-center justify-between">
              <span className="font-medium font-display">
                第 {index + 1} 次执行
              </span>
              <span
                className={`text-sm font-medium ${
                  exec.stage === "executing"
                    ? "text-blue-500"
                    : exec.success
                    ? "text-green-500"
                    : "text-red-500"
                }`}
              >
                {exec.stage === "executing"
                  ? "执行中..."
                  : exec.success
                  ? "✓ 成功"
                  : "✗ 失败"}
              </span>
            </div>

            {/* 代码块 */}
            {exec.code && (
              <div className="bg-muted/50 rounded-lg p-3 border border-border/30">
                <div className="text-xs text-muted-foreground mb-2 font-mono">
                  Python 代码：
                </div>
                <pre className="text-sm overflow-x-auto max-h-[300px] overflow-y-auto font-mono">
                  <code>{exec.code}</code>
                </pre>
              </div>
            )}

            {/* 输出 */}
            {exec.stdout && (
              <div className="bg-muted/50 rounded-lg p-3 border border-border/30">
                <div className="text-xs text-muted-foreground mb-2 font-mono">
                  输出：
                </div>
                <pre className="text-sm whitespace-pre-wrap font-mono">
                  {exec.stdout}
                </pre>
              </div>
            )}

            {/* 错误 */}
            {exec.stderr && (
              <div className="bg-red-50 dark:bg-red-950/20 rounded-lg p-3 border border-red-200 dark:border-red-800">
                <div className="text-xs text-red-600 dark:text-red-400 mb-2 font-mono">
                  错误：
                </div>
                <pre className="text-sm text-red-700 dark:text-red-300 whitespace-pre-wrap font-mono">
                  {exec.stderr}
                </pre>
              </div>
            )}

            {/* 执行时间 */}
            {exec.executionTime !== undefined && exec.executionTime > 0 && (
              <div className="text-xs text-muted-foreground font-mono">
                执行耗时：{exec.executionTime}ms
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
