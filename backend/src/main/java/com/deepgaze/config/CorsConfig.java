package com.deepgaze.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * CORS for the React dev server. Limits exposure to GET (the only verb the
 * frontend needs) and to the local dev origins. Tighten / parameterise per
 * environment when deploying behind a real gateway.
 */
@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET")
                .maxAge(3600);
    }
}
