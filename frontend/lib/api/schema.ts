/**
 * Schema API
 */

import { SchemaResponse, TableMetadata } from '@/types/schema';

const API_BASE_URL = '/api';

/**
 * 获取当前激活数据源的完整 Schema
 */
export async function getDatabaseSchema(): Promise<SchemaResponse> {
  const response = await fetch(`${API_BASE_URL}/schema/database`);
  if (!response.ok) {
    throw new Error('获取数据库 Schema 失败');
  }
  return response.json();
}

/**
 * 获取指定表的详细 Schema
 */
export async function getTableSchema(tableName: string): Promise<TableMetadata> {
  const response = await fetch(`${API_BASE_URL}/schema/table/${encodeURIComponent(tableName)}`);
  if (!response.ok) {
    throw new Error('获取表 Schema 失败');
  }
  return response.json();
}
