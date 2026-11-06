package com.ratelimiter.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DashboardWebConfig implements WebMvcConfigurer {
    private final String[] origins;

    public DashboardWebConfig(@Value("${dashboard.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String[] origins) {
        this.origins = origins.clone();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**").allowedOrigins(origins).allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Request-Id").exposedHeaders("Retry-After", "X-Request-Id")
                .maxAge(3600);
    }
}
