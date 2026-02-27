"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import ChatWindow from "@/components/Chat/ChatWindow";
import InputBox from "@/components/Chat/InputBox";
import ConversationSidebar from "@/components/Chat/ConversationSidebar";
import CodeExecutionPanel from "@/components/CodeExecution/CodeExecutionPanel";
import { ThemeProvider } from "@/contexts/ThemeContext";
import ThemeToggle from "@/components/ThemeToggle";
import MosaicLogo from "@/components/MosaicLogo";
import { DataSource } from "@/types/datasource";
import { CodeExecution } from "@/types/code-execution";
import { getActiveDataSource } from "@/lib/api/datasource";
import { streamChatMessage } from "@/lib/api/chatStream";

// 消息标签类型
export interface MessageTag {
  type: string;        // sql, table, chart, image 等
  content: any;        // 标签内容
  title?: string;      // 标签标题
  metadata?: any;      // 额外元数据
}

// 意图信息类型
export interface IntentInfo {
  category: string;           // 意图类别
  categoryCn: string;         // 意图类别中文
  categoryConfidence: number; // 意图类别置信度
  subtype: string;            // 意图子类型
  subtypeConfidence: number;  // 意图子类型置信度
  subtypeCn: string;          // 意图子类型中文
}

// 推理步骤类型
export interface ReasoningStep {
  step: "thought" | "observation";
  content: string;
  stepIndex: number;
}

export interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: Date;
  tags?: MessageTag[];     // 标签化内容
  intentInfo?: IntentInfo; // 意图识别信息
  suggestions?: string[];  // 推荐后续问题
  reasoningSteps?: ReasoningStep[]; // 推理步骤
  isStreaming?: boolean;           // 是否正在流式输出
  streamingStage?: string;         // 当前流式阶段
  streamingMessage?: string;       // 当前阶段描述
  streamingProgress?: number;      // 当前进度
  streamingTotalSteps?: number;    // 总步骤数
}

export default function Home() {
  const router = useRouter();
  const [messages, setMessages] = useState<Message[]>([]);
  const [currentConversationId, setCurrentConversationId] = useState<string | null>(null);
  const conversationIdRef = useRef<string | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [refreshTrigger, setRefreshTrigger] = useState(0); // 用于触发对话列表刷新
  const [isSending, setIsSending] = useState(false); // 消息发送状态

  // 当前激活的数据源
  const [activeDataSource, setActiveDataSource] = useState<DataSource | null>(null);

  // 代码执行状态
  const [codeExecutions, setCodeExecutions] = useState<CodeExecution[]>([]);
  const [codePanelOpen, setCodePanelOpen] = useState(false);

  // 同步 conversationId 到 ref，避免闭包捕获过期值
  useEffect(() => {
    conversationIdRef.current = currentConversationId;
  }, [currentConversationId]);

  // 加载对话历史
  const loadConversationHistory = async (conversationId: string) => {
    setLoading(true);
    setMessages([]); // 立即清空旧内容，避免显示上一个对话
    try {
      const response = await fetch(`/api/conversations/${conversationId}`);
      if (response.ok) {
        const data = await response.json();
        const historyMessages: Message[] = data.messages.map((msg: any) => ({
          id: msg.messageId,
          role: msg.role,
          content: msg.content,
          timestamp: new Date(msg.createdAt),
          tags: msg.tags || undefined,
        }));
        setMessages(historyMessages);
      } else {
        // HTTP 错误时显示提示
        setMessages([{
          id: "error",
          role: "assistant",
          content: `加载对话历史失败（HTTP ${response.status}），请稍后重试。`,
          timestamp: new Date(),
        }]);
      }
    } catch (error) {
      console.error('加载对话历史失败:', error);
      setMessages([{
        id: "error",
        role: "assistant",
        content: "网络错误，无法加载对话历史。请检查后端服务是否正常运行。",
        timestamp: new Date(),
      }]);
    } finally {
      setLoading(false);
    }
  };

  // 新建对话
  const handleNewConversation = () => {
    // 中止进行中的 SSE 流
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    setIsSending(false);

    setCurrentConversationId(null);
    setMessages([
      {
        id: "welcome",
        role: "assistant",
        content: "您好！我是 ChatBI 助手，有什么可以帮助您的吗？",
        timestamp: new Date(),
      },
    ]);
    setCodeExecutions([]);
    setCodePanelOpen(false);
    setSidebarOpen(false);
  };

  // 选择对话
  const handleSelectConversation = (conversationId: string) => {
    // 中止进行中的 SSE 流
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    setIsSending(false);

    setCurrentConversationId(conversationId);
    loadConversationHistory(conversationId);
    setSidebarOpen(false);
  };

  // 更新消息
  const handleUpdateMessage = (messageId: string, newTags: MessageTag[]) => {
    setMessages((prevMessages) =>
      prevMessages.map((msg) =>
        msg.id === messageId ? { ...msg, tags: newTags } : msg
      )
    );
  };

  // 当前流式请求的 AbortController
  const abortControllerRef = useRef<AbortController | null>(null);

  const handleSendMessage = async (content: string, agentType?: string) => {
    // 添加用户消息
    const userMessage: Message = {
      id: Date.now().toString(),
      role: "user",
      content,
      timestamp: new Date(),
    };

    const assistantId = (Date.now() + 1).toString();
    const assistantMessage: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      timestamp: new Date(),
      isStreaming: true,
      streamingStage: "connecting",
      streamingMessage: "正在连接...",
      streamingProgress: 0,
      streamingTotalSteps: 7,
    };

    setMessages((prev) => [...prev, userMessage, assistantMessage]);
    setIsSending(true);

    // 创建 AbortController
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const timeoutId = setTimeout(() => controller.abort(), 300000); // 5 分钟超时

    // 用于累积 tags 的辅助引用
    const tagsAccumulator: MessageTag[] = [];

    try {
      await streamChatMessage(
        content,
        conversationIdRef.current,
        {
          onStatus: (data) => {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? {
                      ...msg,
                      streamingStage: data.stage,
                      streamingMessage: data.message,
                      streamingProgress: data.progress,
                      streamingTotalSteps: data.totalSteps,
                    }
                  : msg
              )
            );
          },

          onIntent: (data) => {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? {
                      ...msg,
                      intentInfo: {
                        category: data.category,
                        categoryCn: data.categoryCn,
                        categoryConfidence: data.categoryConfidence,
                        subtype: data.subtype,
                        subtypeConfidence: data.subtypeConfidence,
                        subtypeCn: data.subtypeCn || "",
                      },
                    }
                  : msg
              )
            );
          },

          onTextDelta: (data) => {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: msg.content + data.delta }
                  : msg
              )
            );
          },

          onTag: (data) => {
            // 累积 tag，将完整标签追加到消息的 tags 数组
            const newTag: MessageTag = {
              type: data.type,
              content: data.content,
              title: data.title,
              metadata: data.metadata,
            };
            tagsAccumulator.push(newTag);

            // 如果收到 table 类型，自动添加 chart
            const currentTags = [...tagsAccumulator];
            if (newTag.type === 'table' && !currentTags.some(t => t.type === 'chart')) {
              const chartTag: MessageTag = {
                type: 'chart',
                content: newTag.content,
                title: '数据图表',
              };
              tagsAccumulator.push(chartTag);
            }

            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, tags: [...tagsAccumulator] }
                  : msg
              )
            );
          },

          onSuggestions: (data) => {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, suggestions: data.items }
                  : msg
              )
            );
          },

          onReasoning: (data) => {
            setMessages((prev) =>
              prev.map((msg) => {
                if (msg.id !== assistantId) return msg;
                const existing = msg.reasoningSteps || [];
                return {
                  ...msg,
                  reasoningSteps: [...existing, { step: data.step as "thought" | "observation", content: data.content, stepIndex: data.stepIndex }],
                  streamingStage: "reasoning",
                  streamingMessage: data.step === "thought" ? "正在思考..." : "正在分析结果...",
                };
              })
            );
          },

          onCodeExecution: (data) => {
            console.log('[CodeExecution] 收到事件:', data.executionId, data.stage);
            setCodeExecutions((prev) => {
              const existing = prev.find(e => e.executionId === data.executionId);
              if (existing) {
                // 更新现有执行记录
                return prev.map(e =>
                  e.executionId === data.executionId
                    ? { ...e, ...data } as CodeExecution
                    : e
                );
              } else {
                // 新增执行记录
                return [...prev, data as CodeExecution];
              }
            });

            // 自动打开代码面板（setCodePanelOpen 是幂等的，无需闭包检查）
            setCodePanelOpen(true);
          },

          onDone: (data) => {
            // 更新 conversationId（先更新 ref 再更新 state）
            if (data.conversationId && data.conversationId !== conversationIdRef.current) {
              conversationIdRef.current = data.conversationId;
              setCurrentConversationId(data.conversationId);
            }

            // 标记流式完成
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? {
                      ...msg,
                      isStreaming: false,
                      streamingStage: undefined,
                      streamingMessage: undefined,
                      streamingProgress: undefined,
                      streamingTotalSteps: undefined,
                    }
                  : msg
              )
            );

            // 刷新对话列表
            setRefreshTrigger((prev) => prev + 1);
          },

          onError: (data) => {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? {
                      ...msg,
                      content: msg.content || `错误：${data.message}`,
                      isStreaming: false,
                      streamingStage: undefined,
                      streamingMessage: undefined,
                    }
                  : msg
              )
            );
          },
        },
        controller.signal,
        agentType
      );
    } catch (error: any) {
      console.error('流式聊天失败:', error);

      const isTimeout = error.name === 'AbortError' || error.code === 'ABORT_ERR';

      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? {
                ...msg,
                content: msg.content || (isTimeout
                  ? '请求超时，后端处理时间过长。请稍后重试。'
                  : '抱歉，连接后端服务失败。请确保后端服务已启动（http://localhost:8080）'),
                isStreaming: false,
                streamingStage: undefined,
                streamingMessage: undefined,
              }
            : msg
        )
      );
    } finally {
      clearTimeout(timeoutId);
      abortControllerRef.current = null;
      setIsSending(false);
    }
  };

  // 初始加载欢迎消息
  useEffect(() => {
    handleNewConversation();
  }, []);

  // 加载当前激活的数据源
  useEffect(() => {
    const loadActiveDataSource = async () => {
      try {
        const data = await getActiveDataSource();
        setActiveDataSource(data);
      } catch (err) {
        console.error("加载数据源失败:", err);
      }
    };
    loadActiveDataSource();
  }, []);

  return (
    <ThemeProvider>
      <main className="flex h-screen flex-col bg-background relative">
        {/* 头部 */}
        <header className="glass-card border-b border-border/50 backdrop-blur-xl relative z-10">
          <div className="flex items-center px-6 py-4">
            {/* 移动端菜单按钮 */}
            <button
              onClick={() => setSidebarOpen(true)}
              className="lg:hidden p-2 rounded-xl hover:bg-accent/10 hover:border-accent/30 border border-transparent transition-all duration-200 mr-3"
            >
              <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>

            <div className="flex items-center gap-3 flex-1">
              <div className="animate-pulse-glow rounded-xl">
                <MosaicLogo size={40} />
              </div>
              <div>
                <h1 className="text-xl font-display font-bold bg-gradient-to-r from-foreground to-accent bg-clip-text text-transparent">
                  ChatBI
                </h1>
              </div>
            </div>

            <div className="flex items-center gap-3">
              {/* 数据源显示 */}
              <div className="hidden md:flex items-center gap-2 px-4 py-2 glass-card rounded-xl border border-border/50 hover:border-accent/30 transition-border-color duration-200">
                <div className="w-2 h-2 rounded-full bg-accent animate-pulse-glow"></div>
                <span className="text-sm font-medium">
                  {activeDataSource ? activeDataSource.name : "未选择数据源"}
                </span>
              </div>

              {/* 数据源管理按钮 */}
              <button
                onClick={() => router.push("/datasource")}
                className="hidden md:flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-xl border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
                title="数据源管理"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8 4s-8-1.79-8-4" />
                </svg>
                <span>数据源</span>
              </button>

              {/* MCP 知识库管理按钮 */}
              <button
                onClick={() => router.push("/mcp-terms")}
                className="hidden md:flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-xl border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
                title="MCP 知识库管理"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
                </svg>
                <span>知识库</span>
              </button>

              <button
                onClick={handleNewConversation}
                className="hidden sm:flex px-5 py-2 text-sm font-medium gradient-btn text-white rounded-xl transition-all duration-200"
              >
                新建对话
              </button>
              <button
                onClick={() => setSidebarOpen(!sidebarOpen)}
                className="hidden lg:flex px-4 py-2 text-sm font-medium rounded-xl border border-border/50 hover:border-accent/50 hover:bg-accent/10 transition-all duration-200"
              >
                历史记录
              </button>
              <ThemeToggle />
            </div>
          </div>
        </header>

        {/* 主体区域 */}
        <div className="flex flex-1 overflow-hidden relative">
          {/* 对话侧边栏 */}
          <ConversationSidebar
            currentConversationId={currentConversationId}
            onConversationSelect={handleSelectConversation}
            onNewConversation={handleNewConversation}
            isOpen={sidebarOpen}
            onToggle={() => setSidebarOpen(!sidebarOpen)}
            triggerRefresh={refreshTrigger}
          />

          {/* 聊天区域 */}
          <div className="flex flex-1 flex-col overflow-hidden relative z-10">
            {loading && (
              <div className="glass-card border-b border-border/50 px-6 py-3 text-sm">
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 rounded-full bg-accent animate-pulse"></div>
                  <span>正在加载对话历史...</span>
                </div>
              </div>
            )}
            <ChatWindow
              messages={messages}
              isSending={isSending}
              onUpdateMessage={handleUpdateMessage}
              onSendMessage={handleSendMessage}
            />
            <InputBox onSend={handleSendMessage} />
          </div>
        </div>
      </main>

      {/* 代码执行面板 */}
      <CodeExecutionPanel
        executions={codeExecutions}
        isOpen={codePanelOpen}
        onClose={() => setCodePanelOpen(false)}
      />
    </ThemeProvider>
  );
}
