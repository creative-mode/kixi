package ao.creativemode.kixi.exams.service;

import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QuestionImageReadAccessServiceTest {

    private QuestionRepository questions;
    private StatementRepository statements;
    private StatementReadAccessService readAccess;
    private CurrentAccountService currentAccount;
    private QuestionImageReadAccessService service;

    @BeforeEach
    void setUp() {
        questions = org.mockito.Mockito.mock(QuestionRepository.class);
        statements = org.mockito.Mockito.mock(StatementRepository.class);
        readAccess = org.mockito.Mockito.mock(StatementReadAccessService.class);
        currentAccount = org.mockito.Mockito.mock(CurrentAccountService.class);
        service = new QuestionImageReadAccessService(
                org.mockito.Mockito.mock(QuestionImageRepository.class), questions, statements,
                readAccess, org.mockito.Mockito.mock(StatementWriteAccessService.class), currentAccount);
    }

    @Test
    void deniesTeacherOutsideAssignedClassBeforeIssuingImageUrl() {
        Question question = new Question();
        question.setStatementId(20L);
        Statement statement = new Statement();
        statement.setClassId(4L);
        statement.setSubjectId(8L);
        when(currentAccount.requiredAccountId()).thenReturn(Mono.just(77L));
        when(currentAccount.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(currentAccount.hasAnyRole("TEACHER")).thenReturn(Mono.just(true));
        when(questions.findByIdAndDeletedAtIsNull(5L)).thenReturn(Mono.just(question));
        when(statements.findByIdAndDeletedAtIsNull(20L)).thenReturn(Mono.just(statement));
        when(readAccess.teacherScope(77L)).thenReturn(Mono.just(new StatementReadScope(
                java.util.List.of(new StatementReadScope.Pair(9L, 8L)))));

        StepVerifier.create(service.requireQuestion(5L))
                .expectErrorMatches(error -> error.getMessage().contains("not assigned"))
                .verify();
    }

    @Test
    void permitsStudentOnlyForPublishedStatement() {
        Question question = new Question();
        question.setStatementId(20L);
        Statement statement = new Statement();
        when(currentAccount.requiredAccountId()).thenReturn(Mono.just(77L));
        when(currentAccount.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(currentAccount.hasAnyRole("TEACHER")).thenReturn(Mono.just(false));
        when(questions.findByIdAndDeletedAtIsNull(5L)).thenReturn(Mono.just(question));
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(20L)).thenReturn(Mono.just(statement));

        StepVerifier.create(service.requireQuestion(5L)).verifyComplete();
    }
}
