package ao.creativemode.kixi.shared.service;

import ao.creativemode.kixi.shared.config.SecurityRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiAbuseRateLimitException;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Persistent, atomic rate limits for login attempts and file uploads. */
@Service
public class ApiAbuseRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ApiAbuseRateLimiter.class);

    private static final String CLEANUP_SQL = """
            DELETE FROM api_abuse_rate_limits
            WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
            """;

    private static final String RESERVE_SQL = """
            WITH reservation AS (
                INSERT INTO api_abuse_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN api_abuse_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE api_abuse_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN api_abuse_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE api_abuse_rate_limits.window_started_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING attempts, window_started_at
            )
            SELECT attempts,
                   GREATEST(1, CEIL(EXTRACT(EPOCH FROM (
                       window_started_at + (:windowSeconds * INTERVAL '1 second') - CURRENT_TIMESTAMP
                   ))))::bigint AS retry_after_seconds
            FROM reservation
            """;

    private final DatabaseClient databaseClient;
    private final SecurityRateLimitProperties properties;

    public ApiAbuseRateLimiter(DatabaseClient databaseClient,
                               SecurityRateLimitProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    public Mono<Void> checkLogin(String clientAddress) {
        // Do not key anonymous login attempts by username/email: an attacker
        // could otherwise exhaust a victim's bucket and lock them out.
        return check("login:ip:" + valueOrUnknown(clientAddress),
                properties.getLoginMaxAttempts(), properties.getLoginWindowSeconds(), "login");
    }

    public Mono<Void> checkUpload(Long accountId) {
        return check("upload:account:" + accountId,
                properties.getUploadMaxAttempts(), properties.getUploadWindowSeconds(), "upload");
    }

    private Mono<Void> check(String rawKey, int maxAttempts, long windowSeconds, String scope) {
        String rateKey = hash(rawKey);
        return databaseClient.sql(CLEANUP_SQL)
                .fetch()
                .rowsUpdated()
                .then(reserve(rateKey, windowSeconds))
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Request protection is temporarily unavailable")))
                .onErrorMap(error -> !(error instanceof ApiException), error ->
                        ApiException.serviceUnavailable(
                                "Request protection is temporarily unavailable"))
                .flatMap(state -> {
                    if (state.attempts() > maxAttempts) {
                        log.warn("{} rate limit exceeded", scope);
                        return Mono.error(new ApiAbuseRateLimitException(scope, state.retryAfterSeconds()));
                    }
                    return Mono.empty();
                });
    }

    private Mono<RateLimitState> reserve(String rateKey, long windowSeconds) {
        return databaseClient.sql(RESERVE_SQL)
                .bind("rateKey", rateKey)
                .bind("windowSeconds", windowSeconds)
                .map((row, metadata) -> new RateLimitState(
                        row.get("attempts", Integer.class),
                        ((Number) row.get("retry_after_seconds")).longValue()))
                .one();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record RateLimitState(Integer attempts, long retryAfterSeconds) {}
}
