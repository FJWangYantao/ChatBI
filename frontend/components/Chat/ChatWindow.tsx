import { Message, MessageTag } from "@/app/page";
import { useState, useEffect, useRef, useMemo } from "react";
import LoadingSpinner from "./LoadingSpinner";
import StepTimeline from "./StepTimeline";
import SubtaskPanel from "./SubtaskPanel";
import ReasoningChain from "./ReasoningChain";
import { AutoChart } from "@/components/Charts";
import IntentBadge from "./IntentBadge";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { executeSql, fetchPagedData } from "@/lib/api/chat";
import AnalysisResultRenderer from "./AnalysisResultRenderer";
import MessageToolbar from "./MessageToolbar";
import EditableSqlBlock from "./EditableSqlBlock";

interface ChatWindowProps {
  messages: Message[];
  isSending?: boolean;
  onUpdateMessage?: (messageId: string, newTags: MessageTag[]) => void;
  onSendMessage?: (content: string) => void;
  onEditAndResend?: (messageId: string, newContent: string) => void;
  onRegenerateMessage?: (messageId: string) => void;
  onFeedback?: (messageId: string, feedback: 'like' | 'dislike' | null) => void;
}

function PaginatedTable({ tag }: { tag: MessageTag }) {
  const tableData = tag.content;
  const [currentPage, setCurrentPage] = useState(1);
  const [remoteRows, setRemoteRows] = useState<Record<string, any>[] | null>(null);
  const [loading, setLoading] = useState(false);
  const pageSize = 10;

  // 预览数据能覆盖的页数（SSE 推送的前 50 行）
  const previewRows = tableData.rows?.length || 0;
  const previewPages = Math.ceil(previewRows / pageSize);
  const totalRows = tableData.totalRows || previewRows;
  const totalPages = Math.ceil(totalRows / pageSize);
  const hasDataRef = !!tableData.dataRefId;

  // 当翻页超出预览范围时，从服务端获取数据
  useEffect(() => {
    if (!hasDataRef || currentPage <= previewPages) {
      setRemoteRows(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    const offset = (currentPage - 1) * pageSize;
    fetchPagedData(tableData.dataRefId, offset, pageSize)
      .then((res) => {
        if (!cancelled && res.success) {
          setRemoteRows(res.rows);
        }
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [currentPage, hasDataRef, previewPages, tableData.dataRefId]);

  const getCurrentPageData = () => {
    if (hasDataRef && currentPage > previewPages && remoteRows) {
      return remoteRows;
    }
    if (!tableData.rows) return [];
    const startIndex = (currentPage - 1) * pageSize;
    const endIndex = startIndex + pageSize;
    return tableData.rows.slice(startIndex, endIndex);
  };

  return (
    <div className="glass-card border border-border/50 rounded-2xl overflow-hidden hover:border-accent/30 transition-border-color duration-200">
      <div className="border-b border-border/50 px-5 py-3 bg-gradient-to-r from-muted/50 to-background">
        <span className="text-sm font-semibold font-display flex items-center gap-2">
          <span className="text-accent">📊</span>
          {tag.title || "查询结果"}
        </span>
        {!!tableData.executionTime && (
          <span className="ml-2 text-xs opacity-60 font-mono">
            ({tableData.executionTime}ms)
          </span>
        )}
      </div>

      <div className="max-w-[500px] overflow-x-auto scrollbar-thin">
        <table className="min-w-full divide-y divide-border/30">
          <thead className="bg-gradient-to-r from-muted/50 to-background">
            <tr>
              {tableData.columns?.map((column: string, idx: number) => (
                <th
                  key={idx}
                  className="px-5 py-3 text-left text-xs font-semibold font-display uppercase tracking-wider whitespace-nowrap"
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border/20">
            {loading ? (
              <tr>
                <td
                  colSpan={tableData.columns?.length || 1}
                  className="px-5 py-10 text-center text-sm opacity-50"
                >
                  加载中...
                </td>
              </tr>
            ) : (
            <>
            {getCurrentPageData().map((row: any, idx: number) => (
              <tr key={idx} className="hover:bg-accent/5 transition-colors duration-150">
                {tableData.columns?.map((column: string, colIdx: number) => (
                  <td
                    key={colIdx}
                    className="px-5 py-3 text-sm whitespace-nowrap font-mono"
                  >
                    {row[column]?.toString() || "-"}
                  </td>
                ))}
              </tr>
            ))}
            {getCurrentPageData().length === 0 && (
              <tr>
                <td
                  colSpan={tableData.columns?.length || 1}
                  className="px-5 py-10 text-center text-sm opacity-50"
                >
                  暂无数据
                </td>
              </tr>
            )}
            </>
            )}
          </tbody>
        </table>
      </div>

      {/* 分页控件 */}
      {totalPages > 1 && (
        <div className="border-t border-border/50 px-5 py-3 flex items-center justify-between bg-gradient-to-r from-muted/30 to-background">
          <div className="text-xs opacity-70 font-mono">
            共 {tableData.totalRows || totalRows} 行 ·
            第 {currentPage} / {totalPages} 页
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => setCurrentPage(prev => Math.max(1, prev - 1))}
              disabled={currentPage === 1}
              className={`px-4 py-1.5 text-xs font-medium rounded-xl transition-colors duration-200 ${currentPage === 1
                ? "glass-card border border-border/30 opacity-30 cursor-not-allowed"
                : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
                }`}
            >
              上一页
            </button>
            <button
              onClick={() => setCurrentPage(prev => Math.min(totalPages, prev + 1))}
              disabled={currentPage === totalPages}
              className={`px-4 py-1.5 text-xs font-medium rounded-xl transition-colors duration-200 ${currentPage === totalPages
                ? "glass-card border border-border/30 opacity-30 cursor-not-allowed"
                : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
                }`}
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// 标签渲染组件
function MessageTagRenderer({
  tag,
  messageId,
  message,
  onUpdateMessage
}: {
  tag: MessageTag;
  messageId?: string;
  message?: Message;
  onUpdateMessage?: (messageId: string, newTags: MessageTag[]) => void;
}) {
  const [isEditing, setIsEditing] = useState(false);
  const [sqlContent, setSqlContent] = useState(tag.content);
  const [isRunning, setIsRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  const handleRunSql = async () => {
    if (!messageId || !onUpdateMessage) return;

    setIsRunning(true);
    setRunError(null);

    try {
      const response = await executeSql(sqlContent);
      // response.tags 包含了新的 SQL 标签（可能被格式化过）和结果标签
      if (response.tags) {
        onUpdateMessage(messageId, response.tags);
        setIsEditing(false);
      }
    } catch (err: any) {
      setRunError(err.message || "执行失败");
    } finally {
      setIsRunning(false);
    }
  };

  // 处理 SQL 执行结果（用于 sql_editable）
  const handleSqlExecuteResult = (resultTag: MessageTag) => {
    if (!messageId || !onUpdateMessage || !message) return;

    // 将结果 tag 追加到当前消息的 tags 中
    const newTags = [...(message.tags || []), resultTag];
    onUpdateMessage(messageId, newTags);
  };

  switch (tag.type) {
    case "sql_editable":
      return (
        <EditableSqlBlock
          tag={tag}
          onExecuteResult={handleSqlExecuteResult}
        />
      );

    case "sql":
      return (
        <div className="rounded-2xl glass-card border border-border/50 p-5 glow-border overflow-hidden">
          <div className="mb-3 flex items-center justify-between">
            <span className="text-sm font-semibold font-display flex items-center gap-2">
              <span className="text-accent">⚡</span>
              {tag.title || "SQL 查询"}
            </span>
            <div className="flex gap-2">
              {!isEditing ? (
                <>
                  <button
                    onClick={() => setIsEditing(true)}
                    className="rounded-xl px-3 py-1.5 text-xs font-medium glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200 flex items-center gap-1.5"
                  >
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
                    </svg>
                    编辑
                  </button>
                  <button
                    onClick={() => navigator.clipboard.writeText(tag.content)}
                    className="rounded-xl px-3 py-1.5 text-xs font-medium glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
                    title="复制 SQL"
                  >
                    复制
                  </button>
                </>
              ) : (
                <>
                  <button
                    onClick={() => {
                      setIsEditing(false);
                      setSqlContent(tag.content);
                      setRunError(null);
                    }}
                    className="rounded-xl px-3 py-1.5 text-xs font-medium glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleRunSql}
                    disabled={isRunning}
                    className="rounded-xl px-3 py-1.5 text-xs font-medium gradient-btn text-white transition-all duration-200 flex items-center gap-1.5 disabled:opacity-50"
                  >
                    {isRunning ? (
                      "运行中..."
                    ) : (
                      <>
                        <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM9.555 7.168A1 1 0 008 8v4a1 1 0 001.555.832l3-2a1 1 0 000-1.664l-3-2z" clipRule="evenodd" />
                        </svg>
                        运行
                      </>
                    )}
                  </button>
                </>
              )}
            </div>
          </div>

          {isEditing ? (
            <div className="relative">
              <textarea
                value={sqlContent}
                onChange={(e) => setSqlContent(e.target.value)}
                className="w-full h-40 glass-card border border-border/50 text-accent font-mono text-sm p-4 rounded-xl focus:border-accent focus:ring-2 focus:ring-accent/20 outline-none resize-y transition-colors duration-200"
                spellCheck={false}
              />
              {runError && (
                <div className="mt-3 text-xs text-red-400 glass-card border border-red-500/30 bg-red-500/10 p-3 rounded-xl">
                  {runError}
                </div>
              )}
            </div>
          ) : (
            <pre className="overflow-x-auto text-sm text-accent font-mono leading-relaxed">
              <code>{tag.content}</code>
            </pre>
          )}
        </div>
      );

    case "table":
      return <PaginatedTable tag={tag} />;

    case "chart":
      return <AutoChart tag={tag} />;

    case "error":
      return (
        <div className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950/30 p-4">
          <div className="flex items-center gap-2">
            <svg className="h-5 w-5 text-red-400 dark:text-red-500" fill="currentColor" viewBox="0 0 20 20">
              <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
            </svg>
            <span className="text-sm font-medium text-red-800 dark:text-red-200">
              {tag.title || "执行错误"}
            </span>
          </div>
          <p className="mt-1 text-sm text-red-700 dark:text-red-300">{tag.content.error}</p>
        </div>
      );

    case "code":
      return (
        <div className="rounded-lg bg-gray-950 dark:bg-black p-4 border border-gray-800 dark:border-gray-700">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold text-gray-400 dark:text-gray-300">
              {tag.title || "Python 代码"}
            </span>
            <button
              onClick={() => navigator.clipboard.writeText(tag.content)}
              className="rounded px-2 py-1 text-xs text-gray-400 hover:bg-gray-800 dark:hover:bg-gray-700 hover:text-white transition-colors"
              title="复制代码"
            >
              复制
            </button>
          </div>
          <pre className="overflow-x-auto text-sm text-blue-400">
            <code>{tag.content}</code>
          </pre>
        </div>
      );

    case "image":
      return (
        <div className="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 p-2">
          <div className="mb-2 px-2">
            <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">
              {tag.title || "分析图表"}
            </span>
          </div>
          <img
            src={tag.content}
            alt="Analysis Chart"
            className="max-w-full h-auto rounded"
          />
        </div>
      );

    // analysis_result 在气泡外单独渲染，这里不处理
    case "analysis_result":
      return null;

    default:
      return (
        <div className="rounded-lg border border-gray-200 dark:border-gray-800 bg-gray-50 dark:bg-gray-900 p-4">
          <p className="text-sm text-gray-600 dark:text-gray-400">
            未知标签类型: {tag.type}
          </p>
        </div>
      );
  }
}

// 带标签页的消息组件（支持多结果集分组）
function TaggedMessage({
  message,
  onUpdateMessage
}: {
  message: Message;
  onUpdateMessage?: (messageId: string, newTags: MessageTag[]) => void;
}) {
  const [activeGroupIndex, setActiveGroupIndex] = useState(-1); // -1: AI Summary
  const [activeViewType, setActiveViewType] = useState<string>("table"); // Default view
  const isStreaming = message.isStreaming;

  // 将标签按结果集分组（排除 analysis_result，它单独渲染）
  const resultGroups = useState(() => {
    if (!message.tags) return [];

    // 过滤掉 analysis_result、image、suggestions，它们在气泡外单独渲染
    const filteredTags = message.tags.filter(t => t.type !== 'analysis_result' && t.type !== 'image' && t.type !== 'suggestions');

    const groups: { id: string; name: string; tags: MessageTag[] }[] = [];
    let currentTags: MessageTag[] = [];

    filteredTags.forEach((tag) => {
      // 遇到 SQL 标签且当前组已有内容时，开始新组
      if (tag.type === 'sql' && currentTags.length > 0) {
        groups.push({
          id: `result-${groups.length + 1}`,
          name: `结果集 ${groups.length + 1}`,
          tags: currentTags
        });
        currentTags = [];
      }
      currentTags.push(tag);
    });

    if (currentTags.length > 0) {
      groups.push({
        id: `result-${groups.length + 1}`,
        name: `结果集 ${groups.length + 1}`,
        tags: currentTags
      });
    }
    return groups;
  })[0];

  // 如果没有 tags，直接显示内容
  if (!message.tags || message.tags.length === 0) {
    return (
      <div className="prose prose-sm dark:prose-invert max-w-none break-words leading-relaxed">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {message.content}
        </ReactMarkdown>
      </div>
    );
  }

  // 获取当前激活的组和标签
  const activeGroup = activeGroupIndex >= 0 ? resultGroups[activeGroupIndex] : null;
  // 在当前组中查找匹配 activeViewType 的标签，如果没有则默认取第一个
  const activeTag = activeGroup?.tags.find(t => t.type === activeViewType) || activeGroup?.tags[0];

  return (
    <div>
      {/* 一级导航：结果集选择（仅当有非 analysis_result 的 tag 时显示） */}
      {resultGroups.length > 0 && (
        <div className="flex flex-wrap gap-2 border-b border-border/30 pb-3 mb-4">
          <button
            onClick={() => setActiveGroupIndex(-1)}
            className={`px-4 py-2 text-sm font-medium rounded-2xl transition-all duration-200 ${activeGroupIndex === -1
              ? "gradient-btn text-white"
              : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
              }`}
          >
            AI 总结
          </button>
          {resultGroups.map((group, idx) => (
            <button
              key={group.id}
              onClick={() => {
                setActiveGroupIndex(idx);
                const hasCurrentType = group.tags.some(t => t.type === activeViewType);
                if (!hasCurrentType) {
                  const firstType = group.tags.find(t => t.type === 'table') ? 'table' : group.tags[0].type;
                  setActiveViewType(firstType);
                }
              }}
              className={`px-4 py-2 text-sm font-medium rounded-2xl transition-all duration-200 ${activeGroupIndex === idx
                ? "gradient-btn text-white"
                : "glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10"
                }`}
            >
              {group.name}
            </button>
          ))}
        </div>
      )}

      {/* 内容区域 */}
      {activeGroupIndex === -1 ? (
        <div className="prose prose-sm dark:prose-invert max-w-none break-words leading-relaxed">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {message.content}
          </ReactMarkdown>
          {isStreaming && message.content && (
            <span className="inline-block w-2 h-5 bg-accent animate-pulse ml-0.5 align-middle rounded-full" />
          )}
        </div>
      ) : (
        <div>
          {/* 二级导航：视图选择（仅当该组有多个标签时显示） */}
          {activeGroup && activeGroup.tags.length > 1 && (
            <div className="flex gap-6 mb-5 text-sm border-b border-border/20 pb-1">
              {activeGroup.tags.map((tag, idx) => (
                <button
                  key={`${tag.type}-${idx}`}
                  onClick={() => setActiveViewType(tag.type)}
                  className={`pb-3 border-b-2 transition-all duration-200 flex items-center gap-2 font-medium ${activeViewType === tag.type
                    ? "border-accent text-accent"
                    : "border-transparent opacity-60 hover:opacity-100 hover:text-accent"
                    }`}
                >
                  <span>{getTagIcon(tag.type)}</span>
                  <span>{getTagLabel(tag.type)}</span>
                </button>
              ))}
            </div>
          )}

          {/* 视图内容 */}
          {activeTag && (
            <MessageTagRenderer
              tag={activeTag}
              messageId={message.id}
              message={message}
              onUpdateMessage={onUpdateMessage}
            />
          )}
        </div>
      )}
    </div>
  );
}

// 获取标签类型对应的图标
function getTagIcon(type: string): string {
  switch (type) {
    case "sql": return "🗃️";
    case "sql_editable": return "🔍";
    case "table": return "📊";
    case "chart": return "📈";
    case "error": return "❌";
    case "code": return "💻";
    case "image": return "🖼️";
    case "analysis_result": return "📋";
    default: return "📄";
  }
}

// 获取标签类型对应的默认标题
function getTagLabel(type: string): string {
  switch (type) {
    case "sql": return "SQL 语句";
    case "sql_editable": return "生成的 SQL";
    case "table": return "数据表格";
    case "chart": return "数据图表";
    case "error": return "错误信息";
    case "code": return "Python 代码";
    case "image": return "分析图表";
    case "analysis_result": return "分析详情";
    default: return type;
  }
}

function renderSuggestions(
  suggestions: string[] | undefined,
  tags: MessageTag[] | undefined,
  onSendMessage: ((content: string) => void) | undefined
) {
  const rawContent = tags?.find(t => t.type === 'suggestions')?.content;
  const sugs: string[] | undefined = suggestions ?? (Array.isArray(rawContent) ? (rawContent as string[]) : undefined);
  if (!sugs || sugs.length === 0) return null;
  return (
    <div className="w-full mt-4">
      <p className="text-xs font-medium opacity-60 mb-3 font-display">💡 推荐后续问题</p>
      <div className="flex flex-wrap gap-2">
        {sugs.map((suggestion, idx) => (
          <button
            key={idx}
            onClick={() => onSendMessage?.(suggestion)}
            className="px-4 py-2.5 text-sm text-left rounded-2xl glass-card border border-accent/30 hover:border-accent/60 hover:bg-accent/10 transition-all duration-200"
          >
            {suggestion}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function ChatWindow({ messages, isSending, onUpdateMessage, onSendMessage, onEditAndResend, onRegenerateMessage, onFeedback }: ChatWindowProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const prevMessagesLengthRef = useRef(0);
  const isNearBottomRef = useRef(true);
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null);
  const [editContent, setEditContent] = useState("");

  // 监听滚动事件，判断用户是否在底部附近
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    const handleScroll = () => {
      const threshold = 80; // 距底部 80px 以内视为"在底部"
      isNearBottomRef.current =
        el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
    };

    el.addEventListener("scroll", handleScroll, { passive: true });
    return () => el.removeEventListener("scroll", handleScroll);
  }, []);

  // 自动滚动到底部（仅在用户处于底部附近时）
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    const lastMessage = messages[messages.length - 1];
    const isStreaming = lastMessage?.isStreaming;
    // 用户发送新消息时，强制滚到底部
    const userJustSent = hasNewMessage && lastMessage?.role === "user";

    if (userJustSent) {
      isNearBottomRef.current = true;
    }

    if (isNearBottomRef.current && (hasNewMessage || isSending || isStreaming)) {
      scrollRef.current?.scrollTo({
        top: scrollRef.current.scrollHeight,
        behavior: "smooth"
      });
    }
    prevMessagesLengthRef.current = messages.length;
  }, [messages, isSending]);

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto scrollbar-thin">
      <div className="mx-auto max-w-5xl w-full px-8 py-10 space-y-10">
        {messages.map((message) => (
          <div
            key={message.id}
            className={`group flex gap-4 ${message.role === "user" ? "flex-row-reverse" : "flex-row"
              }`}
          >
            {/* 头像 */}
            <div
              className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl font-medium text-sm transition-all duration-200 ${message.role === "user"
                ? "bg-accent/15 border border-accent/30 text-accent"
                : "glass-card border border-border/50"
                }`}
            >
              {message.role === "user" ? "我" : "AI"}
            </div>

            {/* 消息内容 - 自适应宽度 */}
            <div
              className={`${message.role === "user"
                ? "max-w-[70%] flex flex-col items-end"
                : "w-full flex flex-col items-start"
                }`}
            >
              {/* 意图标签 - 显示在消息气泡上方 */}
              {message.role === "assistant" && message.intentInfo && (
                <div className="mb-2">
                  <IntentBadge intentInfo={message.intentInfo} />
                </div>
              )}

              {/* 气泡 */}
              <div
                className={`rounded-2xl px-5 py-4 transition-all duration-200 ${message.role === "user"
                  ? "bg-accent/10 border border-accent/20"
                  : "glass-card border border-border/50 hover:border-accent/20"
                  } ${message.role === "assistant" && message.tags?.some(t => t.type === 'analysis_result') ? 'w-full' : ''}`}
              >
                {/* 编辑态 */}
                {editingMessageId === message.id ? (
                  <div>
                    <textarea
                      value={editContent}
                      onChange={(e) => setEditContent(e.target.value)}
                      className="w-full min-h-[80px] glass-card border border-border/50 text-foreground text-sm p-3 rounded-xl focus:border-accent focus:ring-2 focus:ring-accent/20 outline-none resize-y transition-colors duration-200"
                      autoFocus
                    />
                    <div className="flex justify-end gap-2 mt-2">
                      <button
                        onClick={() => setEditingMessageId(null)}
                        className="px-3 py-1.5 text-xs font-medium rounded-xl glass-card border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
                      >
                        取消
                      </button>
                      <button
                        onClick={() => {
                          if (onEditAndResend && editContent.trim()) {
                            onEditAndResend(message.id, editContent.trim());
                            setEditingMessageId(null);
                          }
                        }}
                        className="px-3 py-1.5 text-xs font-medium gradient-btn text-white rounded-xl transition-all duration-200"
                      >
                        发送
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    {/* 步骤时间线 */}
                    {(message.isStreaming || (message.completedSteps && message.completedSteps.length > 0)) && (
                      <StepTimeline
                        completedSteps={message.completedSteps}
                        currentStage={message.streamingStage}
                        currentMessage={message.streamingMessage}
                        isStreaming={message.isStreaming}
                      />
                    )}

                    {/* 推理过程展示 */}
                    {message.role === "assistant" && message.reasoningSteps && message.reasoningSteps.length > 0 && (
                      <ReasoningChain
                        steps={message.reasoningSteps}
                        isStreaming={message.isStreaming && message.streamingStage === "reasoning"}
                      />
                    )}

                    {/* 标签化消息或有 tags 的消息 */}
                    {message.tags && message.tags.length > 0 ? (
                      // 查数模式：如果只有一个 sql_editable tag，直接渲染
                      message.tags.length === 1 && message.tags[0].type === 'sql_editable' ? (
                        <EditableSqlBlock
                          tag={message.tags[0]}
                          onExecuteResult={(resultTag) => {
                            if (onUpdateMessage) {
                              const newTags = [...(message.tags || []), resultTag];
                              onUpdateMessage(message.id, newTags);
                            }
                          }}
                        />
                      ) : (
                        <TaggedMessage
                          message={message}
                          onUpdateMessage={onUpdateMessage}
                        />
                      )
                    ) : (
                      <div className={`max-w-none break-words leading-relaxed ${message.role === "user"
                        ? "prose prose-sm max-w-none"
                        : "prose prose-sm dark:prose-invert max-w-none"
                        }`}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {message.content}
                        </ReactMarkdown>
                        {message.isStreaming && message.content && (
                          <span className="inline-block w-2 h-5 bg-emerald-500 animate-pulse ml-0.5 align-middle" />
                        )}
                      </div>
                    )}

                    <span
                      className={`mt-2 text-xs block opacity-60 font-mono ${message.role === "user" ? "" : ""
                        }`}
                    >
                      {message.timestamp.toLocaleTimeString()}
                    </span>
                  </>
                )}
              </div>

              {/* 工具栏 - 流式输出中不显示 */}
              {!message.isStreaming && editingMessageId !== message.id && (
                <div className={`opacity-0 group-hover:opacity-100 transition-opacity duration-200 ${
                  message.role === "user" ? "flex justify-end" : ""
                }`}>
                  <MessageToolbar
                    message={message}
                    onCopy={() => navigator.clipboard.writeText(message.content)}
                    onEdit={message.role === "user" ? () => {
                      setEditingMessageId(message.id);
                      setEditContent(message.content);
                    } : undefined}
                    onRegenerate={message.role === "assistant" && onRegenerateMessage
                      ? () => onRegenerateMessage(message.id)
                      : undefined}
                    onFeedback={message.role === "assistant" && onFeedback
                      ? (fb) => onFeedback(message.id, fb)
                      : undefined}
                  />
                </div>
              )}

              {/* ── analysis_result 独立全宽展示区（气泡外） ── */}
              {message.role === "assistant" && message.tags
                ?.filter(t => t.type === 'analysis_result')
                .map((tag, idx) => (
                  <div key={`analysis-${idx}`} className="w-full mt-4">
                    <AnalysisResultRenderer content={tag.content} title={tag.title} allTags={message.tags || []} />
                  </div>
                ))
              }

              {/* ── image 独立全宽展示区（气泡外） ── */}
              {message.role === "assistant" && message.tags
                ?.filter(t => t.type === 'image')
                .map((tag, idx) => (
                  <div key={`image-${idx}`} className="w-full mt-4 glass-card rounded-2xl border border-border/50 overflow-hidden hover:border-accent/30 transition-all duration-200">
                    <div className="px-5 py-3 border-b border-border/50 bg-gradient-to-r from-muted/50 to-background flex items-center gap-2">
                      <span className="text-base">🖼️</span>
                      <span className="text-sm font-semibold font-display">{tag.title || '分析图表'}</span>
                    </div>
                    <div className="p-4">
                      <img
                        src={tag.content}
                        alt="Analysis Chart"
                        className="w-full h-auto rounded-xl"
                        style={{ maxHeight: '500px', objectFit: 'contain' }}
                      />
                    </div>
                  </div>
                ))
              }

              {/* ── 推荐后续问题（气泡外，来自 message.suggestions 或 tags 中的 suggestions） ── */}
              {message.role === "assistant" && renderSuggestions(
                message.suggestions,
                message.tags,
                onSendMessage
              )}
            </div>

          </div>
        ))}

        {/* 加载动画 - 仅在发送中且最后消息是用户消息且没有助手占位时显示 */}
        {isSending && messages.length > 0 && messages[messages.length - 1].role === "user" && (
          <div className="flex gap-4">
            {/* AI 头像占位 */}
            <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl glass-card border border-border/50 font-medium text-sm">
              AI
            </div>

            {/* 加载动画 */}
            <div className="flex flex-col items-start">
              <div className="rounded-2xl px-5 py-4 glass-card border border-border/50">
                <LoadingSpinner />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

