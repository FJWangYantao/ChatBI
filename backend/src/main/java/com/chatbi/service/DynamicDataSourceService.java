package com.chatbi.service;

import com.chatbi.entity.DataSourceConfig;
import com.chatbi.repository.DataSourceConfigRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源服务
 * 负责创建、缓存和管理动态数据源
 */
@Slf4j
@Service
public class DynamicDataSourceService {

    private final DataSourceConfigRepository repository;
    private final Map<Long, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    private static final int DEFAULT_PORT = 3306;
    private static final int MAX_POOL_SIZE = 10;
    private static final long CONNECTION_TIMEOUT = 30000; // 30秒
    private static final long MAX_LIFETIME = 1800000; // 30分钟

    public DynamicDataSourceService(DataSourceConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 构建数据库连接 URL
     */
    private String buildUrl(DataSourceConfig config) {
        return String.format(
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                config.getHost(),
                config.getPort() != null ? config.getPort() : DEFAULT_PORT,
                config.getDbName()
        );
    }

    /**
     * 根据配置创建 Hikari 数据源
     */
    private DataSource createHikariDataSource(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildUrl(config));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 连接池配置
        hikariConfig.setMaximumPoolSize(MAX_POOL_SIZE);
        hikariConfig.setConnectionTimeout(CONNECTION_TIMEOUT);
        hikariConfig.setMaxLifetime(MAX_LIFETIME);
        hikariConfig.setMinimumIdle(2);

        // 测试连接
        hikariConfig.setConnectionTestQuery("SELECT 1");

        log.info("创建数据源: name={}, url={}", config.getName(), buildUrl(config));

        return new HikariDataSource(hikariConfig);
    }

    /**
     * 获取或创建指定 ID 的数据源
     */
    public DataSource getDataSource(Long id) {
        return dataSourceCache.computeIfAbsent(id, key -> {
            Optional<DataSourceConfig> configOpt = repository.findById(id);
            if (configOpt.isEmpty()) {
                throw new IllegalArgumentException("数据源不存在: id=" + id);
            }
            return createHikariDataSource(configOpt.get());
        });
    }

    /**
     * 获取当前激活的数据源
     */
    public DataSource getActiveDataSource() {
        Optional<DataSourceConfig> activeConfig = repository.findActive();
        if (activeConfig.isEmpty()) {
            throw new IllegalStateException("没有激活的数据源");
        }
        DataSourceConfig config = activeConfig.get();
        return getDataSource(config.getId());
    }

    /**
     * 获取当前激活的数据源配置
     */
    public Optional<DataSourceConfig> getActiveDataSourceConfig() {
        return repository.findActive();
    }

    /**
     * 测试数据源连接
     */
    public boolean testConnection(DataSourceConfig config) {
        DataSource testDataSource = createHikariDataSource(config);
        try (Connection connection = testDataSource.getConnection()) {
            boolean isValid = connection.isValid(5); // 5秒超时
            log.info("测试连接: name={}, result={}", config.getName(), isValid ? "成功" : "失败");
            return isValid;
        } catch (SQLException e) {
            log.error("测试连接失败: name={}, error={}", config.getName(), e.getMessage());
            return false;
        } finally {
            if (testDataSource instanceof HikariDataSource) {
                ((HikariDataSource) testDataSource).close();
            }
        }
    }

    /**
     * 添加数据源并缓存
     */
    public DataSourceConfig addDataSource(DataSourceConfig config) {
        // 检查名称是否已存在
        if (repository.existsByName(config.getName())) {
            throw new IllegalArgumentException("数据源名称已存在: " + config.getName());
        }

        DataSourceConfig saved = repository.save(config);
        log.info("添加数据源: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * 更新数据源配置
     */
    public DataSourceConfig updateDataSource(Long id, DataSourceConfig config) {
        // 检查名称是否与其他数据源冲突
        if (repository.existsByNameExcludingId(config.getName(), id)) {
            throw new IllegalArgumentException("数据源名称已存在: " + config.getName());
        }

        config.setId(id);
        DataSourceConfig updated = repository.save(config);

        // 清除缓存，下次获取时重新创建
        dataSourceCache.remove(id);
        log.info("更新数据源: id={}, name={}", id, config.getName());
        return updated;
    }

    /**
     * 删除数据源
     */
    public void deleteDataSource(Long id) {
        // 检查是否是当前激活的数据源
        Optional<DataSourceConfig> activeConfig = repository.findActive();
        if (activeConfig.isPresent() && activeConfig.get().getId().equals(id)) {
            throw new IllegalStateException("不能删除当前激活的数据源");
        }

        repository.deleteById(id);

        // 关闭并移除缓存
        DataSource cached = dataSourceCache.remove(id);
        if (cached instanceof HikariDataSource) {
            ((HikariDataSource) cached).close();
        }
        log.info("删除数据源: id={}", id);
    }

    /**
     * 激活指定数据源
     */
    public void activateDataSource(Long id) {
        Optional<DataSourceConfig> config = repository.findById(id);
        if (config.isEmpty()) {
            throw new IllegalArgumentException("数据源不存在: id=" + id);
        }

        repository.setActive(id);
        log.info("激活数据源: id={}, name={}", id, config.get().getName());
    }

    /**
     * 获取所有数据源配置
     */
    public java.util.List<DataSourceConfig> getAllDataSources() {
        return repository.findAll();
    }

    /**
     * 根据 ID 获取数据源配置
     */
    public Optional<DataSourceConfig> getDataSourceConfig(Long id) {
        return repository.findById(id);
    }

    /**
     * 清除所有数据源缓存
     */
    public void clearCache() {
        dataSourceCache.forEach((id, ds) -> {
            if (ds instanceof HikariDataSource) {
                ((HikariDataSource) ds).close();
            }
        });
        dataSourceCache.clear();
        log.info("清除所有数据源缓存");
    }

    /**
     * 构建服务器级别的连接 URL（不指定数据库）
     * 用于检查和创建数据库
     */
    private String buildServerUrl(DataSourceConfig config) {
        return String.format(
                "jdbc:mysql://%s:%d?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                config.getHost(),
                config.getPort() != null ? config.getPort() : DEFAULT_PORT
        );
    }

    /**
     * 检查数据库是否存在
     */
    public boolean databaseExists(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildServerUrl(config));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setConnectionTimeout(5000);

        try (HikariDataSource ds = new HikariDataSource(hikariConfig);
             Connection conn = ds.getConnection()) {
            // 查询数据库是否存在
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
                ps.setString(1, config.getDbName());
                try (ResultSet rs = ps.executeQuery()) {
                    boolean exists = rs.next() && rs.getInt(1) > 0;
                    log.debug("检查数据库存在性: {} = {}", config.getDbName(), exists);
                    return exists;
                }
            }
        } catch (SQLException e) {
            log.error("检查数据库存在性失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建数据库
     */
    public boolean createDatabase(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildServerUrl(config));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setConnectionTimeout(5000);

        try (HikariDataSource ds = new HikariDataSource(hikariConfig);
             Connection conn = ds.getConnection()) {
            // 创建数据库
            try (Statement stmt = conn.createStatement()) {
                String sql = String.format("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", config.getDbName());
                stmt.execute(sql);
                log.info("成功创建数据库: {}", config.getDbName());
                return true;
            }
        } catch (SQLException e) {
            log.error("创建数据库失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 确保数据库存在，不存在则创建
     */
    public boolean ensureDatabaseExists(DataSourceConfig config) {
        if (databaseExists(config)) {
            log.debug("数据库已存在: {}", config.getDbName());
            return true;
        }
        log.info("数据库不存在，尝试创建: {}", config.getDbName());
        return createDatabase(config);
    }
}
