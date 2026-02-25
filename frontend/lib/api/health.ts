/**
 * 服务健康检查 API
 */
export interface ServiceStatus {
  status: 'ok' | 'error';
  port: number;
  message?: string;
}

export interface ServicesHealth {
  backend: ServiceStatus;
  intent: ServiceStatus;
  ner: ServiceStatus;
  sandbox: ServiceStatus;
}

export async function checkServicesHealth(): Promise<ServicesHealth> {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    const response = await fetch('/api/health/services', {
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    return await response.json();
  } catch {
    // 后端本身不可达
    return {
      backend: { status: 'error', port: 8080, message: '无法连接' },
      intent: { status: 'error', port: 8001, message: '未知' },
      ner: { status: 'error', port: 8002, message: '未知' },
      sandbox: { status: 'error', port: 8003, message: '未知' },
    };
  }
}
