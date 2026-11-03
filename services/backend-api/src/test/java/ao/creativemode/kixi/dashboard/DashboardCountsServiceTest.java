package ao.creativemode.kixi.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementReadScope;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DashboardCountsServiceTest {

    private static final long TEACHER = 7L;

    private DashboardCountsRepository repository;
    private CurrentAccountService current;
    private StatementService statements;
    private StatementReadAccessService statementAccess;
    private SimulationService simulations;
    private SimulationAnswerService answers;
    private DashboardCountsService service;

    @BeforeEach
    void setUp() {
        repository = mock(DashboardCountsRepository.class);
        current = mock(CurrentAccountService.class);
        statements = mock(StatementService.class);
        statementAccess = mock(StatementReadAccessService.class);
        simulations = mock(SimulationService.class);
        answers = mock(SimulationAnswerService.class);
        service = new DashboardCountsService(repository, current, statements, statementAccess, simulations, answers);
    }

    private void caller(boolean admin, boolean teacher) {
        when(current.requiredAccountId()).thenReturn(Mono.just(TEACHER));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(admin));
        when(current.hasAnyRole("TEACHER")).thenReturn(Mono.just(teacher));
    }

    private static Statement statement(Long classId, Long subjectId, Long institutionId) {
        Statement statement = new Statement();
        statement.setClassId(classId);
        statement.setSubjectId(subjectId);
        statement.setInstitutionId(institutionId);
        return statement;
    }

    private static SimulationAnswerResponse answer(long id) {
        return new SimulationAnswerResponse(id, 1L, 1L, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void anAdminGetsEveryCountInOneQueryWithoutTouchingTheLists() {
        caller(true, false);
        Map<String, Long> all = new LinkedHashMap<>(Map.of("accounts", 812L, "review", 7L));
        when(repository.all()).thenReturn(Mono.just(all));

        StepVerifier.create(service.counts()).expectNext(all).verifyComplete();

        verify(statements, never()).findAllActive();
        verify(answers, never()).findAllActiveForStaff(any(), any(Boolean.class));
    }

    @Test
    void aTeacherGetsOnlyTheKeysOfTheListsTheyMayReadCountedAsTheListsCountThem() {
        caller(false, true);
        Map<String, Long> reference = new LinkedHashMap<>();
        for (String key : DashboardCountsRepository.REFERENCE) reference.put(key, 2L);
        when(repository.reference()).thenReturn(Mono.just(reference));
        when(repository.ownEnrollments(TEACHER)).thenReturn(Mono.just(1L));
        // Assigned to class 10, subject 20: one statement in scope, one of another subject, one global.
        StatementReadScope scope = new StatementReadScope(List.of(new StatementReadScope.Pair(10L, 20L)));
        when(statementAccess.teacherScope(TEACHER)).thenReturn(Mono.just(scope));
        when(statements.findAllActive()).thenReturn(Flux.just(
                statement(10L, 20L, 1L), statement(10L, 21L, 1L), statement(null, null, null)));
        when(statements.findNeedingReview()).thenReturn(Flux.just(statement(10L, 21L, 1L)));
        when(simulations.findAllAuthorized(TEACHER, false, true, null, null, 0, 1))
                .thenReturn(Mono.just(new SimulationService.SimulationPage(5L, List.of())));
        when(answers.findAllActiveForStaff(TEACHER, false))
                .thenReturn(Flux.just(answer(1L), answer(2L)));

        Map<String, Long> counts = service.counts().block();

        assertThat(counts).containsEntry("statements", 2L)   // in scope + the global one
                .containsEntry("review", 0L)                    // the only one waiting is of another subject
                .containsEntry("simulations", 5L)
                .containsEntry("simulation-answers", 2L)
                .containsEntry("enrollments", 1L)
                .containsEntry("classes", 2L);
        assertThat(counts).doesNotContainKeys("roles", "accounts", "users", "teachers", "teaching-assignments", "sessions");
        assertThat(List.copyOf(counts.keySet())).containsExactly("school-years", "terms", "subjects", "courses",
                "classes", "institutions", "enrollments", "simulation-answers", "statements", "simulations", "review");
    }

    @Test
    void anyoneElseIsRefusedRatherThanGivenTheSchoolWideNumbers() {
        caller(false, false);

        StepVerifier.create(service.counts())
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class)
                        .extracting("status").hasToString("403 FORBIDDEN"))
                .verify();
        verify(repository, never()).all();
        verify(repository, never()).reference();
    }
}
