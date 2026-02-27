"use client";

import { useState, useEffect } from "react";
import ServiceStatusIndicator from "@/components/ServiceStatusIndicator";

export interface Conversation {
  conversationId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

interface ConversationSidebarProps {
  currentConversationId: string | null;
  onConversationSelect: (conversationId: string) => void;
  onNewConversation: () => void;
  isOpen: boolean;
  onToggle: () => void;
  triggerRefresh?: number; // 新增：用于触发刷新的计数器
}

export default function ConversationSidebar({
  currentConversationId,
  onConversationSelect,
  onNewConversation,
  isOpen,
  onToggle,
  triggerRefresh,
}: ConversationSidebarProps) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);

  // 加载对话列表
  const loadConversations = async () => {
    setLoading(true);
    try {
      const response = await fetch('http://localhost:8080/api/conversations');
      if (response.ok) {
        const data = await response.json();
        setConversations(data);
      }
    } catch (error) {
      console.error('加载对话列表失败:', error);
    } finally {
      setLoading(false);
    }
  };

  // 初始加载
  useEffect(() => {
    loadConversations();
  }, []);

  // 当侧边栏打开时刷新
  useEffect(() => {
    if (isOpen) {
      loadConversations();
    }
  }, [isOpen]);

  // 当 triggerRefresh 变化时刷新（用于发送消息后自动刷新）
  useEffect(() => {
    if (triggerRefresh && triggerRefresh > 0) {
      loadConversations();
    }
  }, [triggerRefresh]);

  // 删除对话
  const handleDeleteConversation = async (conversationId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm('确定要删除这个对话吗？')) return;

    try {
      const response = await fetch(`http://localhost:8080/api/conversations/${conversationId}`, {
        method: 'DELETE',
      });
      if (response.ok) {
        setConversations(prev => prev.filter(c => c.conversationId !== conversationId));
        if (currentConversationId === conversationId) {
          onNewConversation();
        }
      }
    } catch (error) {
      console.error('删除对话失败:', error);
    }
  };

  // 格式化时间
  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

    if (diffDays === 0) {
      return '今天';
    } else if (diffDays === 1) {
      return '昨天';
    } else if (diffDays < 7) {
      return `${diffDays} 天前`;
    } else {
      return date.toLocaleDateString('zh-CN');
    }
  };

  return (
    <>
      {/* 遮罩层 */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-40 lg:hidden"
          onClick={onToggle}
        />
      )}

      {/* 侧边栏 */}
      <aside
        className={`
          fixed lg:relative z-50 h-full bg-white dark:bg-gray-900 border-r border-gray-200 dark:border-gray-800
          ${isOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}
          w-72 flex flex-col
        `}
      >
        {/* 头部 */}
        <div className="p-4 border-b border-gray-200 dark:border-gray-800">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-medium text-gray-900 dark:text-gray-100">
              对话历史
            </h2>
            <button
              onClick={onToggle}
              className="lg:hidden p-2 hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <button
            onClick={onNewConversation}
            className="w-full flex items-center justify-center gap-2 bg-black dark:bg-white px-4 py-2 text-sm text-white dark:text-black hover:opacity-80"
          >
            <span>+</span>
            新建对话
          </button>
        </div>

        {/* 对话列表 */}
        <div className="flex-1 overflow-y-auto p-3 space-y-2">
          {loading ? (
            <div className="text-center py-8 text-gray-500 dark:text-gray-400">
              加载中...
            </div>
          ) : conversations.length === 0 ? (
            <div className="text-center py-8 text-gray-500 dark:text-gray-400">
              暂无对话历史
            </div>
          ) : (
            conversations.map((conversation) => (
              <div
                key={conversation.conversationId}
                onClick={() => onConversationSelect(conversation.conversationId)}
                className={`
                  group relative p-3 cursor-pointer border
                  ${currentConversationId === conversation.conversationId
                    ? 'bg-gray-100 dark:bg-gray-800 border-gray-300 dark:border-gray-700'
                    : 'hover:bg-gray-50 dark:hover:bg-gray-800 border-transparent'
                  }
                `}
              >
                <div className="flex items-start gap-3">
                  <div className="flex-1 min-w-0">
                    <h3 className="text-sm text-gray-900 dark:text-gray-100 truncate">
                      {conversation.title}
                    </h3>
                    <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                      {formatDate(conversation.updatedAt)}
                    </p>
                  </div>
                  <button
                    onClick={(e) => handleDeleteConversation(conversation.conversationId, e)}
                    className="opacity-0 group-hover:opacity-100 p-1 hover:bg-red-100 dark:hover:bg-red-900/20 text-gray-400 hover:text-red-600"
                    title="删除对话"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                </div>
              </div>
            ))
          )}
        </div>

        {/* 底部 */}
        <div className="p-4 border-t border-gray-200 dark:border-gray-800 space-y-3">
          <ServiceStatusIndicator />
          <button
            onClick={loadConversations}
            className="w-full text-sm text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100"
          >
            刷新列表
          </button>
        </div>
      </aside>
    </>
  );
}
