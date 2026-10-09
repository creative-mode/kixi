package ao.creativemode.kixi.chat.service;

import ao.creativemode.kixi.chat.config.ChatRateLimitProperties;
import ao.creativemode.kixi.chat.exception.ChatRateLimitException;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Sliding window of tutor messages per account — the same upsert-in-SQL shape
 * as {@code RegistrationRateLimiter}, on its own table so a flood of chat
 * never reads as a flood of registrations and vice versa.
 *
 * The window lives in one statement so the reservation is atomic under
 * concurrent requests: either the row is inserted, or the counter is bumped
 * and reset when the window elapsed.
 */
@Service
public class ChatRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ChatRateLimiter.class);

    private static final String CLEANUP_EXPIRED_SQL = """
            DELETE FROM chat_rate_limits
            WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
            """;

    private static final String RESERVE_ATTEMPT_SQL = """
            WITH reservation AS (
                INSERT INTO chat_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN chat_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE chat_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN chat_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE chat_rate_limits.window_started_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING attempts, window_started_at
            )
            SELECT attempts,
                   GREATEST(
                       1,
                       CEIL(EXTRACT(EPOCH FROM (
                           window_started_at
                           + (:windowSeconds * INTERVAL '1 second')
                           - CURRENT_TIMESTAMP
                       )))
                   )::bigint AS retry_after_seconds
            FROM reservation
            """;

    private final DatabaseClient databaseClient;
    private final ChatRateLimitProperties properties;

    public ChatRateLimiter(DatabaseClient databaseClient,
                           ChatRateLimitProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    /** Reserve one tutor message for the account, or fail with 429/503. */
    public Mono<Void> check(Long accountId) {
        String rateKey = "account:" + accountId;
        return cleanupExpiredEntries()
                .then(reserveAttempt(rateKey))
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Tutor protection is temporarily unavailable")))
                .onErrorMap(error -> !(error instanceof ApiException), error ->
                        ApiException.serviceUnavailable(
                                "Tutor protection is temporarily unavailable"))
                .flatMap(state -> {
                    if (state.attempts() > properties.getMaxAttempts()) {
                        log.warn("Tutor rate limit exceeded for accountId={}", accountId);
                        return Mono.error(new ChatRateLimitException(state.retryAfterSeconds()));
                    }
                    return Mono.empty();
                });
    }

    private Mono<RateLimitState> reserveAttempt(String rateKey) {
        return databaseClient.sql(RESERVE_ATTEMPT_SQL)
                .bind("rateKey", rateKey)
                .bind("windowSeconds", properties.getWindowSeconds())
                .map((row, metadata) -> new RateLimitState(
                        row.get("attempts", Integer.class),
                        ((Number) row.get("retry_after_seconds")).longValue()))
                .one();
    }

    private Mono<Void> cleanupExpiredEntries() {
        return databaseClient.sql(CLEANUP_EXPIRED_SQL)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private record RateLimitState(Integer attempts, long retryAfterSeconds) {}
}
