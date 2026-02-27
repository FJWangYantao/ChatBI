"use client";

import { useState, KeyboardEvent } from "react";

interface InputBoxProps {
  onSend: (message: string, agentType?: string) => void;
  onStop?: () => void;
  isSending?: boolean;
}

export default function InputBox({ onSend, onStop, isSending }: InputBoxProps) {
  const [input, setInput] = useState("");
  const [selectedAgent, setSelectedAgent] = useState<string | null>(null);

  const handleSend = () => {
    if (input.trim()) {
      onSend(input.trim(), selectedAgent || undefined);
      setInput("");
      setSelectedAgent(null); // 发送后重置选择
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const agentOptions = [
    {
      id: "DATA_ANALYSIS",
      label: "数据分析",
      icon: (
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
        </svg>
      ),
      color: "blue",
      description: "深度数据分析与可视化"
    },
    {
      id: "DIAGNOSTIC_ANALYSIS",
      label: "诊断分析",
      icon: (
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
        </svg>
      ),
      color: "amber",
      description: "问题根因分析"
    },
    {
      id: "REPORT",
      label: "生成报告",
      icon: (
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
      ),
      color: "purple",
      description: "生成完整分析报告"
    }
  ];

  const getButtonStyle = (agentId: string) => {
    const isSelected = selectedAgent === agentId;
    const baseStyle = "flex items-center gap-2 px-3 py-1.5 text-sm border";

    if (isSelected) {
      return `${baseStyle} bg-black dark:bg-white text-white dark:text-black border-black dark:border-white`;
    }

    return `${baseStyle} bg-white dark:bg-gray-900 text-gray-700 dark:text-gray-300 border-gray-300 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-800`;
  };

  return (
    <div className="border-t border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 p-6">
      <div className="mx-auto max-w-4xl">
        {/* Agent 模式选择器 */}
        <div className="mb-4">
          <div className="flex flex-wrap gap-2">
            {agentOptions.map((agent) => (
              <button
                key={agent.id}
                onClick={() => setSelectedAgent(selectedAgent === agent.id ? null : agent.id)}
                className={getButtonStyle(agent.id)}
                title={agent.description}
              >
                <span>{agent.label}</span>
                {selectedAgent === agent.id && (
                  <span className="text-xs">✓</span>
                )}
              </button>
            ))}
          </div>
        </div>

        {/* 输入框 */}
        <div className="flex items-end gap-3 border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 p-3">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={
              selectedAgent
                ? `输入问题，将使用 ${agentOptions.find(a => a.id === selectedAgent)?.label} 模式处理...`
                : "输入您的问题，例如：查询上个月的销售数据..."
            }
            className="flex-1 resize-none border-0 px-2 py-2 text-sm text-gray-900 dark:text-gray-100 placeholder-gray-500 dark:placeholder-gray-500 bg-transparent focus:outline-none focus:ring-0"
            rows={1}
            style={{ minHeight: "40px", maxHeight: "200px" }}
          />
          {isSending ? (
            <button
              onClick={onStop}
              className="flex h-10 w-10 items-center justify-center bg-red-500 text-white hover:bg-red-600 transition-colors"
              title="停止"
            >
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                <path d="M6 6h12v12H6z" />
              </svg>
            </button>
          ) : (
            <button
              onClick={handleSend}
              disabled={!input.trim()}
              className="flex h-10 items-center gap-2 bg-black dark:bg-white px-4 py-2 text-sm text-white dark:text-black hover:opacity-80 disabled:bg-gray-300 dark:disabled:bg-gray-700 disabled:cursor-not-allowed"
            >
              <span>发送</span>
            </button>
          )}
        </div>
        <p className="mt-2 text-center text-xs text-gray-500 dark:text-gray-500">
          按 Enter 发送，Shift + Enter 换行
        </p>
      </div>
    </div>
  );
}
