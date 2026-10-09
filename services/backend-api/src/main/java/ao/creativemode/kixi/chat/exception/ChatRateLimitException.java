package ao.creativemode.kixi.chat.exception;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.RetryAfter;
import org.springframework.http.HttpStatus;

/**
 * The tutor's per-account window is exhausted (issue #114: "rate limit por
 * aluno"). Extends {@link ApiException} so the global handler renders it as a
 * problem detail, and {@link RetryAfter} so that handler adds the header —
 * shared code never names this class (ArchitectureTest).
 */
public final class ChatRateLimitException extends ApiException implements RetryAfter {

    private final long retryAfterSeconds;

    public ChatRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Too many tutor messages. Try again later.",
                "CHAT_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
