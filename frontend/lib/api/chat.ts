export async function executeSql(sql: string) {
  // 创建超时控制器（3分钟）
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 180000); // 180秒 = 3分钟

  try {
    const response = await fetch('/api/chat/execute-sql', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ sql }),
      signal: controller.signal,
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      throw new Error('Failed to execute SQL');
    }

    return response.json();
  } catch (error: any) {
    clearTimeout(timeoutId);
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
