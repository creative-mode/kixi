package ao.creativemode.kixi.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Scope;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.shared.service.TeachingScopeReader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DashboardScopeResolverTest {

    private static final Long ADMIN = 1L;
    private static final Long TEACHER = 2L;

    private TeachingScopeReader teaching;
    private CurrentAccountService accounts;
    private DashboardScopeResolver resolver;

    @BeforeEach
    void setUp() {
        teaching = mock(TeachingScopeReader.class);
        accounts = mock(CurrentAccountService.class);
        resolver = new DashboardScopeResolver(teaching, accounts);
    }

    private void admin() {
        when(accounts.hasAnyRole("ADMIN")).thenReturn(Mono.just(true));
    }

    private void notAdmin() {
        when(accounts.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
    }

    @Test
    void anAdministratorIsNotNarrowedToAnyClass() {
        admin();

        DashboardScopeResolver.DashboardScope scope = resolver.resolve(ADMIN).block();

        assertThat(scope.scope()).isEqualTo(Scope.ADMIN);
        // null is "every class". An empty list would be "no class", which is the opposite.
        assertThat(scope.classIds()).isNull();
        assertThat(scope.everything()).isTrue();
    }

    @Test
    void aTeacherIsNarrowedToTheClassesTheyTeach() {
        notAdmin();
        when(teaching.classIdsTaughtBy(TEACHER)).thenReturn(Flux.just(7L, 9L));

        DashboardScopeResolver.DashboardScope scope = resolver.resolve(TEACHER).block();

        assertThat(scope.scope()).isEqualTo(Scope.TEACHER);
        assertThat(scope.classIds()).containsExactly(7L, 9L);
        assertThat(scope.everything()).isFalse();
    }

    /** A teacher who teaches nothing must see nothing, not everything. */
    @Test
    void aTeacherWithNoAssignmentGetsAScopeThatMatchesNothing() {
        notAdmin();
        when(teaching.classIdsTaughtBy(TEACHER)).thenReturn(Flux.empty());

        DashboardScopeResolver.DashboardScope scope = resolver.resolve(TEACHER).block();

        assertThat(scope.scope()).isEqualTo(Scope.TEACHER);
        assertThat(scope.classIds()).containsExactly(-1L);
        assertThat(scope.everything()).isFalse();
    }

    @Test
    void anAdministratorNeverConsultsTheTeachingAssignments() {
        admin();

        resolver.resolve(ADMIN).block();

        org.mockito.Mockito.verifyNoInteractions(teaching);
    }
}