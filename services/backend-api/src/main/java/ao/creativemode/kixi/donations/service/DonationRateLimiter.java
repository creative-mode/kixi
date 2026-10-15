package ao.creativemode.kixi.donations.service;

import ao.creativemode.kixi.donations.config.DonationProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.DonationRateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The donation budget (issue #252): the sliding window of the registration and login limiters, with
 * the window and the ceiling given per call, so one table holds three buckets for a submission:
 * the client's hour, the client's day and the whole platform's day.
 *
 * <p>Every attempt is counted, including the ones that are refused later for a bad file or taken for
 * a bot: a script that only ever sends bad files is exactly what the budget is for.</p>
 *
 * <p>Fails closed. If the table cannot be reached the donation is answered 503: the alternative to a
 * limiter that errors is no limiter, on an endpoint open to the whole internet.</p>
 */
@Service
public class DonationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DonationRateLimiter.class);

    private static final long HOUR = 3600;
    private static final long DAY = 86400;

    private static final String CLEANUP_EXPIRED_SQL = """
            DELETE FROM donation_rate_limits
            WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '2 days'
            """;

    private static final String RESERVE_ATTEMPT_SQL = """
            WITH reservation AS (
                INSERT INTO donation_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN donation_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE donation_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN donation_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE donation_rate_limits.window_started_at
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
    private final DonationProperties properties;

    public DonationRateLimiter(DatabaseClient databaseClient, DonationProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    /**
     * Counts one submission against the client's hour and day and the platform's day. The three are
     * consulted in that order and the first one over its ceiling answers, so a client already over
     * its own budget does not spend the platform's.
     */
    public Mono<Void> checkSubmission(String clientHash) {
        return check("donation-ip-hour:" + clientHash, properties.getPerHour(), HOUR)
                .then(Mono.defer(() -> check("donation-ip-day:" + clientHash, properties.getPerDay(), DAY)))
                .then(Mono.defer(() -> check("donation-global-day", properties.getGlobalPerDay(), DAY)));
    }

    Mono<Void> check(String rawKey, int ceiling, long windowSeconds) {
        String hashed = hash(rawKey);
        return cleanupExpiredEntries()
                .then(Mono.defer(() -> reserveAttempt(hashed, windowSeconds)))
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Donation protection is temporarily unavailable")))
                .onErrorMap(error -> !(error instanceof ApiException), error ->
                        ApiException.serviceUnavailable("Donation protection is temporarily unavailable"))
                .flatMap(state -> {
                    if (state.attempts() > ceiling) {
                        log.warn("Donation rate limit exceeded for rateKey={}", hashed);
                        return Mono.error(new DonationRateLimitException(state.retryAfterSeconds()));
                    }
                    return Mono.empty();
                });
    }

    private Mono<RateLimitState> reserveAttempt(String hashed, long windowSeconds) {
        return databaseClient.sql(RESERVE_ATTEMPT_SQL)
                .bind("rateKey", hashed)
                .bind("windowSeconds", windowSeconds)
                .map((row, metadata) -> new RateLimitState(
                        row.get("attempts", Integer.class),
                        ((Number) row.get("retry_after_seconds")).longValue()))
                .one();
    }

    private Mono<Void> cleanupExpiredEntries() {
        return databaseClient.sql(CLEANUP_EXPIRED_SQL).fetch().rowsUpdated().then();
    }

    private static String hash(String rateKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rateKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RateLimitState(Integer attempts, long retryAfterSeconds) { }
}
