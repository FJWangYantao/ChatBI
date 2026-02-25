/**
 * MCP 业务术语知识库类型定义
 */

// 业务术语
export interface BusinessTerm {
  id?: number;
  term: string;
  category: string;
  definition: string;
  aliases: string[];
  examples: string[];
  created_at?: string;
  updated_at?: string;
}

// 列映射
export interface ColumnMapping {
  id?: number;
  term_id: number;
  table_name: string;
  column_name: string;
  data_type: string;
  description: string;
  sample_values: string[];
}

// 时间表达式
export interface TimeExpression {
  id?: number;
  pattern: string;
  expression_type: string;
  parse_rule: {
    type: string;
    start_month?: number;
    format?: string;
    description: string;
    [key: string]: any;
  };
  examples: string[];
}

// 统计信息
export interface MCPStats {
  total_terms: number;
  total_mappings: number;
  total_expressions: number;
  categories: { [key: string]: number };
}

// API 响应
export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
}
