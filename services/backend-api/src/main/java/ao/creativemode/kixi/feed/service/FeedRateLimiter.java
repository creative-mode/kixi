package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.config.FeedRateLimitProperties;
import ao.creativemode.kixi.feed.exception.FeedRateLimitException;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Sliding window of feed writes per account - the same upsert-in-SQL shape as
 * {@code ChatRateLimiter}, on its own table so a flood of posts never reads
 * as a flood of tutor messages and vice versa.
 *
 * <p>Replaces the original {@code RateLimiterService}/{@code RateLimitingWebFilter}
 * (issue #120, review point 7): that pair read the principal as a {@code Long}
 * when {@code JwtAuthenticationFilter} sets it as a {@code String}, so every
 * rate-limited POST failed with {@code ClassCastException} before reaching the
 * controller, and its in-memory bucket map had no eviction. Being called from
 * the service with the {@code Long} id {@code CurrentAccountService} already
 * resolved sidesteps the cast entirely, and the DB row expires itself the way
 * {@code chat_rate_limits} rows do.</p>
 */
@Service
public class FeedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FeedRateLimiter.class);

    private static final String CLEANUP_EXPIRED_SQL = """
            DELETE FROM feed_rate_limits
            WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
            """;

    private static final String RESERVE_ATTEMPT_SQL = """
            WITH reservation AS (
                INSERT INTO feed_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN feed_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE feed_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN feed_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE feed_rate_limits.window_started_at
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
    private final FeedRateLimitProperties properties;

    public FeedRateLimiter(DatabaseClient databaseClient, FeedRateLimitProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    /** Reserve one feed write for the account, or fail with 429/503. */
    public Mono<Void> check(Long accountId) {
        String rateKey = "account:" + accountId;
        return cleanupExpiredEntries()
                .then(reserveAttempt(rateKey))
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Feed protection is temporarily unavailable")))
                .onErrorMap(error -> !(error instanceof ApiException), error ->
                        ApiException.serviceUnavailable(
                                "Feed protection is temporarily unavailable"))
                .flatMap(state -> {
                    if (state.attempts() > properties.getMaxAttempts()) {
                        log.warn("Feed rate limit exceeded");
                        return Mono.error(new FeedRateLimitException(state.retryAfterSeconds()));
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
