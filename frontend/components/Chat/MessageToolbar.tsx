import { useState } from "react";
import { Message } from "@/app/page";

interface MessageToolbarProps {
  message: Message;
  onCopy: () => void;
  onEdit?: () => void;
  onRegenerate?: () => void;
  onFeedback?: (feedback: 'like' | 'dislike' | null) => void;
}

export default function MessageToolbar({
  message,
  onCopy,
  onEdit,
  onRegenerate,
  onFeedback,
}: MessageToolbarProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    onCopy();
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleFeedback = (type: 'like' | 'dislike') => {
    if (!onFeedback) return;
    onFeedback(message.feedback === type ? null : type);
  };

  const isUser = message.role === "user";

  return (
    <div className="flex items-center gap-1 mt-1.5">
      {/* 用户消息：编辑 + 复制 */}
      {isUser && (
        <>
          {onEdit && (
            <ToolbarButton onClick={onEdit} title="编辑">
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
              </svg>
            </ToolbarButton>
          )}
          <ToolbarButton onClick={handleCopy} title={copied ? "已复制" : "复制"}>
            {copied ? (
              <svg className="w-3.5 h-3.5 text-green-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            ) : (
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
            )}
          </ToolbarButton>
        </>
      )}

      {/* AI 消息：复制 + 重新生成 + 点赞 + 点踩 */}
      {!isUser && (
        <>
          <ToolbarButton onClick={handleCopy} title={copied ? "已复制" : "复制"}>
            {copied ? (
              <svg className="w-3.5 h-3.5 text-green-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            ) : (
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
            )}
          </ToolbarButton>
          {onRegenerate && (
            <ToolbarButton onClick={onRegenerate} title="重新生成">
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
            </ToolbarButton>
          )}
          {onFeedback && (
            <>
              <ToolbarButton
                onClick={() => handleFeedback('like')}
                title="点赞"
                active={message.feedback === 'like'}
              >
                <svg className="w-3.5 h-3.5" fill={message.feedback === 'like' ? "currentColor" : "none"} stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 9V5a3 3 0 00-3-3l-4 9v11h11.28a2 2 0 002-1.7l1.38-9a2 2 0 00-2-2.3H14zm-9 11H3a1 1 0 01-1-1v-7a1 1 0 011-1h2" />
                </svg>
              </ToolbarButton>
              <ToolbarButton
                onClick={() => handleFeedback('dislike')}
                title="点踩"
                active={message.feedback === 'dislike'}
              >
                <svg className="w-3.5 h-3.5" fill={message.feedback === 'dislike' ? "currentColor" : "none"} stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 15v4a3 3 0 003 3l4-9V2H5.72a2 2 0 00-2 1.7l-1.38 9a2 2 0 002 2.3H10zm9-13h2a1 1 0 011 1v7a1 1 0 01-1 1h-2" />
                </svg>
              </ToolbarButton>
            </>
          )}
        </>
      )}
    </div>
  );
}

function ToolbarButton({
  onClick,
  title,
  active,
  children,
}: {
  onClick: () => void;
  title: string;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`p-1.5 rounded-lg transition-all duration-200 ${
        active
          ? "text-accent bg-accent/15"
          : "text-muted-foreground/50 hover:text-foreground hover:bg-muted/50"
      }`}
    >
      {children}
    </button>
  );
}
