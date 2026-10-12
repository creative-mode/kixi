package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.config.FeedRateLimitProperties;
import ao.creativemode.kixi.feed.exception.FeedRateLimitException;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The feed's own sliding window (issue #120, review point 7). The original
 * {@code RateLimitingWebFilter} could not be exercised this way at all - it
 * read the security context directly and cast the principal to {@code Long},
 * which is why "send 11 requests" against it meant 11 {@code ClassCastException}s,
 * not 10 successes and an 11th 429. Driving the eleven attempts through the
 * same DB round trip production takes, the way {@code RegistrationRateLimiterTest}
 * does for registration, is what actually proves the window works.
 */
class FeedRateLimiterTest {

    private DatabaseClient databaseClient;
    private FeedRateLimiter limiter;

    @BeforeEach
    void setUp() {
        databaseClient = mock(DatabaseClient.class);
        FeedRateLimitProperties properties = new FeedRateLimitProperties();
        properties.setMaxAttempts(10);
        properties.setWindowSeconds(60);
        limiter = new FeedRateLimiter(databaseClient, properties);
    }

    @Test
    void firstTenRequestsInTheWindowSucceed() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            stubReserve(attempt, 60L);
            StepVerifier.create(limiter.check(42L)).verifyComplete();
        }
    }

    @Test
    void theEleventhRequestInTheWindowIs429() {
        stubReserve(11, 37L);

        StepVerifier.create(limiter.check(42L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(FeedRateLimitException.class);
                    FeedRateLimitException limited = (FeedRateLimitException) error;
                    assertThat(limited.getRetryAfterSeconds()).isEqualTo(37L);
                    assertThat(limited.getStatusCode()).isEqualTo(429);
                })
                .verify();
    }

    @Test
    void failsClosedWhenDatabaseReturnsEmpty() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(contains("INSERT INTO feed_rate_limits"))).thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenReturn(rows(Mono.empty()));

        StepVerifier.create(limiter.check(42L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(503);
                })
                .verify();
    }

    private void stubReserve(int attempts, long retryAfterSeconds) {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(contains("INSERT INTO feed_rate_limits"))).thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, ?> mapper = invocation.getArgument(0);
            io.r2dbc.spi.Row row = mock(io.r2dbc.spi.Row.class);
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
        when(databaseClient.sql(contains("DELETE FROM feed_rate_limits"))).thenReturn(cleanup);
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
