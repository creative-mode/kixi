package ao.creativemode.kixi.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * A client (or the whole platform) has used its donation budget (issue #252). 429 with
 * {@code Retry-After}, like the other throttles. The message does not say which window tripped:
 * telling a script that the hourly bucket is full and the daily one is not is a map for it.
 */
public final class DonationRateLimitException extends ApiException implements RetryAfter {

    private final long retryAfterSeconds;

    public DonationRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many donations. Try again later.",
                "DONATION_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
