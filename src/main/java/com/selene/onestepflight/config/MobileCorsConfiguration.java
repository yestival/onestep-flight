package com.selene.onestepflight.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MobileCorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public MobileCorsConfiguration(@Value("${app.cors.allowed-origins:capacitor://localhost,https://localhost}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).peek(MobileCorsConfiguration::validate).toArray(String[]::new);
    }

    private static void validate(String value) {
        URI uri = URI.create(value);
        if (value.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !java.util.Set.of("https", "http", "capacitor").contains(uri.getScheme())) {
            throw new IllegalArgumentException("CORS origins must be explicit origins without paths or wildcards");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins)
                .allowedMethods("OPTIONS", "GET", "POST", "PUT", "DELETE")
                .allowedHeaders("Content-Type", "Accept", "Authorization", "X-Share-Update-Token")
                .allowCredentials(false).maxAge(3600);
    }
}
