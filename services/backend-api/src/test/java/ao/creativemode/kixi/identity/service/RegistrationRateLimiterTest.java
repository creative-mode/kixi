package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.config.RegistrationRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.RegistrationRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the distributed registration rate limiter.
 *
 * Window expiry under clock skew and true concurrency against PostgreSQL
 * remain a follow-up (integration / Testcontainers), as agreed for M1.
 */
class RegistrationRateLimiterTest {

    private DatabaseClient databaseClient;
    private RegistrationRateLimitProperties properties;
    private RegistrationRateLimiter limiter;

    @BeforeEach
    void setUp() {
        databaseClient = mock(DatabaseClient.class);
        properties = new RegistrationRateLimitProperties();
        properties.setMaxAttempts(5);
        properties.setWindowSeconds(900);
        limiter = new RegistrationRateLimiter(databaseClient, properties);
    }

    @Test
    void allowsAttemptAtOrBelowMax() {
        stubReserve(5, 120L);

        StepVerifier.create(limiter.check("10.0.0.1"))
                .verifyComplete();
    }

    @Test
    void blocksAboveMaxWithRetryAfter() {
        stubReserve(6, 317L);

        StepVerifier.create(limiter.check("10.0.0.1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(RegistrationRateLimitException.class);
                    RegistrationRateLimitException limited = (RegistrationRateLimitException) error;
                    assertThat(limited.getRetryAfterSeconds()).isEqualTo(317L);
                    assertThat(limited.getStatusCode()).isEqualTo(429);
                })
                .verify();
    }

    @Test
    void failsClosedWhenDatabaseReturnsEmpty() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("INSERT INTO registration_rate_limits")))
                .thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenReturn(rows(Mono.empty()));

        StepVerifier.create(limiter.check("10.0.0.1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(503);
                })
                .verify();
    }

    @Test
    void failsClosedWhenDatabaseErrors() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("INSERT INTO registration_rate_limits")))
                .thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenReturn(
                rows(Mono.error(new RuntimeException("connection reset"))));

        StepVerifier.create(limiter.check("10.0.0.1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(503);
                })
                .verify();
    }

    private void stubReserve(int attempts, long retryAfterSeconds) {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("INSERT INTO registration_rate_limits")))
                .thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiFunction<io.r2dbc.spi.Readable, Object, ?> mapper = invocation.getArgument(0);
            // Drive the mapper with a stub row so RateLimitState is built as in production.
            io.r2dbc.spi.Readable row = mock(io.r2dbc.spi.Readable.class);
            when(row.get("attempts", Integer.class)).thenReturn(attempts);
            when(row.get("retry_after_seconds")).thenReturn(retryAfterSeconds);
            Object mapped = mapper.apply(row, null);
            return rows(Mono.just(mapped));
        });
    }

    private void stubCleanup() {
        DatabaseClient.GenericExecuteSpec cleanup = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("DELETE FROM registration_rate_limits")))
                .thenReturn(cleanup);
        when(cleanup.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(0L));
    }

    @SuppressWarnings("unchecked")
    private static DatabaseClient.GenericExecuteSpec.RowsFetchSpec<Object> rows(Mono<?> value) {
        DatabaseClient.GenericExecuteSpec.RowsFetchSpec<Object> fetch =
                mock(DatabaseClient.GenericExecuteSpec.RowsFetchSpec.class);
        when(fetch.one()).thenReturn((Mono<Object>) value);
        return fetch;
    }
}
