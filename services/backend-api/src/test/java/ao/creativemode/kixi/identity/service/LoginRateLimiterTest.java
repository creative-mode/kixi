package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.config.LoginRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.LoginRateLimitException;
import io.r2dbc.spi.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the login rate limiter (issue #123).
 *
 * <p>Window expiry under clock skew and real concurrency against PostgreSQL is the same
 * ground {@link RegistrationRateLimiterTest} leaves open, and is left open here on
 * purpose rather than half-solved: the statement is the one already running for
 * registration, against a table of the same shape. What is worth pinning here is what a
 * copy of that statement could get wrong, and the key is what it could get wrong.</p>
 */
class LoginRateLimiterTest {

    private static final String INSERT = "INSERT INTO login_rate_limits";
    private static final String CLEANUP = "DELETE FROM login_rate_limits";

    private DatabaseClient databaseClient;
    private LoginRateLimitProperties properties;
    private LoginRateLimiter limiter;
    private Map<String, Object> bound;

    @BeforeEach
    void setUp() {
        databaseClient = mock(DatabaseClient.class);
        properties = new LoginRateLimitProperties();
        properties.setMaxAttempts(3);
        properties.setWindowSeconds(60);
        limiter = new LoginRateLimiter(databaseClient, properties);
        bound = new HashMap<>();
    }

    @Test
    void allowsAttemptAtOrBelowMax() {
        stubReserve(3, 30L);

        StepVerifier.create(limiter.check("login-ip:10.0.0.1")).verifyComplete();
    }

    @Test
    void blocksAboveMaxWithRetryAfter() {
        stubReserve(4, 30L);

        StepVerifier.create(limiter.check("login-ip:10.0.0.1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(LoginRateLimitException.class);
                    LoginRateLimitException limited = (LoginRateLimitException) error;
                    assertThat(limited.getRetryAfterSeconds()).isEqualTo(30L);
                    assertThat(limited.getStatusCode()).isEqualTo(429);
                })
                .verify();
    }

    /**
     * The key reaches the table as a digest, never as the identity behind it.
     *
     * <p>An address in {@code login_rate_limits.rate_key} in the clear would put every
     * account's identity on disk in a table nobody remembers when someone asks for the
     * data. The controller prefixes the raw key; what must not arrive here is that prefix
     * or anything after it.</p>
     */
    @Test
    void theKeyIsHashedSoNoIdentityIsStoredInTheClear() {
        stubReserve(1, 30L);

        limiter.check("login-identity:alguem@kixi.ao").block();

        assertThat(bound.get("rateKey"))
                .asString()
                .hasSize(64)
                .doesNotContain("alguem", "kixi.ao", "login-identity");
    }

    @Test
    void theWindowInSecondsReachesTheStatement() {
        stubReserve(1, 30L);

        limiter.check("login-ip:10.0.0.1").block();

        assertThat(bound).containsEntry("windowSeconds", 60L);
    }

    /**
     * A limiter whose table is unreachable must not wave the login through.
     *
     * <p>The alternative to failing closed here is no limiter at all, which is what an
     * attacker wants on this path. 503 is refused; 200 would be the vulnerability.</p>
     */
    @Test
    void failsClosedWhenTheReservationErrors() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(contains(INSERT))).thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        RowsFetchSpec<Object> failing = rows(Mono.error(new RuntimeException("connection reset")));
        when(reserve.map(any(BiFunction.class))).thenReturn(failing);

        StepVerifier.create(limiter.check("login-ip:10.0.0.1"))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatusCode()).isEqualTo(503))
                .verify();
    }

    @Test
    void failsClosedWhenTheReservationIsEmpty() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(contains(INSERT))).thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        RowsFetchSpec<Object> empty = rows(Mono.empty());
        when(reserve.map(any(BiFunction.class))).thenReturn(empty);

        StepVerifier.create(limiter.check("login-ip:10.0.0.1"))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatusCode()).isEqualTo(503))
                .verify();
    }

    private void stubReserve(int attempts, long retryAfterSeconds) {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(contains(INSERT))).thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenAnswer(call -> {
            bound.put(call.getArgument(0), call.getArgument(1));
            return reserve;
        });
        when(reserve.map(any(BiFunction.class))).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            BiFunction<Row, io.r2dbc.spi.RowMetadata, ?> mapper = call.getArgument(0);
            Row row = mock(Row.class);
            when(row.get("attempts", Integer.class)).thenReturn(attempts);
            when(row.get("retry_after_seconds")).thenReturn(retryAfterSeconds);
            return rows(Mono.just(mapper.apply(row, null)));
        });
    }

    private void stubCleanup() {
        DatabaseClient.GenericExecuteSpec cleanup = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(contains(CLEANUP))).thenReturn(cleanup);
        when(cleanup.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(0L));
    }

    @SuppressWarnings("unchecked")
    private static RowsFetchSpec<Object> rows(Mono<?> value) {
        RowsFetchSpec<Object> fetch = mock(RowsFetchSpec.class);
        when(fetch.one()).thenReturn((Mono<Object>) value);
        return fetch;
    }
}