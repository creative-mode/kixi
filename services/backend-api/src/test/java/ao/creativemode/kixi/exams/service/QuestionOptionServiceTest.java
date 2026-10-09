package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
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

class QuestionOptionServiceTest {

    private static final Long STATEMENT_ID = 4L;
    private static final Long ACCOUNT_ID = 9L;
    private static final Long QUESTION_ID = 3L;
    private static final Long OPTION_ID = 30L;
    private static final String OTHER_LABEL = "B";

    private QuestionOptionRepository options;
    private QuestionRepository questions;
    private StatementRepository statements;
    private StatementWriteAccessService writeAccess;
    private QuestionOptionService service;

    @BeforeEach
    void setUp() {
        options = mock(QuestionOptionRepository.class);
        questions = mock(QuestionRepository.class);
        statements = mock(StatementRepository.class);
        writeAccess = mock(StatementWriteAccessService.class);
        service = new QuestionOptionService(options, questions, statements, writeAccess);
    }

    // ── The answer key ──────────────────────────────────────────────────────

    @Test
    void markingTheCorrectOptionClearsTheOthers() {
        givenTheStatementIsWritable();
        givenAnActiveOption(OPTION_ID);
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(2));
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenAnswer(invocation -> {
            QuestionOption option = activeOption(OPTION_ID, "A");
            option.markAsCorrect();
            return Mono.just(option);
        });

        StepVerifier.create(service.setCorrectOption(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.isCorrect()).isTrue())
                .verifyComplete();

        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    @Test
    void anOptionFromAnotherQuestionIsNotFoundAndNothingIsRewritten() {
        // setCorrectOption rewrites is_correct across every option of the
        // question it is given. A foreign id would therefore leave this question
        // with no answer at all, silently, so it has to fail before the query.
        givenTheStatementIsWritable();
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.setCorrectOption(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void anOptionIdFromAnotherQuestionIsRefused() {
        givenTheStatementIsWritable();
        QuestionOption foreign = activeOption(OPTION_ID, "A");
        foreign.setQuestionId(999L);
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(foreign));

        StepVerifier.create(service.setCorrectOption(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void aTeacherOutsideTheirClassesCannotMarkAnAnswer() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned")));

        StepVerifier.create(service.setCorrectOption(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    // ── The route that carries only the question id ──────────────────────────

    @Test
    void theFlatRouteFindsTheStatementAndThenWeighsIt() {
        // PUT /api/v1/questions/{id}/correct-option names no statement, so the
        // statement has to be looked up before the write rule can be applied at
        // all. The rule still runs: a question reached this way is no less
        // somebody else's.
        givenTheStatementIsWritable();
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question()));
        givenAnActiveOption(OPTION_ID);
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(2));
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenAnswer(invocation -> {
            QuestionOption option = activeOption(OPTION_ID, "A");
            option.markAsCorrect();
            return Mono.just(option);
        });

        StepVerifier.create(service.setCorrectOptionOfQuestion(QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.isCorrect()).isTrue())
                .verifyComplete();

        verify(writeAccess).requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false);
        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    @Test
    void theFlatRouteStillRefusesATeacherFromAnotherClass() {
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question()));
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.error(ApiException.forbidden("not assigned")));

        StepVerifier.create(service.setCorrectOptionOfQuestion(QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void theFlatRouteAnswersNotFoundForAQuestionThatIsNotThere() {
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.setCorrectOptionOfQuestion(QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(writeAccess);
        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void theFlatRouteRefusesAnOptionFromAnotherQuestion() {
        givenTheStatementIsWritable();
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question()));
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.setCorrectOptionOfQuestion(QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    // ── Labels and creation ─────────────────────────────────────────────────

    @Test
    void createRestoresAnOptionWhoseLabelWasRemoved() {
        // uk_question_options_label is unique on (question_id, option_label) and
        // does not know about deleted_at, so the label stays taken. The label is
        // an identity, so the option comes back rather than colliding.
        givenTheStatementIsWritable();
        when(options.findNextOrderIndex(QUESTION_ID)).thenReturn(Mono.just(2));
        QuestionOption removed = activeOption(OPTION_ID, "A");
        removed.markAsDeleted();
        when(options.findByQuestionIdAndOptionLabel(QUESTION_ID, "A")).thenReturn(Mono.just(removed));
        when(options.save(removed)).thenAnswer(invocation -> Mono.just(removed));

        StepVerifier.create(service.create(STATEMENT_ID, QUESTION_ID, request("A", "um", false), ACCOUNT_ID, false))
                .assertNext(response -> {
                    assertThat(response.optionText()).isEqualTo("um");
                    assertThat(response.orderIndex()).isEqualTo(2);
                })
                .verifyComplete();

        assertThat(removed.isDeleted()).isFalse();
    }

    @Test
    void createConflictsWithAnOptionAlreadyLabelledTheSame() {
        givenTheStatementIsWritable();
        when(options.findNextOrderIndex(QUESTION_ID)).thenReturn(Mono.just(2));
        when(options.findByQuestionIdAndOptionLabel(QUESTION_ID, "A"))
                .thenReturn(Mono.just(activeOption(OPTION_ID, "A")));

        StepVerifier.create(service.create(STATEMENT_ID, QUESTION_ID, request("A", "um", false), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(options, never()).save(any(QuestionOption.class));
    }

    @Test
    void createHonoursTheCorrectFlagOnANewOption() {
        givenTheStatementIsWritable();
        when(options.findNextOrderIndex(QUESTION_ID)).thenReturn(Mono.just(1));
        when(options.findByQuestionIdAndOptionLabel(QUESTION_ID, "A")).thenReturn(Mono.empty());
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> {
            QuestionOption option = invocation.getArgument(0);
            option.setId(OPTION_ID);
            return Mono.just(option);
        });
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(1));
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenAnswer(invocation -> {
            QuestionOption option = activeOption(OPTION_ID, "A");
            option.markAsCorrect();
            return Mono.just(option);
        });

        StepVerifier.create(service.create(STATEMENT_ID, QUESTION_ID, request("A", "um", true), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.isCorrect()).isTrue())
                .verifyComplete();

        // Saving the flag on the insert alone would leave whatever was already
        // correct still correct, so the question would answer two ways.
        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    @Test
    void createWithoutTheCorrectFlagLeavesTheColumnAlone() {
        givenTheStatementIsWritable();
        when(options.findNextOrderIndex(QUESTION_ID)).thenReturn(Mono.just(1));
        when(options.findByQuestionIdAndOptionLabel(QUESTION_ID, "A")).thenReturn(Mono.empty());
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> {
            QuestionOption option = invocation.getArgument(0);
            option.setId(OPTION_ID);
            return Mono.just(option);
        });

        StepVerifier.create(service.create(STATEMENT_ID, QUESTION_ID, request("A", "um", false), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.isCorrect()).isFalse())
                .verifyComplete();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void reusingTheLabelOfAnOptionThatWasTheAnswerDoesNotSilentlyMakeItOne() {
        // The caller asked for an option that is not the answer and would have
        // got one that is: the row came out of the trash carrying its old flag.
        givenTheStatementIsWritable();
        when(options.findNextOrderIndex(QUESTION_ID)).thenReturn(Mono.just(2));
        QuestionOption removed = activeOption(OPTION_ID, "A");
        removed.markAsCorrect();
        removed.markAsDeleted();
        when(options.findByQuestionIdAndOptionLabel(QUESTION_ID, "A")).thenReturn(Mono.just(removed));
        when(options.save(removed)).thenAnswer(invocation -> Mono.just(removed));

        StepVerifier.create(service.create(STATEMENT_ID, QUESTION_ID, request("A", "outro", false), ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.isCorrect()).isFalse())
                .verifyComplete();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void updateChangesTheTextAndNotTheLabel() {
        givenTheStatementIsWritable();
        QuestionOption existing = activeOption(OPTION_ID, "A");
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(existing));
        when(options.save(existing)).thenAnswer(invocation -> Mono.just(existing));

        StepVerifier.create(service.update(
                        STATEMENT_ID, QUESTION_ID, OPTION_ID, request(OTHER_LABEL, "outro", null),
                        ACCOUNT_ID, false))
                .assertNext(response -> {
                    // The label is the option's identity and is unique per
                    // question, so editing the text must not rename it.
                    assertThat(response.optionLabel()).isEqualTo("A");
                    assertThat(response.optionText()).isEqualTo("outro");
                })
                .verifyComplete();
    }

    @Test
    void updateAlsoMarksTheAnswerRatherThanIgnoringTheFlag() {
        // It used to be accepted here and dropped, so the body asked for the
        // option to become the answer, got a 200, and nothing changed.
        givenTheStatementIsWritable();
        QuestionOption existing = activeOption(OPTION_ID, "A");
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(existing));
        when(options.save(existing)).thenAnswer(invocation -> Mono.just(existing));
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(2));

        StepVerifier.create(service.update(
                        STATEMENT_ID, QUESTION_ID, OPTION_ID, request("A", "outro", true),
                        ACCOUNT_ID, false))
                .expectNextCount(1)
                .verifyComplete();

        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    // ── Reordering ──────────────────────────────────────────────────────────

    @Test
    void reorderRefusesAListThatMissesAnOption() {
        // Not spelled out, so an option would be left on an order nobody chose.
        givenTheStatementIsWritable();
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(activeOption(1L, "A"), activeOption(2L, OTHER_LABEL)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verify(options, never()).save(any(QuestionOption.class));
    }

    @Test
    void reorderRefusesAListThatNamesTheSameOptionTwice() {
        givenTheStatementIsWritable();
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(activeOption(1L, "A"), activeOption(2L, OTHER_LABEL)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(1L, 1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }

    @Test
    void reorderWritesTheGivenSequence() {
        givenTheStatementIsWritable();
        QuestionOption first = activeOption(1L, "A");
        QuestionOption second = activeOption(2L, OTHER_LABEL);
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(first, second));
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(2L, 1L), ACCOUNT_ID, false))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(second.getOrderIndex()).isEqualTo(0);
        assertThat(first.getOrderIndex()).isEqualTo(1);
    }

    // ── Removal ─────────────────────────────────────────────────────────────

    @Test
    void softDeleteThenRestoreLeavesTheOptionActiveAgain() {
        givenTheStatementIsWritable();
        QuestionOption existing = activeOption(OPTION_ID, "A");
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(existing));
        when(options.save(existing)).thenAnswer(invocation -> Mono.just(existing));

        StepVerifier.create(service.softDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();
        assertThat(existing.isDeleted()).isTrue();

        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(existing));
        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();
        assertThat(existing.isDeleted()).isFalse();
    }

    @Test
    void restoringTheAnswerSavesItUnmarkedAndReMarksItThroughTheQuery() {
        // setCorrectOption only touches the active rows, so a correct option in
        // the trash keeps its flag. Saving the loaded entity as it stands would
        // bring that flag back next to whichever option is correct now — two
        // answers, and findCorrectOptionByQuestionId answers with LIMIT 1.
        givenTheStatementIsWritable();
        QuestionOption wasTheAnswer = activeOption(OPTION_ID, "B");
        wasTheAnswer.markAsCorrect();
        wasTheAnswer.markAsDeleted();
        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(wasTheAnswer));
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(2));

        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        assertThat(wasTheAnswer.isDeleted()).isFalse();
        // Both halves: what went to the database, and the rewrite that followed.
        assertThat(wasTheAnswer.getIsCorrect()).isFalse();
        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    @Test
    void restoringAnOptionThatWasNotTheAnswerLeavesTheKeyAlone() {
        givenTheStatementIsWritable();
        QuestionOption ordinary = activeOption(OPTION_ID, "A");
        ordinary.markAsDeleted();
        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(ordinary));
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        assertThat(ordinary.isDeleted()).isFalse();
        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void aRemovedOptionCanBePurgedAndAnActiveOneCannot() {
        givenTheStatementIsWritable();
        QuestionOption removed = activeOption(OPTION_ID, "A");
        removed.markAsDeleted();
        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(removed));
        when(options.delete(removed)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        verify(options).delete(removed);

        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.empty());
        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void purgingAnOptionFromAnotherQuestionIsNotFound() {
        givenTheStatementIsWritable();
        QuestionOption elsewhere = activeOption(OPTION_ID, "A");
        elsewhere.setQuestionId(999L);
        elsewhere.markAsDeleted();
        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(elsewhere));

        StepVerifier.create(service.hardDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).delete(any(QuestionOption.class));
    }

    // ── What a reader does and does not see ─────────────────────────────────

    @Test
    void aReaderGetsTheOptionsWithoutTheAnswer() {
        givenTheStatementIsPublished();
        givenTheQuestionIsThere();
        QuestionOption answered = activeOption(OPTION_ID, "A");
        answered.markAsCorrect();
        when(options.findAllByQuestionIdOrderedByOrderIndex(QUESTION_ID)).thenReturn(Flux.just(answered));

        StepVerifier.create(service.findAll(STATEMENT_ID, QUESTION_ID, false))
                .assertNext(response -> {
                    assertThat(response.optionText()).isEqualTo("texto");
                    assertThat(response.isCorrect()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void staffGetTheOptionsWithTheAnswer() {
        givenTheStatementIsThere();
        givenTheQuestionIsThere();
        QuestionOption answered = activeOption(OPTION_ID, "A");
        answered.markAsCorrect();
        when(options.findAllByQuestionIdOrderedByOrderIndex(QUESTION_ID)).thenReturn(Flux.just(answered));

        StepVerifier.create(service.findAll(STATEMENT_ID, QUESTION_ID, true))
                .assertNext(response -> assertThat(response.isCorrect()).isTrue())
                .verifyComplete();
    }

    @Test
    void aReaderCannotListTheTrash() {
        givenTheStatementIsPublished();
        givenTheQuestionIsThere();

        StepVerifier.create(service.findAllDeleted(STATEMENT_ID, QUESTION_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).findAllByQuestionIdAndDeletedAtIsNotNull(anyLong());
    }

    @Test
    void aReaderOnlyReachesAPublishedStatement() {
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.findAll(STATEMENT_ID, QUESTION_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(options);
    }

    @Test
    void aQuestionFromAnotherStatementIsNotFound() {
        givenTheStatementIsThere();
        Question question = question();
        question.setStatementId(999L);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question));

        StepVerifier.create(service.findAll(STATEMENT_ID, QUESTION_ID, true))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(options);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenTheStatementIsThere() {
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Mono.just(statement()));
    }

    private void givenTheStatementIsPublished() {
        Statement statement = statement();
        statement.setVisible(true);
        when(statements.findByIdAndVisibleTrueAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement));
    }

    private void givenTheStatementIsWritable() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.just(statement()));
        givenTheStatementIsThere();
        givenTheQuestionIsThere();
    }

    private void givenTheQuestionIsThere() {
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question()));
    }

    private void givenAnActiveOption(Long optionId) {
        when(options.findByIdAndDeletedAtIsNull(optionId))
                .thenReturn(Mono.just(activeOption(optionId, "A")));
    }

    private QuestionOption activeOption(Long id, String label) {
        QuestionOption option = new QuestionOption(QUESTION_ID, label, "texto");
        option.setId(id);
        option.setOrderIndex(1);
        return option;
    }

    private Question question() {
        Question question = new Question(STATEMENT_ID, 1, "texto", "multiple_choice");
        question.setId(QUESTION_ID);
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

    private QuestionOptionRequest request(String label, String text, Boolean correct) {
        return new QuestionOptionRequest(label, text, correct);
    }
}
