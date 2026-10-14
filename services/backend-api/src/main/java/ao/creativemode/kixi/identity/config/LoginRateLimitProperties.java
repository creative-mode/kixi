package ao.creativemode.kixi.identity.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * The login window (issue #123).
 *
 * <p>Ten attempts a minute, against five in fifteen minutes for registration. Login is
 * looser on purpose: a registration is a one-off and a login is something a person does
 * repeatedly, several times a day, and a tighter window locks out the person using the
 * platform rather than the person guessing a password.</p>
 *
 * <p>Overridable in production through
 * {@code APP_AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS} and
 * {@code APP_AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS}, without a rebuild.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.auth.login-rate-limit")
@Validated
public class LoginRateLimitProperties {

    @Min(1)
    private int maxAttempts = 10;

    @Min(1)
    private long windowSeconds = 60;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}