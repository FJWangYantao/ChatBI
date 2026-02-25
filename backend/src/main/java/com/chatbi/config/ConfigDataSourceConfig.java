package com.chatbi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 配置数据库配置
 * 用于存储应用级别的配置信息，如数据源配置、用户配置等
 * 独立于业务数据源和对话数据源
 */
@Configuration
public class ConfigDataSourceConfig {

    @Value("${spring.config.datasource.url}")
    private String url;

    @Value("${spring.config.datasource.username}")
    private String username;

    @Value("${spring.config.datasource.password}")
    private String password;

    @Value("${spring.config.datasource.driver-class-name}")
    private String driverClassName;

    @Bean(name = "configDataSource")
    public DataSource configDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean(name = "configJdbcTemplate")
    public JdbcTemplate configJdbcTemplate() {
        return new JdbcTemplate(configDataSource());
    }
}
