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

    /**
     * The window per address, and it is deliberately much looser than the one per account.
     *
     * <p>A school puts every student behind a single public address. At ten attempts a
     * minute, one student fumbling their password locks the whole school out until the
     * window passes, and it is an attacker who gets to choose the victim by simply being
     * next. Sixty per minute still stops a stranger going through a list of usernames,
     * which is what this key is for, without making it the account's defence.</p>
     */
    @Min(1)
    private int maxAttemptsPerAddress = 60;

    @Min(1)
    private long windowSeconds = 60;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getMaxAttemptsPerAddress() {
        return maxAttemptsPerAddress;
    }

    public void setMaxAttemptsPerAddress(int maxAttemptsPerAddress) {
        this.maxAttemptsPerAddress = maxAttemptsPerAddress;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}