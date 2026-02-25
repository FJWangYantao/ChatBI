package com.chatbi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 对话数据库配置
 * 独立于主数据源(销售数据)
 */
@Configuration
public class ConversationDataSourceConfig {

    @Value("${spring.conversation.datasource.url}")
    private String url;

    @Value("${spring.conversation.datasource.username}")
    private String username;

    @Value("${spring.conversation.datasource.password}")
    private String password;

    @Value("${spring.conversation.datasource.driver-class-name}")
    private String driverClassName;

    @Bean(name = "conversationDataSource")
    public DataSource conversationDataSource() {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean(name = "conversationJdbcTemplate")
    public JdbcTemplate conversationJdbcTemplate() {
        return new JdbcTemplate(conversationDataSource());
    }
}
