package ao.creativemode.kixi.shared.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.config.SecurityRateLimitProperties;
import ao.creativemode.kixi.shared.exception.ApiAbuseRateLimitException;
import ao.creativemode.kixi.shared.exception.ApiException;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiAbuseRateLimiterTest {

    private DatabaseClient databaseClient;
    private ApiAbuseRateLimiter limiter;

    @BeforeEach
    void setUp() {
        databaseClient = mock(DatabaseClient.class);
        limiter = new ApiAbuseRateLimiter(databaseClient, new SecurityRateLimitProperties());
    }

    @Test
    void allowsLoginAttemptsWithinTheConfiguredWindow() {
        stubReservation(10, 900L);

        StepVerifier.create(limiter.checkLogin("192.0.2.10"))
                .verifyComplete();
    }

    @Test
    void rejectsAnExcessUploadWithRetryAfter() {
        stubReservation(11, 37L);

        StepVerifier.create(limiter.checkUpload(42L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiAbuseRateLimitException.class);
                    ApiAbuseRateLimitException limited = (ApiAbuseRateLimitException) error;
                    assertThat(limited.getStatusCode()).isEqualTo(429);
                    assertThat(limited.getRetryAfterSeconds()).isEqualTo(37L);
                })
                .verify();
    }

    @Test
    void failsClosedWhenRateLimitStorageIsUnavailable() {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("INSERT INTO api_abuse_rate_limits")))
                .thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        RowsFetchSpec<Object> empty = rows(Mono.empty());
        when(reserve.map(any(BiFunction.class))).thenReturn(empty);

        StepVerifier.create(limiter.checkUpload(42L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(503);
                })
                .verify();
    }

    private void stubReservation(int attempts, long retryAfterSeconds) {
        stubCleanup();
        DatabaseClient.GenericExecuteSpec reserve = mock(DatabaseClient.GenericExecuteSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("INSERT INTO api_abuse_rate_limits")))
                .thenReturn(reserve);
        when(reserve.bind(anyString(), any())).thenReturn(reserve);
        when(reserve.map(any(BiFunction.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiFunction<Row, RowMetadata, ?> mapper = invocation.getArgument(0);
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
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("DELETE FROM api_abuse_rate_limits")))
                .thenReturn(cleanup);
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
