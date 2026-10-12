package ao.creativemode.kixi.feed.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Sliding window for feed writes per account (issue #120, review point 7:
 * the original filter crashed on every POST and, being in-memory, grew
 * without bound). Bound from the environment as e.g.
 * {@code APP_FEED_RATE_LIMIT_MAX_ATTEMPTS} → {@code app.feed.rate-limit.max-attempts}.
 */
@Component
@ConfigurationProperties(prefix = "app.feed.rate-limit")
@Validated
public class FeedRateLimitProperties {

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
