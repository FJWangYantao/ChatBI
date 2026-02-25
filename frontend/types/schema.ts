0/**
 * Schema 相关类型定义
 */

/**
 * 列元数据
 */
export interface ColumnMetadata {
  columnName: string;
  dataType: string;
  columnSize?: number | null;
  nullable: boolean;
  columnDefault?: string | null;
  columnComment?: string | null;
  isPrimaryKey: boolean;
}

/**
 * 外键关系
 */
export interface ForeignKeyMetadata {
  columnName: string;
  referencedTableName: string;
  referencedColumnName: string;
  constraintName: string;
}

/**
 * 表元数据
 */
export interface TableMetadata {
  tableName: string;
  tableComment?: string | null;
  columns: ColumnMetadata[];
  foreignKeys: ForeignKeyMetadata[];
}

/**
 * Schema API 响应
 */
export interface SchemaResponse {
  databaseName: string;
  tables: TableMetadata[];
  formattedForAI: string;
}
