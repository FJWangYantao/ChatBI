// frontend/types/code-sidebar.ts
export interface CodeEntry {
  id: string;
  type: 'sql' | 'python' | 'execution';
  code: string;
  title: string;
  timestamp: number;
  messageId: string;
  isStreaming: boolean;
  // execution 类型特有字段
  executionId?: string;
  stage?: string;
  stdout?: string;
  stderr?: string;
  success?: boolean;
  executionTime?: number;
}
