package ao.creativemode.kixi.shared.exception;

import org.springframework.http.HttpStatus;

/** Rate limit response shared by authentication and expensive upload endpoints. */
public final class ApiAbuseRateLimitException extends ApiException implements RetryAfter {

    private final long retryAfterSeconds;

    public ApiAbuseRateLimitException(String scope, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many " + scope + " requests. Try again later.",
                "API_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
