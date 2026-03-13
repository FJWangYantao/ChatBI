import { getLLMConfig, PROVIDER_BASE_URLS } from "@/types/llm-config";

export async function executeSql(sql: string) {
  // 获取 LLM 配置
  const llmConfig = getLLMConfig();
  console.log('[executeSql] LLM 配置:', {
    hasConfig: !!llmConfig,
    provider: llmConfig?.provider,
    hasApiKey: !!llmConfig?.apiKey,
    model: llmConfig?.modelName
  });

  if (!llmConfig || !llmConfig.provider || !llmConfig.apiKey || !llmConfig.modelName) {
    throw new Error("请先在设置中配置 LLM 供应商和 API Key");
  }

  // 创建超时控制器（3分钟）
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 180000); // 180秒 = 3分钟

  try {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-LLM-Provider': llmConfig.provider,
      'X-LLM-API-Key': llmConfig.apiKey,
      'X-LLM-Model': llmConfig.modelName,
    };

    // 添加 Base URL（如果有配置则使用配置，否则使用默认值）
    const baseUrl = llmConfig.baseUrl || PROVIDER_BASE_URLS[llmConfig.provider];
    if (baseUrl) {
      headers['X-LLM-Base-URL'] = baseUrl;
    }

    console.log('[executeSql] 发送请求:', {
      url: 'http://localhost:8080/api/chat/execute-sql',
      headers: { ...headers, 'X-LLM-API-Key': '***' },
      sqlLength: sql.length
    });

    // 直接调用后端 API，绕过 Next.js rewrites（rewrites 不会转发自定义请求头）
    const response = await fetch('http://localhost:8080/api/chat/execute-sql', {
      method: 'POST',
      headers,
      body: JSON.stringify({ sql }),
      signal: controller.signal,
    });

    clearTimeout(timeoutId);

    console.log('[executeSql] 响应状态:', response.status, response.statusText);

    if (!response.ok) {
      const errorText = await response.text();
      console.error('[executeSql] 请求失败:', errorText);
      throw new Error('Failed to execute SQL');
    }

    const result = await response.json();
    console.log('[executeSql] 响应数据:', result);
    return result;
  } catch (error: any) {
    clearTimeout(timeoutId);
    console.error('[executeSql] 异常:', error);
    throw error;
  }
}

/**
 * 分页获取大数据集
 */
export async function fetchPagedData(refId: string, offset: number, limit: number) {
  const response = await fetch(`/api/chat/data/${refId}?offset=${offset}&limit=${limit}`);
  if (!response.ok) {
    throw new Error('Failed to fetch paged data');
  }
  return response.json();
}
