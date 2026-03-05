'use client';

import { useState, useEffect } from 'react';
import { getLLMConfig } from '@/types/llm-config';

export default function ModelInfoDisplay() {
  const [modelInfo, setModelInfo] = useState<{ provider: string; model: string } | null>(null);

  useEffect(() => {
    const fetchModelInfo = async () => {
      const config = getLLMConfig();
      const headers: HeadersInit = {};

      if (config) {
        headers['X-LLM-Provider'] = config.provider;
        headers['X-LLM-Model'] = config.modelName;
        headers['X-LLM-Base-Url'] = config.baseUrl || '';
      }

      try {
        const res = await fetch('/api/api/model-info', { headers });
        if (res.ok) {
          const data = await res.json();
          setModelInfo({ provider: data.provider, model: data.model });
        }
      } catch (error) {
        console.error('获取模型信息失败:', error);
      }
    };

    fetchModelInfo();
  }, []);

  if (!modelInfo) return null;

  const displayName = modelInfo.model.split('/').pop() || modelInfo.model;
  const providerName = modelInfo.provider === 'openrouter' ? 'OpenRouter' : 'DeepSeek';

  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-blue-500/10 border border-blue-500/20">
      <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
      <span className="text-sm text-gray-700 dark:text-gray-300">
        {providerName} · {displayName}
      </span>
    </div>
  );
}
