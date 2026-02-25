/**
 * 文件导入相关类型定义
 */

export interface FileImportRequest {
  dataSourceId: number;
  tableName: string;
  skipIfExists?: boolean;
  firstRowAsHeader?: boolean;
}

export interface FileImportResponse {
  success: boolean;
  message: string;
  tableName?: string;
  rowsImported?: number;
  columnsCreated?: number;
}
