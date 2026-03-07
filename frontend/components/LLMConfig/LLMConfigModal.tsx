'use client';

import { useEffect, useState } from 'react';
import {
  LLMConfig,
  LLMProvider,
  MODEL_PRESETS,
  PROVIDER_BASE_URLS,
  clearLLMConfig,
  getDefaultLLMConfig,
  getDefaultModelName,
  getLLMConfig,
  getLLMConfigStore,
  getRawProviderConfig,
  saveLLMConfig,
} from '@/types/llm-config';

interface LLMConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export default function LLMConfigModal({ isOpen, onClose }: LLMConfigModalProps) {
  const [provider, setProvider] = useState<LLMProvider>('deepseek');
  const [apiKey, setApiKey] = useState('');
  const [modelName, setModelName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  useEffect(() => {
    if (!isOpen) return;

    // 读取当前激活的供应商配置
    const store = getLLMConfigStore();
    const activeProvider = store?.activeProvider || 'deepseek';
    const rawConfig = getRawProviderConfig(activeProvider);

    setProvider(activeProvider);

    if (rawConfig) {
      // 有历史配置，恢复之前保存的值
      setApiKey(rawConfig.apiKey || '');
      setModelName(rawConfig.modelName || getDefaultModelName(activeProvider));
      setBaseUrl(rawConfig.baseUrl || '');
    } else {
      // 无历史配置，显示空白表单
      setApiKey('');
      setModelName(getDefaultModelName(activeProvider));
      setBaseUrl('');
    }
  }, [isOpen]);

  const handleProviderChange = (nextProvider: LLMProvider) => {
    setProvider(nextProvider);

    // 直接读取该供应商的原始配置
    const rawConfig = getRawProviderConfig(nextProvider);

    if (rawConfig) {
      // 该供应商有历史配置，恢复之前保存的值
      setApiKey(rawConfig.apiKey || '');
      setModelName(rawConfig.modelName || getDefaultModelName(nextProvider));
      setBaseUrl(rawConfig.baseUrl || '');
    } else {
      // 该供应商从未配置过，显示空白表单（modelName 使用默认值）
      setApiKey('');
      setModelName(getDefaultModelName(nextProvider));
      setBaseUrl('');
    }
  };

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
    if (confirm(`确定清除 ${provider} 的本地配置吗？`)) {
      clearLLMConfig(provider);

      // 清除后，检查是否还有其他供应商的配置
      const store = getLLMConfigStore();
      if (!store) {
        // 所有配置都被清空了，显示默认供应商的空白表单
        setProvider('deepseek');
        setApiKey('');
        setModelName(getDefaultModelName('deepseek'));
        setBaseUrl('');
      } else {
        // 还有其他供应商的配置，切换到激活的供应商
        const activeProvider = store.activeProvider;
        const rawConfig = getRawProviderConfig(activeProvider);

        setProvider(activeProvider);
        if (rawConfig) {
          setApiKey(rawConfig.apiKey || '');
          setModelName(rawConfig.modelName || getDefaultModelName(activeProvider));
          setBaseUrl(rawConfig.baseUrl || '');
        } else {
          setApiKey('');
          setModelName(getDefaultModelName(activeProvider));
          setBaseUrl('');
        }
      }

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

        <div className="mb-4">
          <label className="block text-sm font-medium mb-2">供应商</label>
          <select
            value={provider}
            onChange={(e) => handleProviderChange(e.target.value as LLMProvider)}
            className="w-full px-3 py-2 bg-background/50 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-accent"
          >
            <option value="deepseek">DeepSeek</option>
            <option value="openrouter">OpenRouter</option>
          </select>
          <p className="text-xs text-muted-foreground mt-1">
            每个供应商会分别保存自己的 API Key、模型和 Base URL。
          </p>
        </div>

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
                留空则使用该供应商默认地址。
              </p>
            </div>
          )}
        </div>

        <div className="mb-4 p-3 bg-accent/10 rounded-lg">
          <p className="text-xs text-muted-foreground">
            配置保存在浏览器本地。切换供应商时，会自动带出该供应商上次保存的完整配置。
          </p>
        </div>

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
            清除当前供应商
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
