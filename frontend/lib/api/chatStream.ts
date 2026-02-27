/**
 * SSE 流式聊天客户端
 * 使用 fetch + ReadableStream 解析 SSE（支持 POST 请求）
 */

// 暂停/恢复控制器，用于控制 SSE 读取循环
export class PauseController {
  private _paused = false;
  private _resolve: (() => void) | null = null;

  get paused() { return this._paused; }

  pause() {
    this._paused = true;
  }

  resume() {
    this._paused = false;
    this._resolve?.();
    this._resolve = null;
  }

  // 在读取循环中调用，暂停时阻塞
  async waitIfPaused(): Promise<void> {
    if (!this._paused) return;
    return new Promise(resolve => { this._resolve = resolve; });
  }
}

export interface StreamCallbacks {
  onStatus?: (data: { stage: string; message: string; progress: number; totalSteps: number }) => void;
  onIntent?: (data: { category: string; categoryCn: string; categoryConfidence: number; subtype: string; subtypeConfidence: number; subtypeCn: string }) => void;
  onTextDelta?: (data: { delta: string }) => void;
  onTag?: (data: { type: string; content: any; title?: string; metadata?: any }) => void;
  onTagStart?: (data: { id: string; type: string; title: string }) => void;
  onTagDelta?: (data: { id: string; delta: string }) => void;
  onTagEnd?: (data: { id: string; tag: { type: string; content: any; title?: string; metadata?: any } }) => void;
  onSuggestions?: (data: { items: string[] }) => void;
  onReasoning?: (data: { step: string; content: string; stepIndex: number }) => void;
  onStepResult?: (data: { stepName: string; stepLabel: string; duration: number; status: string; result: any }) => void;
  onCodeExecution?: (data: {
    executionId: string;
    stage: string;
    code?: string;
    stdout?: string;
    stderr?: string;
    success?: boolean;
    executionTime?: number;
  }) => void;
  onDone?: (data: { conversationId: string; totalDuration: number }) => void;
  onError?: (data: { code: string; message: string; stage: string }) => void;
}

/**
 * 解析 SSE 文本流，调用对应 callback
 */
function processSSELine(eventType: string, dataStr: string, callbacks: StreamCallbacks) {
  try {
    const data = JSON.parse(dataStr);
    switch (eventType) {
      case 'status':
        callbacks.onStatus?.(data);
        break;
      case 'intent':
        callbacks.onIntent?.(data);
        break;
      case 'text_delta':
        callbacks.onTextDelta?.(data);
        break;
      case 'tag':
        callbacks.onTag?.(data);
        break;
      case 'suggestions':
        callbacks.onSuggestions?.(data);
        break;
      case 'reasoning':
        callbacks.onReasoning?.(data);
        break;
      case 'step_result':
        callbacks.onStepResult?.(data);
        break;
      case 'tag_start':
        callbacks.onTagStart?.(data);
        break;
      case 'tag_delta':
        callbacks.onTagDelta?.(data);
        break;
      case 'tag_end':
        callbacks.onTagEnd?.(data);
        break;
      case 'code_execution':
        console.log('[SSE] code_execution 事件到达:', data);
        callbacks.onCodeExecution?.(data);
        break;
      case 'done':
        callbacks.onDone?.(data);
        break;
      case 'error':
        callbacks.onError?.(data);
        break;
    }
  } catch (e) {
    console.warn('SSE 解析失败:', eventType, dataStr, e);
  }
}

/**
 * 发送流式聊天请求
 */
export async function streamChatMessage(
  message: string,
  conversationId: string | null,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
  agentType?: string,
  pauseController?: PauseController
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, conversationId, agentType }),
    signal,
  });

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('Response body is not readable');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';

  try {
    while (true) {
      await pauseController?.waitIfPaused();
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 按行解析 SSE 协议
      const lines = buffer.split('\n');
      // 最后一行可能不完整，保留在 buffer 中
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const dataStr = line.slice(5).trim();
          if (currentEvent && dataStr) {
            processSSELine(currentEvent, dataStr, callbacks);
          }
          // 每对 event+data 之后重置
          currentEvent = '';
        }
        // 忽略空行和注释行
      }
    }

    // 处理 buffer 中可能残留的数据
    if (buffer.trim()) {
      const remainingLines = buffer.split('\n');
      for (const line of remainingLines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const dataStr = line.slice(5).trim();
          if (currentEvent && dataStr) {
            processSSELine(currentEvent, dataStr, callbacks);
          }
          currentEvent = '';
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
