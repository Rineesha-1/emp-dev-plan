package com.devplan.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.devplan.engine.TenantRegistrar;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final TenantRegistrar tenantRegistrar;

    public WebConfig(TenantRegistrar tenantRegistrar) {
        this.tenantRegistrar = tenantRegistrar;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantRegistrar).addPathPatterns("/api/**").excludePathPatterns("/api/config");
    }
}
