package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.config.RegistrationRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.RegistrationRateLimitException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class RegistrationRateLimiter {

    private static final String RESERVE_ATTEMPT_SQL = """
            WITH cleanup AS (
                DELETE FROM registration_rate_limits
                WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
            ), upsert AS (
                INSERT INTO registration_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN registration_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE registration_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN registration_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE registration_rate_limits.window_started_at
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
            FROM upsert
            """;

    private final DatabaseClient databaseClient;
    private final RegistrationRateLimitProperties properties;

    public RegistrationRateLimiter(DatabaseClient databaseClient,
                                   RegistrationRateLimitProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    public Mono<Void> check(String clientAddress) {
        return databaseClient.sql(RESERVE_ATTEMPT_SQL)
                .bind("rateKey", hash(clientAddress))
                .bind("windowSeconds", properties.getWindowSeconds())
                .map((row, metadata) -> new RateLimitState(
                        row.get("attempts", Integer.class),
                        ((Number) row.get("retry_after_seconds")).longValue()))
                .one()
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Registration protection is temporarily unavailable")))
                .flatMap(state -> state.attempts() > properties.getMaxAttempts()
                        ? Mono.error(new RegistrationRateLimitException(state.retryAfterSeconds()))
                        : Mono.empty());
    }

    private String hash(String clientAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(clientAddress.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RateLimitState(Integer attempts, long retryAfterSeconds) {}
}
