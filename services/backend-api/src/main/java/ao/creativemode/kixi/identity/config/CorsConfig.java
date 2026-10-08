package ao.creativemode.kixi.identity.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Builds the reactive {@link CorsConfigurationSource} from {@link CorsProperties}.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    static final List<String> ALLOWED_METHODS = Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    static final List<String> ALLOWED_HEADERS = Arrays.asList("Authorization", "Content-Type", "X-Request-Id");
    static final List<String> EXPOSED_HEADERS = Arrays.asList("Retry-After", "X-Request-ID");

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : properties.getAllowedOrigins()) {
            String trimmed = origin == null ? "" : origin.trim();
            if (!trimmed.isEmpty()) {
                config.addAllowedOrigin(trimmed);
            }
        }
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
