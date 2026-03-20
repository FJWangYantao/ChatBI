package com.chatbi.service;

import com.chatbi.entity.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态 JdbcTemplate 提供者
 * 负责为当前激活的数据源提供 JdbcTemplate
 */
@Slf4j
@Service
public class DynamicJdbcTemplateProvider {

    private final DynamicDataSourceService dynamicDataSourceService;
    private final ConcurrentHashMap<Long, JdbcTemplate> jdbcTemplateCache = new ConcurrentHashMap<>();

    public DynamicJdbcTemplateProvider(DynamicDataSourceService dynamicDataSourceService) {
        this.dynamicDataSourceService = dynamicDataSourceService;
    }

    /**
     * 获取当前激活数据源的 JdbcTemplate
     */
    public JdbcTemplate getJdbcTemplate() {
        Optional<DataSourceConfig> activeConfig = dynamicDataSourceService.getActiveDataSourceConfig();
        if (activeConfig.isEmpty()) {
            throw new IllegalStateException("没有激活的数据源，请先在数据源管理中激活一个数据源");
        }

        Long activeId = activeConfig.get().getId();
        return jdbcTemplateCache.computeIfAbsent(activeId, id -> {
            DataSource dataSource = dynamicDataSourceService.getDataSource(id);
            log.info("为数据源 {} 创建 JdbcTemplate", id);
            return new JdbcTemplate(dataSource);
        });
    }

    /**
     * 获取指定数据源的 JdbcTemplate
     */
    public JdbcTemplate getJdbcTemplate(Long dataSourceId) {
        return jdbcTemplateCache.computeIfAbsent(dataSourceId, id -> {
            DataSource dataSource = dynamicDataSourceService.getDataSource(id);
            log.info("为数据源 {} 创建 JdbcTemplate", id);
            return new JdbcTemplate(dataSource);
        });
    }

    /**
     * 清除指定数据源的 JdbcTemplate 缓存
     */
    public void clearCache(Long dataSourceId) {
        jdbcTemplateCache.remove(dataSourceId);
        log.info("清除数据源 {} 的 JdbcTemplate 缓存", dataSourceId);
    }

    /**
     * 清除所有 JdbcTemplate 缓存
     */
    public void clearAllCache() {
        jdbcTemplateCache.clear();
        log.info("清除所有 JdbcTemplate 缓存");
    }

    /**
     * 获取当前激活数据源的 ID
     */
    public Long getActiveDataSourceId() {
        Optional<DataSourceConfig> activeConfig = dynamicDataSourceService.getActiveDataSourceConfig();
        if (activeConfig.isEmpty()) {
            throw new IllegalStateException("没有激活的数据源");
        }
        return activeConfig.get().getId();
    }

    /**
     * 检查是否有激活的数据源
     */
    public boolean hasActiveDataSource() {
        return dynamicDataSourceService.getActiveDataSourceConfig().isPresent();
    }
}
