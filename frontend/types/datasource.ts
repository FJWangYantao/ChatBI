/**
 * 数据源配置类型
 */
export interface DataSource {
  id?: number;
  name: string;           // 数据源名称
  host: string;           // 主机地址
  port: number;           // 端口
  dbName: string;         // 数据库名
  username: string;       // 用户名
  password: string;       // 密码
  isActive?: boolean;     // 是否当前激活
  createdAt?: string;     // 创建时间
  updatedAt?: string;     // 更新时间
}

/**
 * 测试连接响应
 */
export interface TestConnectionResponse {
  success: boolean;
  message: string;
}

/**
 * 数据源统计信息
 */
export interface DataSourceStats {
  total: number;
  active: number;
  inactive: number;
}
