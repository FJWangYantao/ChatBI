package com.chatbi.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据源配置实体类
 * 对应数据库表: datasource_config
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceConfig {
    private Long id;
    private String name;           // 数据源名称（如：销售数据、库存数据）
    private String host;           // 主机地址
    private Integer port;          // 端口
    private String dbName;         // 数据库名（字段名改为 dbName 避免 MySQL 保留关键字）
    private String username;       // 用户名
    private String password;       // 密码（加密存储）
    private Boolean isActive;      // 是否当前激活
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
