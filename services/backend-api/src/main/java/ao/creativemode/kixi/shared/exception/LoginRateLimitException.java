package ao.creativemode.kixi.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * The login window is exhausted (issue #123: "rate limit em login").
 *
 * <p>Beside {@link RegistrationRateLimitException} rather than in the identity module:
 * {@code identity} has no exception package of its own, and the two throttles that guard
 * the same door read better side by side.</p>
 *
 * <p>The message says nothing about whether the account exists. A limiter that answers
 * "too many attempts for this user" on one username and not on another is an account
 * oracle, and it leaks before the password is ever checked.</p>
 */
public final class LoginRateLimitException extends ApiException implements RetryAfter {

    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many login attempts. Try again later.",
                "LOGIN_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}