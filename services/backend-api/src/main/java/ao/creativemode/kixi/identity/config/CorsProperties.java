package ao.creativemode.kixi.identity.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CORS origins configuration.
 *
 * <p>Bound from {@code app.cors.allowed-origins} (env
 * {@code APP_CORS_ALLOWED_ORIGINS}, comma-separated). Empty means no
 * browser cross-origin access beyond same-origin.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
    }
}
