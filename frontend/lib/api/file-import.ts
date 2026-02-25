/**
 * 文件导入 API
 */

import { FileImportResponse } from '@/types/file-import';

const API_BASE_URL = '/api';

/**
 * 导入文件到指定数据源
 */
export async function importFile(
  file: File,
  dataSourceId: number,
  tableName: string,
  skipIfExists = true,
  firstRowAsHeader = true
): Promise<FileImportResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('dataSourceId', dataSourceId.toString());
  formData.append('tableName', tableName);
  formData.append('skipIfExists', skipIfExists.toString());
  formData.append('firstRowAsHeader', firstRowAsHeader.toString());

  const response = await fetch(`${API_BASE_URL}/file-import`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    throw new Error('文件导入失败');
  }

  return response.json();
}
