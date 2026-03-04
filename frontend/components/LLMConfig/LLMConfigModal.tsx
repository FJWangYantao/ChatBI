'use client';

import { useState, useEffect } from 'react';
import { LLMConfig, MODEL_PRESETS, PROVIDER_BASE_URLS, getLLMConfig, saveLLMConfig, clearLLMConfig } from '@/types/llm-config';

interface LLMConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export default function LLMConfigModal({ isOpen, onClose }: LLMConfigModalProps) {
  const [provider, setProvider] = useState<'openrouter' | 'deepseek'>('deepseek');
  const [apiKey, setApiKey] = useState('');
  const [modelName, setModelName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // 加载现有配置
  useEffect(() => {
    if (isOpen) {
      const config = getLLMConfig();
      if (config) {
        setProvider(config.provider);
        setApiKey(config.apiKey);
        setModelName(config.modelName);
        setBaseUrl(config.baseUrl || '');
      } else {
        // 设置默认值
        setProvider('deepseek');
        setApiKey('');
        setModelName('deepseek-chat');
        setBaseUrl('');
      }
    }
  }, [isOpen]);

  // 当提供商改变时，更新默认模型
  useEffect(() => {
    if (provider === 'deepseek' && !modelName) {
      setModelName('deepseek-chat');
    } else if (provider === 'openrouter' && !modelName) {
      setModelName('deepseek/deepseek-chat');
    }
  }, [provider]);

  const handleSave = () => {
    if (!apiKey.trim() || !modelName.trim()) {
      alert('请填写 API Key 和模型名称');
      return;
    }

    const config: LLMConfig = {
      provider,
      apiKey: apiKey.trim(),
      modelName: modelName.trim(),
      baseUrl: baseUrl.trim() || undefined,
    };

    saveLLMConfig(config);
    onClose();
  };

  const handleClear = () => {
    if (confirm('确定要清除配置并使用后端默认配置吗？')) {
      clearLLMConfig();
      onClose();
    }
  };

  if (!isOpen) return null;

  const currentPresets = MODEL_PRESETS[provider];

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="glass-card p-6 rounded-xl max-w-md w-full mx-4 max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-xl font-semibold mb-4">LLM 配置</h2>

        {/* 提供商选择 */}
        <div className="mb-4">
          <label className="block text-sm font-medium mb-2">提供商</label>
          <select
            value={provider}
            onChange={(e) => setProvider(e.target.value as 'openrouter' | 'deepseek')}
            className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent"
          >
            <option value="deepseek">DeepSeek</option>
            <option value="openrouter">OpenRouter</option>
          </select>
        </div>

        {/* API Key */}
        <div className="mb-4">
          <label className="block text-sm font-medium mb-2">API Key</label>
          <input
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="输入 API Key"
            className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent"
          />
        </div>

        {/* 模型名称 */}
        <div className="mb-4">
          <label className="block text-sm font-medium mb-2">模型名称</label>
          {provider === 'deepseek' ? (
            <select
              value={modelName}
              onChange={(e) => setModelName(e.target.value)}
              className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {currentPresets.map((preset) => (
                <option key={preset.value} value={preset.value}>
                  {preset.label}
                </option>
              ))}
            </select>
          ) : (
            <input
              type="text"
              value={modelName}
              onChange={(e) => setModelName(e.target.value)}
              placeholder="例如: deepseek/deepseek-chat, anthropic/claude-3.5-sonnet"
              className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent"
            />
          )}
          {provider === 'openrouter' && (
            <p className="text-xs text-muted-foreground mt-1">
              常用模型: deepseek/deepseek-chat, anthropic/claude-3.5-sonnet, openai/gpt-4-turbo
            </p>
          )}
        </div>

        {/* 高级选项 */}
        <div className="mb-4">
          <button
            onClick={() => setShowAdvanced(!showAdvanced)}
            className="text-sm text-accent hover:underline"
          >
            {showAdvanced ? '隐藏' : '显示'}高级选项
          </button>

          {showAdvanced && (
            <div className="mt-2">
              <label className="block text-sm font-medium mb-2">Base URL（可选）</label>
              <input
                type="text"
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder={PROVIDER_BASE_URLS[provider]}
                className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent text-sm"
              />
              <p className="text-xs text-muted-foreground mt-1">
                留空使用默认端点
              </p>
            </div>
          )}
        </div>

        {/* 说明 */}
        <div className="mb-4 p-3 bg-accent/10 rounded-lg">
          <p className="text-xs text-muted-foreground">
            配置保存在浏览器本地，每次请求时会传递给后端。如果不配置，将使用后端默认配置。
          </p>
        </div>

        {/* 按钮 */}
        <div className="flex gap-2">
          <button
            onClick={handleSave}
            className="flex-1 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent/90 transition-colors"
          >
            保存
          </button>
          <button
            onClick={handleClear}
            className="px-4 py-2 bg-destructive/20 text-destructive rounded-lg hover:bg-destructive/30 transition-colors"
          >
            清除
          </button>
          <button
            onClick={onClose}
            className="px-4 py-2 bg-background/50 border border-border rounded-lg hover:bg-background/70 transition-colors"
          >
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
