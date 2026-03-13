import { useState } from "react";
import { MessageTag } from "@/app/page";
import { executeSql } from "@/lib/api/chat";

interface EditableSqlBlockProps {
  tag: MessageTag;
  onExecuteResult: (resultTag: MessageTag) => void;
}

export default function EditableSqlBlock({ tag, onExecuteResult }: EditableSqlBlockProps) {
  const [sql, setSql] = useState(tag.content.sql || "");
  const [isExecuting, setIsExecuting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleExecute = async () => {
    setIsExecuting(true);
    setError(null);

    try {
      console.log('[EditableSqlBlock] 开始执行 SQL:', sql.substring(0, 100));
      const response = await executeSql(sql);
      console.log('[EditableSqlBlock] 收到响应:', response);

      if (response.tags && response.tags.length > 0) {
        console.log('[EditableSqlBlock] 找到结果 tag:', response.tags[0]);
        // 将结果 tag 传递给父组件
        onExecuteResult(response.tags[0]);
      } else {
        console.warn('[EditableSqlBlock] 响应中没有 tags');
        setError('执行成功但没有返回结果');
      }
    } catch (err: any) {
      console.error('[EditableSqlBlock] 执行失败:', err);
      setError(err.message || "执行失败");
    } finally {
      setIsExecuting(false);
    }
  };

  return (
    <div className="glass-card border border-border/50 rounded-2xl overflow-hidden">
      {/* 标题栏 */}
      <div className="border-b border-border/50 px-5 py-3 bg-gradient-to-r from-muted/50 to-background">
        <span className="text-sm font-semibold font-display flex items-center gap-2">
          <span className="text-accent">🔍</span>
          {tag.title || "生成的 SQL"}
        </span>
      </div>

      {/* SQL 编辑区 */}
      <div className="p-4">
        <textarea
          value={sql}
          onChange={(e) => setSql(e.target.value)}
          className="w-full h-32 p-3 font-mono text-sm bg-muted/30 border border-border/30 rounded-xl focus:outline-none focus:border-accent/50 resize-none"
          placeholder="SELECT * FROM ..."
        />
      </div>

      {/* 操作按钮 */}
      <div className="border-t border-border/50 px-5 py-3 flex items-center justify-between bg-gradient-to-r from-muted/30 to-background">
        <div className="text-xs opacity-70">
          {tag.content.query && `查询：${tag.content.query}`}
        </div>
        <button
          onClick={handleExecute}
          disabled={isExecuting || !sql.trim()}
          className={`px-4 py-2 text-sm font-medium rounded-xl transition-all duration-200 ${
            isExecuting || !sql.trim()
              ? "glass-card border border-border/30 opacity-30 cursor-not-allowed"
              : "glass-card border border-accent/50 hover:bg-accent/10 hover:border-accent"
          }`}
        >
          {isExecuting ? "执行中..." : "执行 SQL"}
        </button>
      </div>

      {/* 错误提示 */}
      {error && (
        <div className="px-5 py-3 bg-red-500/10 border-t border-red-500/30 text-sm text-red-400">
          {error}
        </div>
      )}
    </div>
  );
}
