package com.chatbi.config;

import com.chatbi.interceptor.LLMConfigInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;
    private final LLMConfigInterceptor llmConfigInterceptor;

    public WebConfig(RequestLoggingInterceptor requestLoggingInterceptor,
                     LLMConfigInterceptor llmConfigInterceptor) {
        this.requestLoggingInterceptor = requestLoggingInterceptor;
        this.llmConfigInterceptor = llmConfigInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/error");

        // 注意：由于 context-path 是 /api，这里的路径模式不需要包含 /api
        registry.addInterceptor(llmConfigInterceptor)
                .addPathPatterns("/**");  // 匹配所有路径
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")  // 使用 allowedOriginPatterns 替代 allowedOrigins，可与 allowCredentials 共存
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")  // 暴露所有响应头
                .allowCredentials(true);
    }
}
