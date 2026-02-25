package com.chatbi.repository;

import com.chatbi.entity.DataSourceConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 数据源配置 Repository
 * 使用 config 数据源存储数据源配置信息
 */
@Repository
public class DataSourceConfigRepository {

    private final JdbcTemplate configJdbcTemplate;

    public DataSourceConfigRepository(
            @Qualifier("configJdbcTemplate") JdbcTemplate configJdbcTemplate) {
        this.configJdbcTemplate = configJdbcTemplate;
        // 初始化表
        initTable();
    }

    private final RowMapper<DataSourceConfig> rowMapper = new RowMapper<DataSourceConfig>() {
        @Override
        public DataSourceConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            return DataSourceConfig.builder()
                    .id(rs.getLong("id"))
                    .name(rs.getString("name"))
                    .host(rs.getString("host"))
                    .port(rs.getInt("port"))
                    .dbName(rs.getString("db_name"))
                    .username(rs.getString("username"))
                    .password(rs.getString("password"))
                    .isActive(rs.getBoolean("is_active"))
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                    .build();
        }
    };

    /**
     * 初始化数据源配置表
     */
    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS datasource_config (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(100) NOT NULL COMMENT '数据源名称',
                host VARCHAR(255) NOT NULL COMMENT '主机地址',
                port INT NOT NULL DEFAULT 3306 COMMENT '端口',
                db_name VARCHAR(100) NOT NULL COMMENT '数据库名',
                username VARCHAR(100) NOT NULL COMMENT '用户名',
                password VARCHAR(500) NOT NULL COMMENT '密码（加密存储）',
                is_active BOOLEAN DEFAULT FALSE COMMENT '是否当前激活',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                UNIQUE KEY uk_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表'
            """;
        configJdbcTemplate.execute(sql);
    }

    /**
     * 保存数据源配置
     */
    public DataSourceConfig save(DataSourceConfig config) {
        if (config.getId() == null) {
            // 插入新数据源
            configJdbcTemplate.update(
                    "INSERT INTO datasource_config (name, host, port, db_name, username, password, is_active) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    config.getName(), config.getHost(), config.getPort(),
                    config.getDbName(), config.getUsername(), config.getPassword(),
                    config.getIsActive() != null ? config.getIsActive() : false
            );
            // 返回最新插入的记录
            List<DataSourceConfig> results = configJdbcTemplate.query(
                    "SELECT * FROM datasource_config ORDER BY id DESC LIMIT 1", rowMapper);
            return results.isEmpty() ? null : results.get(0);
        } else {
            // 更新数据源
            configJdbcTemplate.update(
                    "UPDATE datasource_config SET name = ?, host = ?, port = ?, db_name = ?, username = ?, password = ?, is_active = ?, updated_at = ? WHERE id = ?",
                    config.getName(), config.getHost(), config.getPort(),
                    config.getDbName(), config.getUsername(), config.getPassword(),
                    config.getIsActive(), LocalDateTime.now(), config.getId()
            );
            return findById(config.getId()).orElse(null);
        }
    }

    /**
     * 根据 ID 查找
     */
    public Optional<DataSourceConfig> findById(Long id) {
        String sql = "SELECT * FROM datasource_config WHERE id = ?";
        List<DataSourceConfig> results = configJdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 查找所有数据源
     */
    public List<DataSourceConfig> findAll() {
        String sql = "SELECT * FROM datasource_config ORDER BY created_at DESC";
        return configJdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 查找当前激活的数据源
     */
    public Optional<DataSourceConfig> findActive() {
        String sql = "SELECT * FROM datasource_config WHERE is_active = TRUE LIMIT 1";
        List<DataSourceConfig> results = configJdbcTemplate.query(sql, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 根据 ID 删除
     */
    public void deleteById(Long id) {
        configJdbcTemplate.update("DELETE FROM datasource_config WHERE id = ?", id);
    }

    /**
     * 激活指定数据源，同时取消其他数据源的激活状态
     */
    public void setActive(Long id) {
        // 先取消所有数据源的激活状态
        configJdbcTemplate.update("UPDATE datasource_config SET is_active = FALSE");
        // 激活指定数据源
        configJdbcTemplate.update("UPDATE datasource_config SET is_active = TRUE, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
    }

    /**
     * 取消所有数据源的激活状态
     */
    public void deactivateAll() {
        configJdbcTemplate.update("UPDATE datasource_config SET is_active = FALSE");
    }

    /**
     * 根据名称查找
     */
    public Optional<DataSourceConfig> findByName(String name) {
        String sql = "SELECT * FROM datasource_config WHERE name = ?";
        List<DataSourceConfig> results = configJdbcTemplate.query(sql, rowMapper, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 检查名称是否已存在
     */
    public boolean existsByName(String name) {
        String sql = "SELECT COUNT(*) FROM datasource_config WHERE name = ?";
        Integer count = configJdbcTemplate.queryForObject(sql, Integer.class, name);
        return count != null && count > 0;
    }

    /**
     * 检查名称是否已存在（排除指定 ID）
     */
    public boolean existsByNameExcludingId(String name, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM datasource_config WHERE name = ? AND id != ?";
        Integer count = configJdbcTemplate.queryForObject(sql, Integer.class, name, excludeId);
        return count != null && count > 0;
    }
}
