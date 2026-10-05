package ao.creativemode.kixi.shared.exception;

import org.springframework.http.HttpStatus;

public final class RegistrationRateLimitException extends ApiException {

    private final long retryAfterSeconds;

    public RegistrationRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many registration attempts. Try again later.",
                "REGISTRATION_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
