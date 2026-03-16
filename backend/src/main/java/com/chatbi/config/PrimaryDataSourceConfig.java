package com.chatbi.config;

import org.springframework.beans.factory.annotation.Value;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 主数据源配置
 * 用于 Text2SQL 查询，连接到销售数据库
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    /**
     * 主数据源 - 连接到销售数据库
     * 标记为 @Primary 确保这是默认的数据源
     */
    @Bean(name = "primaryDataSource")
    @Primary
    public DataSource primaryDataSource() {
        HikariDataSource ds = (HikariDataSource) DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .type(HikariDataSource.class)
                .build();
        ds.setConnectionInitSql("SET NAMES utf8mb4");
        return ds;
    }

    /**
     * 主 JdbcTemplate - 用于执行 SQL 查询
     * 标记为 @Primary 确保这是默认的 JdbcTemplate
     */
    @Bean(name = "primaryJdbcTemplate")
    @Primary
    public JdbcTemplate primaryJdbcTemplate() {
        return new JdbcTemplate(primaryDataSource());
    }
}
