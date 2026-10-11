package ao.creativemode.kixi.analytics.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.analytics.repository.AnalyticsRepository;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.Access;
import ao.creativemode.kixi.analytics.repository.AnalyticsScope;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AnalyticsServiceTest {

    private AnalyticsRepository repository;
    private CurrentAccountService current;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnalyticsRepository.class);
        current = mock(CurrentAccountService.class);
        service = new AnalyticsService(repository, current);
        when(current.requiredAccountId()).thenReturn(Mono.just(7L));
    }

    @Test
    void theScopeComesFromTheAuthenticatedAccount() {
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(repository.classes(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.classes()).verifyComplete();

        verify(repository).classes(new AnalyticsScope(7L, false));
    }

    @Test
    void aClassOutsideTheAssignmentsIsForbiddenAndNotAnEmptyList() {
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(repository.classAccess(any(), eq(3L))).thenReturn(Mono.just(Access.FORBIDDEN));

        StepVerifier.create(service.classStudents(3L))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.FORBIDDEN))
                .verify();
        verify(repository, never()).classStudents(any(), anyLong());
    }

    @Test
    void aMissingStatementIsNotFound() {
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(true));
        when(repository.statementAccess(any(), eq(9L))).thenReturn(Mono.just(Access.NOT_FOUND));

        StepVerifier.create(service.statementQuestions(9L))
                .expectErrorSatisfies(error -> assertStatus(error, HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void anAllowedClassRunsTheQuery() {
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(repository.classAccess(any(), eq(3L))).thenReturn(Mono.just(Access.ALLOWED));
        when(repository.classWeekly(any(), eq(3L), eq(AnalyticsService.DEFAULT_WEEKS))).thenReturn(Flux.empty());

        StepVerifier.create(service.classWeekly(3L, null)).verifyComplete();
    }

    @Test
    void parametersOutsideTheirRangeAreABadRequest() {
        assertThatThrownBy(() -> AnalyticsService.within("days", 0, 30, 365))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("days must be between 1 and 365");
        assertThatThrownBy(() -> AnalyticsService.within("limit", 201, 50, 200))
                .isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThat(AnalyticsService.within("days", null, 30, 365)).isEqualTo(30);
    }

    private static void assertStatus(Throwable error, HttpStatus status) {
        org.assertj.core.api.Assertions.assertThat(error).isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThat(((ApiException) error).getStatusCode()).isEqualTo(status.value());
    }
}
