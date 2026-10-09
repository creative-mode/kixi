package ao.creativemode.kixi.chat.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Sliding window for tutor messages per account (issue #114: "rate limit por
 * aluno"). Bound from the environment as e.g.
 * {@code APP_CHAT_RATE_LIMIT_MAX_ATTEMPTS} → {@code app.chat.rate-limit.max-attempts}.
 */
@Component
@ConfigurationProperties(prefix = "app.chat.rate-limit")
@Validated
public class ChatRateLimitProperties {

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
