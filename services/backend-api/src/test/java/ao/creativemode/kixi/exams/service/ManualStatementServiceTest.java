package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ManualStatementServiceTest {

    private StatementRepository statements;
    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private InstitutionAccessService access;
    private ManualStatementService service;

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        access = mock(InstitutionAccessService.class);
        service = new ManualStatementService(statements, questions, options, access);
    }

    private ManualStatementRequest request() {
        return new ManualStatementRequest(
            1L, 2L, "Prova de Matemática", "Teste", 90, null, null,
            null, null, null, null, true,
            List.of(
                new ManualStatementRequest.Question("Resolva x+1=2", 5.0, null),
                new ManualStatementRequest.Question("Escolha", 3.5, List.of(
                    new ManualStatementRequest.Option("A", "um", true),
                    new ManualStatementRequest.Option("B", "dois", false)))
            )
        );
    }

    @Test
    void createsStatementWithNumberedQuestionsAndOptions() {
        when(access.requireCanAuthor(9L, false, 1L, 2L)).thenReturn(Mono.empty());
        when(statements.save(any(Statement.class))).thenAnswer(invocation -> {
            Statement s = invocation.getArgument(0);
            s.setId(10L);
            return Mono.just(s);
        });
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            q.setId(100L + q.getNumber());
            return Mono.just(q);
        });
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(request(), 9L, false))
                .assertNext(saved -> assertThat(saved.getId()).isEqualTo(10L))
                .verifyComplete();

        ArgumentCaptor<Statement> statement = ArgumentCaptor.forClass(Statement.class);
        verify(statements).save(statement.capture());
        assertThat(statement.getValue().getInstitutionId()).isEqualTo(1L);
        assertThat(statement.getValue().getSubjectId()).isEqualTo(2L);
        assertThat(statement.getValue().getCreatedBy()).isEqualTo(9L);
        assertThat(statement.getValue().getSource()).isEqualTo("manual");
        assertThat(statement.getValue().getTotalMaxScore()).isEqualTo(8.5);
        verify(questions, times(2)).save(any(Question.class));
        verify(options, times(2)).save(any(QuestionOption.class));
    }

    @Test
    void doesNotSaveAnythingWhenAccessIsDenied() {
        when(access.requireCanAuthor(9L, false, 1L, 2L))
                .thenReturn(Mono.error(ApiException.forbidden("Teacher is not affiliated with this institution")));

        StepVerifier.create(service.create(request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statements, never()).save(any(Statement.class));
        verify(questions, never()).save(any(Question.class));
    }
}
