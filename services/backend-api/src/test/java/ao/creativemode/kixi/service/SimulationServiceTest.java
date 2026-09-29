package ao.creativemode.kixi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.model.Simulation;
import ao.creativemode.kixi.model.SimulationStatus;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.repository.SimulationRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
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
    private SimulationService service;

    @BeforeEach
    void setUp() {
        repository = mock(SimulationRepository.class);
        accountRepository = mock(AccountRepository.class);
        schoolYearRepository = mock(SchoolYearRepository.class);
        statementRepository = mock(StatementRepository.class);
        service = new SimulationService(repository, accountRepository, schoolYearRepository, statementRepository);

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account(1L)));
        when(schoolYearRepository.findById(1L)).thenReturn(Mono.just(new SchoolYear()));
        when(statementRepository.findById(1L)).thenReturn(Mono.just(new Statement()));
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
                            .isEqualTo("finishedAt and timeSpentSeconds are required");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateFinishesSimulationWhenAllRequiredFieldsProvided() {
        Simulation inProgress = simulation(1L, SimulationStatus.IN_PROGRESS);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgress));
        when(repository.save(any(Simulation.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        LocalDateTime finishedAt = LocalDateTime.now();
        StepVerifier.create(service.update(1L,
                        new SimulationRequest(1L, 1L, 1L, null, finishedAt, 600, 8.5, SimulationStatus.FINISHED)))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(SimulationStatus.FINISHED);
                    assertThat(response.finalScore()).isEqualTo(8.5);
                })
                .verifyComplete();
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
