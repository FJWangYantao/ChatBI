/**
 * 数据源 API
 */

import { DataSource, TestConnectionResponse, DataSourceStats } from '@/types/datasource';

// 使用相对路径，通过 Next.js rewrites 代理到后端
const API_BASE_URL = '/api';

/**
 * 获取所有数据源
 */
export async function getAllDataSources(): Promise<DataSource[]> {
  const response = await fetch(`${API_BASE_URL}/datasource`);
  if (!response.ok) {
    throw new Error('获取数据源列表失败');
  }
  return response.json();
}

/**
 * 根据 ID 获取数据源
 */
export async function getDataSource(id: number): Promise<DataSource> {
  const response = await fetch(`${API_BASE_URL}/datasource/${id}`);
  if (!response.ok) {
    throw new Error('获取数据源失败');
  }
  return response.json();
}

/**
 * 获取当前激活的数据源
 */
export async function getActiveDataSource(): Promise<DataSource | null> {
  const response = await fetch(`${API_BASE_URL}/datasource/active`);
  if (response.status === 204) {
    return null;
  }
  if (!response.ok) {
    throw new Error('获取当前数据源失败');
  }
  return response.json();
}

/**
 * 添加数据源
 */
export async function addDataSource(dataSource: Omit<DataSource, 'id' | 'isActive' | 'createdAt' | 'updatedAt'>): Promise<DataSource> {
  const response = await fetch(`${API_BASE_URL}/datasource`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(dataSource),
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || '添加数据源失败');
  }

  return response.json();
}

/**
 * 更新数据源
 */
export async function updateDataSource(id: number, dataSource: Omit<DataSource, 'id' | 'isActive' | 'createdAt' | 'updatedAt'>): Promise<DataSource> {
  const response = await fetch(`${API_BASE_URL}/datasource/${id}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(dataSource),
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || '更新数据源失败');
  }

  return response.json();
}

/**
 * 删除数据源
 */
export async function deleteDataSource(id: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/datasource/${id}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || '删除数据源失败');
  }
}

/**
 * 激活指定数据源
 */
export async function activateDataSource(id: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/datasource/${id}/active`, {
    method: 'PUT',
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || '激活数据源失败');
  }
}

/**
 * 测试数据源连接
 */
export async function testConnection(dataSource: Omit<DataSource, 'id' | 'isActive' | 'createdAt' | 'updatedAt'>): Promise<TestConnectionResponse> {
  const response = await fetch(`${API_BASE_URL}/datasource/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(dataSource),
  });

  if (!response.ok) {
    throw new Error('测试连接失败');
  }

  return response.json();
}

/**
 * 获取数据源统计信息
 */
export async function getDataSourceStats(): Promise<DataSourceStats> {
  const response = await fetch(`${API_BASE_URL}/datasource/stats`);
  if (!response.ok) {
    throw new Error('获取统计信息失败');
  }
  return response.json();
}
