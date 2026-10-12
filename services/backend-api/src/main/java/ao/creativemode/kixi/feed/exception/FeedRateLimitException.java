package ao.creativemode.kixi.feed.exception;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.RetryAfter;
import org.springframework.http.HttpStatus;

/**
 * The per-account window on feed writes is exhausted (issue #120, review
 * point 7). Extends {@link ApiException} so the global handler renders it as
 * a problem detail, and {@link RetryAfter} so that handler adds the header -
 * the same shape as {@code ChatRateLimitException}.
 */
public final class FeedRateLimitException extends ApiException implements RetryAfter {

    private final long retryAfterSeconds;

    public FeedRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many feed requests. Try again later.",
                "FEED_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
