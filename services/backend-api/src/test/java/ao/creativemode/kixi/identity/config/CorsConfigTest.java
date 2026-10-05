package ao.creativemode.kixi.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;

class CorsConfigTest {

    private final CorsConfig corsConfig = new CorsConfig();

    private static CorsConfiguration resolve(CorsConfig config, CorsProperties properties) {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/v1/auth/login").header("Origin", "https://aluno.kixi.ao"));
        return config.corsConfigurationSource(properties).getCorsConfiguration(exchange);
    }

    @Test
    void buildsSourceWithConfiguredOrigins() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://aluno.kixi.ao", " https://manager.kixi.ao "));

        CorsConfiguration resolved = resolve(corsConfig, properties);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getAllowedOrigins())
                .containsExactly("https://aluno.kixi.ao", "https://manager.kixi.ao");
        assertThat(resolved.getAllowedMethods())
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(resolved.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "X-Request-Id");
        assertThat(resolved.getExposedHeaders())
                .contains("Retry-After", "X-Request-ID");
        assertThat(resolved.getAllowCredentials()).isTrue();
    }

    @Test
    void ignoresBlankOrigins() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("  ", ""));

        assertThat(resolve(corsConfig, properties).getAllowedOrigins()).isNullOrEmpty();
    }

    @Test
    void emptyOriginsMeansSameOriginOnly() {
        CorsProperties properties = new CorsProperties();

        assertThat(resolve(corsConfig, properties).getAllowedOrigins()).isNullOrEmpty();
    }

    @Test
    void nullListIsTolerated() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(null);

        assertThat(resolve(corsConfig, properties)).isNotNull();
    }
}
