package com.orderfulfillment.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Vite dev server (a different origin/port) to call this API during local development.
 *
 * <p>Origin patterns are driven by {@code app.cors.allowed-origin-patterns} (comma-separated),
 * defaulting to {@code http://localhost:*} so every existing local Compose/{@code kind} flow keeps
 * working unchanged. Override via the {@code APP_CORS_ALLOWED_ORIGIN_PATTERNS} environment variable
 * for any deployment where the frontend is not served same-origin. Never set this to {@code *} in a
 * production-facing default, and never combine a wildcard with credentials.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    WebConfig(@Value("${app.cors.allowed-origin-patterns:http://localhost:*}") String allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
