export type LLMProvider = 'deepseek' | 'openrouter';

export interface LLMConfig {
  provider: LLMProvider;
  apiKey: string;
  modelName: string;
  baseUrl?: string;
}

interface LLMConfigStore {
  activeProvider: LLMProvider;
  configs: Partial<Record<LLMProvider, Omit<LLMConfig, 'provider'>>>;
}

export const LLM_CONFIG_KEY = 'chatbi_llm_config';

export const MODEL_PRESETS: Record<LLMProvider, Array<{ value: string; label: string }>> = {
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

export const PROVIDER_BASE_URLS: Record<LLMProvider, string> = {
  deepseek: 'https://api.deepseek.com',
  openrouter: 'https://openrouter.ai/api/v1'
};

export function getDefaultModelName(provider: LLMProvider): string {
  return MODEL_PRESETS[provider][0]?.value || '';
}

export function getDefaultLLMConfig(provider: LLMProvider = 'deepseek'): LLMConfig {
  return {
    provider,
    apiKey: '',
    modelName: getDefaultModelName(provider),
    baseUrl: undefined,
  };
}

function isProvider(value: unknown): value is LLMProvider {
  return value === 'deepseek' || value === 'openrouter';
}

function normalizeConfig(provider: LLMProvider, config?: Partial<Omit<LLMConfig, 'provider'>> | null): LLMConfig {
  return {
    provider,
    apiKey: config?.apiKey || '',
    modelName: config?.modelName || getDefaultModelName(provider),
    baseUrl: config?.baseUrl || undefined,
  };
}

function parseStore(stored: string): LLMConfigStore | null {
  try {
    const parsed = JSON.parse(stored) as Partial<LLMConfigStore & LLMConfig>;

    if (parsed && typeof parsed === 'object' && 'configs' in parsed) {
      const activeProvider = isProvider(parsed.activeProvider) ? parsed.activeProvider : 'deepseek';
      const configs = parsed.configs && typeof parsed.configs === 'object' ? parsed.configs : {};
      return {
        activeProvider,
        configs: {
          deepseek: configs.deepseek,
          openrouter: configs.openrouter,
        },
      };
    }

    if (parsed && isProvider(parsed.provider)) {
      const legacyConfig = normalizeConfig(parsed.provider, parsed);
      return {
        activeProvider: legacyConfig.provider,
        configs: {
          [legacyConfig.provider]: {
            apiKey: legacyConfig.apiKey,
            modelName: legacyConfig.modelName,
            baseUrl: legacyConfig.baseUrl,
          },
        },
      };
    }
  } catch {
    return null;
  }

  return null;
}

export function getLLMConfigStore(): LLMConfigStore | null {
  if (typeof window === 'undefined') return null;
  const stored = localStorage.getItem(LLM_CONFIG_KEY);
  if (!stored) return null;
  return parseStore(stored);
}

export function getLLMConfig(provider?: LLMProvider): LLMConfig | null {
  const store = getLLMConfigStore();
  if (!store) return null;

  const targetProvider = provider || store.activeProvider;
  if (!isProvider(targetProvider)) return null;

  return normalizeConfig(targetProvider, store.configs[targetProvider]);
}

/**
 * 直接读取指定供应商的原始配置（不做 normalize）
 * 返回 undefined 表示该供应商从未配置过
 */
export function getRawProviderConfig(provider: LLMProvider): Omit<LLMConfig, 'provider'> | undefined {
  const store = getLLMConfigStore();
  if (!store) return undefined;
  return store.configs[provider];
}

export function saveLLMConfig(config: LLMConfig): void {
  if (typeof window === 'undefined') return;

  const store = getLLMConfigStore() || {
    activeProvider: config.provider,
    configs: {},
  };

  const nextStore: LLMConfigStore = {
    activeProvider: config.provider,
    configs: {
      ...store.configs,
      [config.provider]: {
        apiKey: config.apiKey,
        modelName: config.modelName,
        baseUrl: config.baseUrl,
      },
    },
  };

  localStorage.setItem(LLM_CONFIG_KEY, JSON.stringify(nextStore));
}

export function clearLLMConfig(provider?: LLMProvider): void {
  if (typeof window === 'undefined') return;

  if (!provider) {
    localStorage.removeItem(LLM_CONFIG_KEY);
    return;
  }

  const store = getLLMConfigStore();
  if (!store) return;

  const nextConfigs = { ...store.configs };
  delete nextConfigs[provider];

  const remainingProviders = (Object.keys(nextConfigs) as LLMProvider[]).filter((key) => {
    const config = nextConfigs[key];
    return !!(config && (config.apiKey || config.modelName || config.baseUrl));
  });

  if (remainingProviders.length === 0) {
    localStorage.removeItem(LLM_CONFIG_KEY);
    return;
  }

  const nextActiveProvider = store.activeProvider === provider
    ? remainingProviders[0]
    : store.activeProvider;

  localStorage.setItem(LLM_CONFIG_KEY, JSON.stringify({
    activeProvider: nextActiveProvider,
    configs: nextConfigs,
  } satisfies LLMConfigStore));
}
