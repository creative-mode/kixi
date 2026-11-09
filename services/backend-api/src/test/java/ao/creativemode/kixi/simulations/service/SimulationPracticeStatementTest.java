package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A simulação de um treino por temas (kixi#729) sobre o enunciado virtual do próprio aluno. */
class SimulationPracticeStatementTest {

    private static final long STUDENT = 12L;
    private static final long PRACTICE = 7000L;

    private final SimulationRepository repository = mock(SimulationRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StatementRepository statements = mock(StatementRepository.class);
    private SimulationService service;

    @BeforeEach
    void setUp() {
        service = new SimulationService(repository, accounts, mock(SchoolYearRepository.class), statements,
                mock(SimulationDeadlineService.class));
        Account account = new Account();
        account.setId(STUDENT);
        when(accounts.findById(STUDENT)).thenReturn(Mono.just(account));
        Statement virtual = new Statement();
        virtual.setId(PRACTICE);
        virtual.setPracticeOwnerAccountId(STUDENT);
        virtual.setDurationMinutes(45);
        when(statements.findById(PRACTICE)).thenReturn(Mono.just(virtual));
        when(repository.save(any(Simulation.class))).thenAnswer(call -> {
            Simulation saved = call.getArgument(0);
            saved.setId(90L);
            return Mono.just(saved);
        });
    }

    @Test
    void theOwnerStartsAnInProgressSimulationWithTheTitleAndNoSelection() {
        when(statements.findPracticeByIdAndOwner(PRACTICE, STUDENT)).thenReturn(Mono.just(new Statement()));

        StepVerifier.create(service.createForPractice(STUDENT, PRACTICE, "Treino · Matriz"))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(90L);
                    assertThat(response.status()).isEqualTo(SimulationStatus.IN_PROGRESS);
                    assertThat(response.statement().id()).isEqualTo(PRACTICE);
                    assertThat(response.selectedQuestionIds()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<Simulation> saved = ArgumentCaptor.forClass(Simulation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(STUDENT);
        assertThat(saved.getValue().getStatementId()).isEqualTo(PRACTICE);
        assertThat(saved.getValue().getTitle()).isEqualTo("Treino · Matriz");
        assertThat(saved.getValue().getSelectedQuestionIds()).isNull();
        assertThat(saved.getValue().getExamRoomId()).isNull();
    }

    @Test
    void anotherAccountNeverStartsOnSomebodyElsesPractice() {
        when(statements.findPracticeByIdAndOwner(PRACTICE, 99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.createForPractice(99L, PRACTICE, "x"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
        verify(repository, never()).save(any());
    }
}
