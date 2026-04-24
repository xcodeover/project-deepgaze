package com.deepgaze.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React dev server. Only meaningful when the browser talks to
 * the backend directly; the Vite dev server usually proxies /api so CORS
 * isn't exercised. Still configured here so that a non-proxied browser
 * session (e.g. hitting http://localhost:8080 directly, or a production
 * gateway that forwards the Origin) gets correct preflight responses.
 *
 * POST is required for /api/ops/**; all other /api/** endpoints accept
 * GET only and Spring MVC will 405 non-GET verbs there.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "X-Deepgaze-Auth")
                .maxAge(3600);
    }
}
