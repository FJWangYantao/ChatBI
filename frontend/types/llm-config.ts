export interface LLMConfig {
  provider: 'openrouter' | 'deepseek';
  apiKey: string;
  modelName: string;
  baseUrl?: string;
}

// 多 provider 配置
export interface LLMConfigs {
  currentProvider: 'openrouter' | 'deepseek';
  deepseek?: LLMConfig;
  openrouter?: LLMConfig;
}

// localStorage key
export const LLM_CONFIG_KEY = 'chatbi_llm_configs';

// 预设模型列表
export const MODEL_PRESETS = {
  deepseek: [
    { value: 'deepseek-chat', label: 'DeepSeek Chat (推荐)' },
    { value: 'deepseek-reasoner', label: 'DeepSeek Reasoner (R1)' },
  ],
  openrouter: [
    { value: 'deepseek/deepseek-chat', label: 'DeepSeek Chat' },
    { value: 'anthropic/claude-3.5-sonnet', label: 'Claude 3.5 Sonnet' },
    { value: 'openai/gpt-4-turbo', label: 'GPT-4 Turbo' },
    { value: 'google/gemini-pro-1.5', label: 'Gemini Pro 1.5' },
  ]
};

// API 端点映射
export const PROVIDER_BASE_URLS = {
  deepseek: 'https://api.deepseek.com',
  openrouter: 'https://openrouter.ai/api'
};

// 辅助函数：从 localStorage 读取当前配置
export function getLLMConfig(): LLMConfig | null {
  if (typeof window === 'undefined') return null;
  const stored = localStorage.getItem(LLM_CONFIG_KEY);
  if (!stored) return null;
  try {
    const configs: LLMConfigs = JSON.parse(stored);
    const provider = configs.currentProvider;
    return configs[provider] || null;
  } catch {
    return null;
  }
}

// 辅助函数：保存配置到 localStorage
export function saveLLMConfig(config: LLMConfig): void {
  if (typeof window === 'undefined') return;
  const stored = localStorage.getItem(LLM_CONFIG_KEY);
  let configs: LLMConfigs;

  try {
    configs = stored ? JSON.parse(stored) : { currentProvider: config.provider };
  } catch {
    configs = { currentProvider: config.provider };
  }

  configs.currentProvider = config.provider;
  configs[config.provider] = config;
  localStorage.setItem(LLM_CONFIG_KEY, JSON.stringify(configs));
}

// 辅助函数：获取所有配置
export function getAllLLMConfigs(): LLMConfigs | null {
  if (typeof window === 'undefined') return null;
  const stored = localStorage.getItem(LLM_CONFIG_KEY);
  if (!stored) return null;
  try {
    return JSON.parse(stored);
  } catch {
    return null;
  }
}

// 辅助函数：清除配置
export function clearLLMConfig(): void {
  if (typeof window === 'undefined') return;
  localStorage.removeItem(LLM_CONFIG_KEY);
}
