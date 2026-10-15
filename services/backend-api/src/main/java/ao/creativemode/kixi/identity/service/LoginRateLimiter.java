package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.config.LoginRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.LoginRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The login window (issue #123), the same sliding window
 * {@link RegistrationRateLimiter} keeps for registration.
 *
 * <p>Copy rather than share: the limiter reads a table named for it and is configured
 * with its own window, so widening {@code RegistrationRateLimiter} into a general one
 * would touch a control that works on the last day before launch to save a dozen lines.</p>
 *
 * <p>Called twice per login, once for the client address and once for the identity being
 * claimed, which is the pair that closes both halves of a guess: one address spraying many
 * usernames, and many addresses aiming at one account. Neither key alone is enough.</p>
 *
 * <p>Fails closed. If the table cannot be reached the login is answered 503 rather than
 * waved through, because the alternative to a limiter that errors is no limiter, and on
 * this path no limiter is what an attacker wants.</p>
 */
@Service
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final String CLEANUP_EXPIRED_SQL = """
            DELETE FROM login_rate_limits
            WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
            """;

    private static final String RESERVE_ATTEMPT_SQL = """
            WITH reservation AS (
                INSERT INTO login_rate_limits
                    (rate_key, window_started_at, attempts, updated_at)
                VALUES (:rateKey, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (rate_key) DO UPDATE SET
                    attempts = CASE
                        WHEN login_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN 1
                        ELSE login_rate_limits.attempts + 1
                    END,
                    window_started_at = CASE
                        WHEN login_rate_limits.window_started_at
                             <= CURRENT_TIMESTAMP - (:windowSeconds * INTERVAL '1 second')
                        THEN CURRENT_TIMESTAMP
                        ELSE login_rate_limits.window_started_at
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
    private final LoginRateLimitProperties properties;

    public LoginRateLimiter(DatabaseClient databaseClient, LoginRateLimitProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    /**
     * Count an attempt against an identity. The window that matters.
     *
     * <p>Ten per minute per account: past that, someone is guessing this account, and
     * the identity is the only key here that names the victim.</p>
     */
    public Mono<Void> checkIdentity(String rateKey) {
        return check(rateKey, properties.getMaxAttempts());
    }

    /**
     * Count an attempt against an address, which is not the same decision.
     *
     * <p>The limit is far looser ({@link LoginRateLimitProperties#getMaxAttemptsPerAddress()})
     * because behind a school NAT every student leaves from the same address. At the same
     * ten, one student fumbling their password eleven times locks the whole school out of
     * the platform for the rest of the window, and the attacker gets to choose who is
     * locked out by aiming at whoever is next. Spraying is still stopped, just later: the
     * address key exists to cap how fast a stranger can go through a list of usernames,
     * not to be the account's defence.</p>
     */
    public Mono<Void> checkAddress(String rateKey) {
        return check(rateKey, properties.getMaxAttemptsPerAddress());
    }

    private Mono<Void> check(String rateKey, int maxAttempts) {
        String hashed = hash(rateKey);
        return cleanupExpiredEntries()
                .then(reserveAttempt(hashed))
                .switchIfEmpty(Mono.error(ApiException.serviceUnavailable(
                        "Login protection is temporarily unavailable")))
                .onErrorMap(error -> !(error instanceof ApiException), error ->
                        ApiException.serviceUnavailable(
                                "Login protection is temporarily unavailable"))
                .flatMap(state -> {
                    if (state.attempts() > maxAttempts) {
                        log.warn("Login rate limit exceeded for rateKey={}", hashed);
                        return Mono.error(new LoginRateLimitException(state.retryAfterSeconds()));
                    }
                    return Mono.empty();
                });
    }

    private Mono<RateLimitState> reserveAttempt(String hashed) {
        return databaseClient.sql(RESERVE_ATTEMPT_SQL)
                .bind("rateKey", hashed)
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

    private String hash(String rateKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rateKey.getBytes(StandardCharsets.UTF_8));
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