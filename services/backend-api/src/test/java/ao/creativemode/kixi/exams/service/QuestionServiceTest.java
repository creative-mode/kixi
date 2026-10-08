package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.dto.question.QuestionRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QuestionServiceTest {

    private static final Long STATEMENT_ID = 4L;
    private static final Long ACCOUNT_ID = 9L;
    private static final Long QUESTION_ID = 3L;

    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private StatementRepository statements;
    private StatementWriteAccessService writeAccess;
    private QuestionService service;

    @BeforeEach
    void setUp() {
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        statements = mock(StatementRepository.class);
        writeAccess = mock(StatementWriteAccessService.class);
        service = new QuestionService(questions, options, statements, writeAccess);
    }

    // ── Numbering ───────────────────────────────────────────────────────────

    @Test
    void createTakesTheNextNumberAndOrderFromWhatTheStatementHolds() {
        givenTheStatementIsWritable();
        when(questions.findNextQuestionNumber(STATEMENT_ID)).thenReturn(Mono.just(7));
        when(questions.findNextOrderIndex(STATEMENT_ID)).thenReturn(Mono.just(4));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(QUESTION_ID);
            return Mono.just(question);
        });

        StepVerifier.create(service.create(STATEMENT_ID, request(1.0, "x + 1 = 2"), ACCOUNT_ID, false))
                .assertNext(response -> {
                    assertThat(response.number()).isEqualTo(7);
                    assertThat(response.orderIndex()).isEqualTo(4);
                    assertThat(response.needsReview()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void createKeepsTheModelAnswerAndDefaultsTheTypeToOpen() {
        givenTheStatementIsWritable();
        when(questions.findNextQuestionNumber(STATEMENT_ID)).thenReturn(Mono.just(1));
        when(questions.findNextOrderIndex(STATEMENT_ID)).thenReturn(Mono.just(0));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // The vocabulary is not settled between the OCR paths ("development") and
        // the builder ("open"), so an unspecified type falls back to one of them
        // rather than being refused.
        StepVerifier.create(service.create(STATEMENT_ID, request(null, "Explique"), ACCOUNT_ID, false))
                .assertNext(response -> {
                    assertThat(response.questionType()).isEqualTo("open");
                    assertThat(response.modelAnswer()).isEqualTo("Explique");
                })
                .verifyComplete();
    }

    @Test
    void createStoresABlankModelAnswerAsNothing() {
        givenTheStatementIsWritable();
        when(questions.findNextQuestionNumber(STATEMENT_ID)).thenReturn(Mono.just(1));
        when(questions.findNextOrderIndex(STATEMENT_ID)).thenReturn(Mono.just(0));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(STATEMENT_ID, request(1.0, "   "), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.modelAnswer()).isNull())
                .verifyComplete();
    }

    @Test
    void createKeepsMultipleChoiceAsTheTypeTheApprovalGateKnows() {
        givenTheStatementIsWritable();
        when(questions.findNextQuestionNumber(STATEMENT_ID)).thenReturn(Mono.just(1));
        when(questions.findNextOrderIndex(STATEMENT_ID)).thenReturn(Mono.just(0));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(
                        STATEMENT_ID, new QuestionRequest("Qual?", "multiple_choice", 1.0, 0, "B"), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.questionType()).isEqualTo("multiple_choice"))
                .verifyComplete();
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    @Test
    void aQuestionOfAnotherStatementIsNotFoundAndNothingIsWritten() {
        givenTheStatementIsWritable();
        Question foreign = question(QUESTION_ID, 1);
        foreign.setStatementId(999L);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(foreign));

        StepVerifier.create(service.update(STATEMENT_ID, QUESTION_ID, request(1.0, "x"), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void aTeacherOutsideTheirClassesCannotTouchAStatement() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned to this class")));

        StepVerifier.create(service.softDelete(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(questions, never()).save(any(Question.class));
        verifyNoInteractions(options);
    }

    @Test
    void aStatementThatIsGoneIsNotFound() {
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.findAllActive(STATEMENT_ID, true))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void aReaderOnlyReachesAPublishedStatement() {
        // The statement exists but was never published, so for anyone who is not
        // staff it is the same answer as for an id that does not exist.
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.findAllActive(STATEMENT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    // ── What a reader does and does not see ─────────────────────────────────

    @Test
    void aReaderGetsTheQuestionWithoutTheAnswerKey() {
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement()));
        Question answered = question(QUESTION_ID, 1);
        answered.setModelAnswer("x = 1");
        answered.setNeedsReview(true);
        answered.setMaxScore(5.0);
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID)).thenReturn(Flux.just(answered));

        StepVerifier.create(service.findAllActive(STATEMENT_ID, false))
                .assertNext(response -> {
                    // The content of the question, so the route is useful.
                    assertThat(response.text()).isEqualTo("texto");
                    assertThat(response.maxScore()).isEqualTo(5.0);
                    // And not the two things that give it away.
                    assertThat(response.modelAnswer()).isNull();
                    assertThat(response.needsReview()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void staffGetTheSameQuestionWithTheAnswerKey() {
        // Without this half, the case above would also pass with the fields
        // permanently blank, which is a different and worse bug.
        givenTheStatementIsThere();
        Question answered = question(QUESTION_ID, 1);
        answered.setModelAnswer("x = 1");
        answered.setNeedsReview(true);
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID)).thenReturn(Flux.just(answered));

        StepVerifier.create(service.findAllActive(STATEMENT_ID, true))
                .assertNext(response -> {
                    assertThat(response.modelAnswer()).isEqualTo("x = 1");
                    assertThat(response.needsReview()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void aReaderCannotListTheTrash() {
        // The trashed questions are for whoever corrects the paper.
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement()));

        StepVerifier.create(service.findAllDeleted(STATEMENT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(questions, never()).findAllByStatementIdAndDeletedAtIsNotNull(anyLong());
    }

    @Test
    void theListingFollowsTheDisplayOrder() {
        // order_index is what reorder writes, so a listing that sorted on
        // anything else would make every reordering invisible.
        givenTheStatementIsThere();
        when(questions.findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.findAllActive(STATEMENT_ID, true)).verifyComplete();

        verify(questions).findAllByStatementIdOrderedByOrderIndex(STATEMENT_ID);
    }

    // ── Reordering ──────────────────────────────────────────────────────────

    @Test
    void reorderRefusesAListThatMissesAQuestion() {
        // Not spelled out, so a question would be left on an order nobody chose.
        givenTheStatementIsWritable();
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(question(1L, 1), question(2L, 2)));

        StepVerifier.create(service.reorder(STATEMENT_ID, List.of(1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verify(questions, never()).save(any(Question.class));
    }

    @Test
    void reorderRefusesAListThatNamesTheSameQuestionTwice() {
        givenTheStatementIsWritable();
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(question(1L, 1), question(2L, 2)));

        StepVerifier.create(service.reorder(STATEMENT_ID, List.of(1L, 1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }

    @Test
    void reorderMovesOnlyTheOrderAndNeverTouchesTheNumber() {
        // number is the stable ordinal and is what the unique constraint covers;
        // renumbering on a reorder would hand a number to a question that may
        // already hold it.
        givenTheStatementIsWritable();
        Question first = question(1L, 1);
        Question second = question(2L, 2);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(first, second));
        when(questions.save(any(Question.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.reorder(STATEMENT_ID, List.of(2L, 1L), ACCOUNT_ID, false))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(second.getOrderIndex()).isZero();
        assertThat(first.getOrderIndex()).isEqualTo(1);
        assertThat(first.getNumber()).isEqualTo(1);
        assertThat(second.getNumber()).isEqualTo(2);
    }

    // ── Removal, and the options that go with the question ──────────────────

    @Test
    void softDeleteStampsTheQuestionAndItsOptionsWithTheSameMoment() {
        // is_correct is read from the active options, so options left behind on
        // a removed question would keep answering for it. The shared stamp is
        // what lets the restore tell the two apart.
        givenTheStatementIsWritable();
        Question existing = question(QUESTION_ID, 1);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(existing));
        when(questions.save(existing)).thenAnswer(invocation -> Mono.just(existing));
        when(options.softDeleteAllByQuestionIdAndDeletedAt(anyLong(), any(LocalDateTime.class)))
                .thenReturn(Mono.just(3));

        StepVerifier.create(service.softDelete(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        LocalDateTime questionStamp = existing.getDeletedAt();
        assertThat(questionStamp).isNotNull();
        verify(options).softDeleteAllByQuestionIdAndDeletedAt(QUESTION_ID, questionStamp);
    }

    @Test
    void restoreBringsBackOnlyTheOptionsTheCascadeTook() {
        // An option the teacher removed before the question was deleted has an
        // older stamp, so it stays out and its removal is still respected.
        givenTheStatementIsWritable();
        Question removed = question(QUESTION_ID, 1);
        LocalDateTime stamp = LocalDateTime.now().minusDays(1);
        removed.setDeletedAt(stamp);
        when(questions.findByIdAndDeletedAtIsNotNull(QUESTION_ID)).thenReturn(Mono.just(removed));
        when(questions.save(removed)).thenAnswer(invocation -> Mono.just(removed));
        when(options.restoreAllDeletedByQuestionIdAndDeletedAt(anyLong(), any(LocalDateTime.class)))
                .thenReturn(Mono.just(2));

        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        assertThat(removed.isDeleted()).isFalse();
        verify(options).restoreAllDeletedByQuestionIdAndDeletedAt(QUESTION_ID, stamp);
    }

    @Test
    void restoringAQuestionThatWasNeverRemovedIsNotFound() {
        givenTheStatementIsWritable();
        when(questions.findByIdAndDeletedAtIsNotNull(QUESTION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).restoreAllDeletedByQuestionIdAndDeletedAt(anyLong(), any());
    }

    @Test
    void aRemovedQuestionCanBePurgedAndAnActiveOneCannot() {
        // Purge is for good, so it is only offered on something already in the
        // trash: otherwise a mistaken DELETE would leave nothing to undo.
        givenTheStatementIsWritable();
        Question removed = question(QUESTION_ID, 7);
        removed.setDeletedAt(LocalDateTime.now());
        when(questions.findByIdAndDeletedAtIsNotNull(QUESTION_ID)).thenReturn(Mono.just(removed));
        when(questions.delete(removed)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        verify(questions).delete(removed);

        when(questions.findByIdAndDeletedAtIsNotNull(QUESTION_ID)).thenReturn(Mono.empty());
        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void purgingAQuestionOfAnotherStatementIsNotFound() {
        givenTheStatementIsWritable();
        Question elsewhere = question(QUESTION_ID, 7);
        elsewhere.setStatementId(99L);
        elsewhere.setDeletedAt(LocalDateTime.now());
        when(questions.findByIdAndDeletedAtIsNotNull(QUESTION_ID)).thenReturn(Mono.just(elsewhere));

        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(questions, never()).delete(any(Question.class));
    }

    @Test
    void updateKeepsTheNumberItAlreadyHas() {
        givenTheStatementIsWritable();
        Question existing = question(QUESTION_ID, 7);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(existing));
        when(questions.save(existing)).thenAnswer(invocation -> Mono.just(existing));

        StepVerifier.create(service.update(STATEMENT_ID, QUESTION_ID, request(2.0, "revisado"), ACCOUNT_ID, false))
                .assertNext(response -> {
                    // The request carries no number, so an edit cannot hand the
                    // question a different ordinal.
                    assertThat(response.number()).isEqualTo(7);
                    assertThat(response.text()).isEqualTo("Enunciado?");
                    assertThat(response.maxScore()).isEqualTo(2.0);
                    assertThat(response.modelAnswer()).isEqualTo("revisado");
                })
                .verifyComplete();
    }

    @Test
    void updateLeavesTheTypeAloneWhenNoneIsGiven() {
        givenTheStatementIsWritable();
        Question existing = question(QUESTION_ID, 1);
        existing.setQuestionType("development");
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(existing));
        when(questions.save(existing)).thenAnswer(invocation -> Mono.just(existing));

        StepVerifier.create(service.update(
                        STATEMENT_ID, QUESTION_ID, new QuestionRequest("x", "  ", 1.0, 0, null), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.questionType()).isEqualTo("development"))
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenTheStatementIsThere() {
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.just(statement()));
    }

    private void givenTheStatementIsWritable() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.just(statement()));
        givenTheStatementIsThere();
    }

    private Question question(Long id, Integer number) {
        Question question = new Question(STATEMENT_ID, number, "texto", "open");
        question.setId(id);
        question.setOrderIndex(0);
        return question;
    }

    private Statement statement() {
        Statement statement = new Statement("P1", "Prova de Matematica");
        statement.setId(STATEMENT_ID);
        statement.setInstitutionId(1L);
        statement.setClassId(2L);
        statement.setSubjectId(3L);
        statement.setVisible(true);
        return statement;
    }

    private QuestionRequest request(Double score, String modelAnswer) {
        return new QuestionRequest("Enunciado?", null, score, 0, modelAnswer);
    }
}
