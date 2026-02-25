/**
 * MCP 业务术语知识库 API 客户端
 */

import {
  BusinessTerm,
  ColumnMapping,
  TimeExpression,
  MCPStats,
  ApiResponse,
} from '@/types/mcp-terms';

// MCP 服务器地址（通过 Next.js proxy）
const MCP_API_BASE = '/api/mcp';

// ============ 业务术语 API ============

export async function getAllTerms(): Promise<BusinessTerm[]> {
  const response = await fetch(`${MCP_API_BASE}/terms`);
  if (!response.ok) throw new Error('获取术语列表失败');
  const data: ApiResponse<BusinessTerm[]> = await response.json();
  return data.data || [];
}

export async function getTerm(id: number): Promise<BusinessTerm> {
  const response = await fetch(`${MCP_API_BASE}/terms/${id}`);
  if (!response.ok) throw new Error('获取术语详情失败');
  const data: ApiResponse<BusinessTerm> = await response.json();
  if (!data.data) throw new Error('术语不存在');
  return data.data;
}

export async function createTerm(term: Omit<BusinessTerm, 'id' | 'created_at' | 'updated_at'>): Promise<BusinessTerm> {
  const response = await fetch(`${MCP_API_BASE}/terms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(term),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '创建术语失败');
  }
  const data: ApiResponse<BusinessTerm> = await response.json();
  if (!data.data) throw new Error('创建术语失败');
  return data.data;
}

export async function updateTerm(id: number, term: Partial<BusinessTerm>): Promise<BusinessTerm> {
  const response = await fetch(`${MCP_API_BASE}/terms/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(term),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '更新术语失败');
  }
  const data: ApiResponse<BusinessTerm> = await response.json();
  if (!data.data) throw new Error('更新术语失败');
  return data.data;
}

export async function deleteTerm(id: number): Promise<void> {
  const response = await fetch(`${MCP_API_BASE}/terms/${id}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '删除术语失败');
  }
}

// ============ 列映射 API ============

export async function getAllMappings(): Promise<ColumnMapping[]> {
  const response = await fetch(`${MCP_API_BASE}/mappings`);
  if (!response.ok) throw new Error('获取列映射失败');
  const data: ApiResponse<ColumnMapping[]> = await response.json();
  return data.data || [];
}

export async function getMappingsByTermId(termId: number): Promise<ColumnMapping[]> {
  const response = await fetch(`${MCP_API_BASE}/mappings?term_id=${termId}`);
  if (!response.ok) throw new Error('获取列映射失败');
  const data: ApiResponse<ColumnMapping[]> = await response.json();
  return data.data || [];
}

export async function createMapping(mapping: Omit<ColumnMapping, 'id'>): Promise<ColumnMapping> {
  const response = await fetch(`${MCP_API_BASE}/mappings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(mapping),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '创建列映射失败');
  }
  const data: ApiResponse<ColumnMapping> = await response.json();
  if (!data.data) throw new Error('创建列映射失败');
  return data.data;
}

export async function updateMapping(id: number, mapping: Partial<ColumnMapping>): Promise<ColumnMapping> {
  const response = await fetch(`${MCP_API_BASE}/mappings/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(mapping),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '更新列映射失败');
  }
  const data: ApiResponse<ColumnMapping> = await response.json();
  if (!data.data) throw new Error('更新列映射失败');
  return data.data;
}

export async function deleteMapping(id: number): Promise<void> {
  const response = await fetch(`${MCP_API_BASE}/mappings/${id}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '删除列映射失败');
  }
}

// ============ 时间表达式 API ============

export async function getAllExpressions(): Promise<TimeExpression[]> {
  const response = await fetch(`${MCP_API_BASE}/expressions`);
  if (!response.ok) throw new Error('获取时间表达式失败');
  const data: ApiResponse<TimeExpression[]> = await response.json();
  return data.data || [];
}

export async function createExpression(expr: Omit<TimeExpression, 'id'>): Promise<TimeExpression> {
  const response = await fetch(`${MCP_API_BASE}/expressions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(expr),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '创建时间表达式失败');
  }
  const data: ApiResponse<TimeExpression> = await response.json();
  if (!data.data) throw new Error('创建时间表达式失败');
  return data.data;
}

export async function updateExpression(id: number, expr: Partial<TimeExpression>): Promise<TimeExpression> {
  const response = await fetch(`${MCP_API_BASE}/expressions/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(expr),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '更新时间表达式失败');
  }
  const data: ApiResponse<TimeExpression> = await response.json();
  if (!data.data) throw new Error('更新时间表达式失败');
  return data.data;
}

export async function deleteExpression(id: number): Promise<void> {
  const response = await fetch(`${MCP_API_BASE}/expressions/${id}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || '删除时间表达式失败');
  }
}

// ============ 统计信息 API ============

export async function getStats(): Promise<MCPStats> {
  const response = await fetch(`${MCP_API_BASE}/stats`);
  if (!response.ok) throw new Error('获取统计信息失败');
  const data: ApiResponse<MCPStats> = await response.json();
  if (!data.data) throw new Error('获取统计信息失败');
  return data.data;
}
