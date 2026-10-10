package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimulationServiceTest {

    private SimulationRepository repository;
    private AccountRepository accountRepository;
    private SchoolYearRepository schoolYearRepository;
    private StatementRepository statementRepository;
    private SimulationDeadlineService deadlineService;
    private SimulationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SimulationRepository.class);
        accountRepository = mock(AccountRepository.class);
        schoolYearRepository = mock(SchoolYearRepository.class);
        statementRepository = mock(StatementRepository.class);
        deadlineService = mock(SimulationDeadlineService.class);
        service = new SimulationService(repository, accountRepository, schoolYearRepository,
                statementRepository, deadlineService);

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account(1L)));
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));
        Statement statement = new Statement();
        statement.setId(1L);
        when(statementRepository.findById(1L)).thenReturn(Mono.just(statement));
    }

    @Test
    void findAllActiveResolvesRelationshipsForEveryEntity() {
        when(repository.findByDeletedAtIsNull()).thenReturn(Flux.just(simulation(1L, SimulationStatus.IN_PROGRESS)));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void findAllActiveForAccountDelegatesToRepository() {
        when(repository.findByAccountIdAndDeletedAtIsNull(1L))
                .thenReturn(Flux.just(simulation(1L, SimulationStatus.IN_PROGRESS)));

        StepVerifier.create(service.findAllActiveForAccount(1L))
                .assertNext(response -> assertThat(response.account().id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void findByIdExposesDeadlineCalculatedForAccountWithExtraTime() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 3, 1, 10, 0);
        LocalDateTime deadline = startedAt.plusMinutes(37).plusSeconds(40);
        Account account = account(1L);
        account.setAccessibilityExtraTime(true);
        Statement statement = new Statement();
        statement.setDurationMinutes(30);
        Simulation simulation = simulation(1L, SimulationStatus.IN_PROGRESS);
        simulation.setStartedAt(startedAt);

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));
        when(statementRepository.findById(1L)).thenReturn(Mono.just(statement));
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(simulation));
        when(deadlineService.effectiveDeadlineFor(startedAt, 30, true)).thenReturn(deadline);

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.deadline()).isEqualTo(deadline))
                .verifyComplete();

        verify(deadlineService).effectiveDeadlineFor(startedAt, 30, true);
    }

    @Test
    void findByIdExposesEffectiveDeadlineForAccountWithoutExtraTime() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 3, 1, 10, 0);
        LocalDateTime deadline = startedAt.plusMinutes(30).plusSeconds(10);
        Account account = account(1L);
        account.setAccessibilityExtraTime(false);
        Statement statement = new Statement();
        statement.setDurationMinutes(30);
        Simulation simulation = simulation(1L, SimulationStatus.IN_PROGRESS);
        simulation.setStartedAt(startedAt);

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));
        when(statementRepository.findById(1L)).thenReturn(Mono.just(statement));
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(simulation));
        when(deadlineService.effectiveDeadlineFor(startedAt, 30, false)).thenReturn(deadline);

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.deadline()).isEqualTo(deadline))
                .verifyComplete();

        verify(deadlineService).effectiveDeadlineFor(startedAt, 30, false);
    }

    @Test
    void closedRoomDeadlineIsRealAndJsonSerializable() throws Exception {
        LocalDateTime roomEndsAt = LocalDateTime.of(2026, 10, 10, 12, 0);
        Simulation simulation = simulation(1L, SimulationStatus.IN_PROGRESS);
        simulation.setExamRoomId(77L);
        simulation.setStartedAt(LocalDateTime.of(2026, 10, 10, 10, 0));
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(simulation));
        when(deadlineService.deadline(simulation)).thenReturn(Mono.just(roomEndsAt));

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> {
                    assertThat(response.deadline()).isEqualTo(roomEndsAt);
                    try {
                        assertThat(new ObjectMapper().findAndRegisterModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .writeValueAsString(response))
                                .contains("\"deadline\":\"2026-10-10T12:00:00\"")
                                .doesNotContain("-999999999");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                })
                .verifyComplete();
    }

    @Test
    void findAllTrashedReturnsOnlyDeletedEntities() {
        Simulation deleted = simulation(2L, SimulationStatus.CANCELLED);
        deleted.markAsDelete();
        when(repository.findByDeletedAtIsNotNull()).thenReturn(Flux.just(deleted));

        StepVerifier.create(service.findAllTrashed())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void createRejectsMissingAccountWithoutSaving() {
        when(accountRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(new SimulationRequest(9999L, 1L, 1L, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsMissingSchoolYearWithoutSaving() {
        when(schoolYearRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(new SimulationRequest(1L, 1L, 9999L, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsMissingStatementWithoutSaving() {
        when(statementRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(new SimulationRequest(1L, 9999L, 1L, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createSavesSimulationInProgressWhenReferencesExist() {
        when(repository.save(any(Simulation.class))).thenAnswer(invocation -> {
            Simulation entity = invocation.getArgument(0);
            entity.setId(10L);
            return Mono.just(entity);
        });

        StepVerifier.create(service.create(new SimulationRequest(1L, 1L, 1L, null, null, null, null, null)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(10L);
                    assertThat(response.status()).isEqualTo(SimulationStatus.IN_PROGRESS);
                })
                .verifyComplete();
    }

    @Test
    void teacherCreationUsesTheRealStatementWriteGate() {
        InstitutionAccessService institutionAccess = mock(InstitutionAccessService.class);
        when(institutionAccess.requireAssignedTo(7L, false, 3L, null))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned")));
        Statement statement = new Statement();
        statement.setId(1L);
        statement.setClassId(3L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(statement));
        SimulationService teacherService = new SimulationService(repository, accountRepository,
                schoolYearRepository, statementRepository, deadlineService, null,
                new StatementWriteAccessService(institutionAccess, statementRepository));

        StepVerifier.create(teacherService.create(
                        new SimulationRequest(1L, 1L, null, null, null, null, null, null), 7L, false))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
        verify(repository, never()).save(any(Simulation.class));
    }

    @Test
    void createForAccountRejectsMismatchedAccountId() {
        StepVerifier.create(service.createForAccount(
                        new SimulationRequest(2L, 1L, 1L, null, null, null, null, null), 1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(403);
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void participantCannotCreateNormalSimulationForAnOpenOrRunningRoomStatement() {
        ExamRoomAccess rooms = mock(ExamRoomAccess.class);
        when(rooms.hasOpenOrRunningRoom(1L)).thenReturn(Mono.just(true));
        SimulationService participantService = new SimulationService(repository, accountRepository,
                schoolYearRepository, statementRepository, deadlineService, rooms, null);

        StepVerifier.create(participantService.createForAccount(
                        new SimulationRequest(1L, 1L, 1L, null, null, null, null, null), 1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
        verify(repository, never()).save(any(Simulation.class));
    }

    @Test
    void participantCanStillCreateAStatementWithoutAnActiveRoom() {
        ExamRoomAccess rooms = mock(ExamRoomAccess.class);
        when(rooms.hasOpenOrRunningRoom(1L)).thenReturn(Mono.just(false));
        when(repository.save(any(Simulation.class))).thenAnswer(invocation -> {
            Simulation entity = invocation.getArgument(0);
            entity.setId(11L);
            return Mono.just(entity);
        });
        SimulationService participantService = new SimulationService(repository, accountRepository,
                schoolYearRepository, statementRepository, deadlineService, rooms, null);

        StepVerifier.create(participantService.createForAccount(
                        new SimulationRequest(1L, 1L, 1L, null, null, null, null, null), 1L))
                .expectNextCount(1).verifyComplete();
        verify(repository).save(any(Simulation.class));
    }

    @Test
    void updateRejectsMissingSimulation() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new SimulationRequest(1L, 1L, 1L, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void updateRejectsSimulationThatIsNotInProgress() {
        Simulation finished = simulation(1L, SimulationStatus.FINISHED);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(finished));

        StepVerifier.create(service.update(1L, new SimulationRequest(1L, 1L, 1L, null, null, null, null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Simulation cannot be updated");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsInvalidStatusTransition() {
        Simulation inProgress = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgress));

        StepVerifier.create(service.update(1L,
                        new SimulationRequest(1L, 1L, 1L, null, null, null, null, SimulationStatus.IN_PROGRESS)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Invalid status");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsFinishedStatusMissingRequiredFields() {
        Simulation inProgress = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgress));

        StepVerifier.create(service.update(1L,
                        new SimulationRequest(1L, 1L, 1L, null, null, null, null, SimulationStatus.FINISHED)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .contains("/submit");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateCannotUseClientPayloadToFinishSimulation() {
        Simulation inProgress = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgress));
        StepVerifier.create(service.update(1L,
                        new SimulationRequest(1L, 1L, 1L, null, LocalDateTime.now(), 600, 8.5, SimulationStatus.FINISHED)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .contains("/submit");
                })
                .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteRejectsMissingSimulation() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksSimulationAsDeleted() {
        Simulation existing = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any(Simulation.class))).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.getDeletedAt()).isNotNull();
    }

    @Test
    void restoreRejectsSimulationThatIsNotDeleted() {
        Simulation existing = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findById(1L)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        Simulation deleted = simulation(1L, SimulationStatus.IN_PROGRESS);
        deleted.markAsDelete();
        when(repository.findById(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(any(Simulation.class))).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.getDeletedAt()).isNull();
    }

    @Test
    void restoreRejectsDeletedExamRoomSimulationToKeepRoomJoinUnique() {
        Simulation deleted = simulation(1L, SimulationStatus.IN_PROGRESS);
        deleted.setExamRoomId(20L);
        deleted.markAsDelete();
        when(repository.findById(1L)).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
        verify(repository, never()).save(any(Simulation.class));
    }

    @Test
    void hardDeleteRejectsSimulationThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(Simulation.class));
    }

    @Test
    void hardDeleteRemovesTrashedSimulation() {
        Simulation deleted = simulation(1L, SimulationStatus.IN_PROGRESS);
        deleted.markAsDelete();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private Simulation simulation(Long id, SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setId(id);
        simulation.setAccountId(1L);
        simulation.setStatementId(1L);
        simulation.setSchoolYearId(1L);
        simulation.setStatus(status);
        simulation.setStartedAt(LocalDateTime.now());
        return simulation;
    }

    private Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        account.setUsername("student_test");
        account.setEmail("student_test@kixi.local");
        return account;
    }
}
