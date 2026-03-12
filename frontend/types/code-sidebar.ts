// frontend/types/code-sidebar.ts

// SQL 查询结果类型
export interface QueryResult {
  columns: string[];
  rows: Array<Record<string, any>>;
  totalRows: number;
  dataRefId?: string;
  executionTime?: number;
  success: boolean;
  error?: string;
}

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
  // SQL 类型特有字段
  queryResult?: QueryResult;
}
