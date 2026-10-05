package ao.creativemode.kixi.identity.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Aborts startup in the {@code prod} profile when the JWT secret is
 * missing, too short or a well-known placeholder.
 *
 * <p>{@link JwtProperties} already enforces non-blank and min length via
 * bean validation; this guard adds an explicit placeholder check with a
 * profile-specific message so misconfiguration fails fast in production.</p>
 */
@Component
@Profile("prod")
public class ProdJwtSecretGuard {

    static final Set<String> PLACEHOLDERS = new HashSet<>(Arrays.asList(
            "changeme", "change-me", "change_me", "default", "secret",
            "password", "123456", "test", "test-only", "example"));

    private final JwtProperties jwtProperties;

    public ProdJwtSecretGuard(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void validate() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Refusing to start with 'prod' profile: app.jwt.secret (APP_JWT_SECRET) must be configured");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "Refusing to start with 'prod' profile: app.jwt.secret must contain at least 32 characters");
        }
        if (isPlaceholder(secret)) {
            throw new IllegalStateException(
                    "Refusing to start with 'prod' profile: app.jwt.secret uses a default/placeholder value");
        }
    }

    static boolean isPlaceholder(String secret) {
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(normalized)) {
            return true;
        }
        for (String placeholder : PLACEHOLDERS) {
            if (normalized.startsWith(placeholder + "-")
                    || normalized.startsWith(placeholder + "_")
                    || normalized.startsWith(placeholder + " ")) {
                return true;
            }
        }
        return false;
    }
}
