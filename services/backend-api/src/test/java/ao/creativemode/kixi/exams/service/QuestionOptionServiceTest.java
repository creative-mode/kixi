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
    private static final String OTHER_QUESTION_ID_LABEL = "B";

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

    // â”€â”€ The answer key â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void markingTheCorrectOptionClearsTheOthers() {
        givenTheQuestionIsWritable();
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
        givenTheQuestionIsWritable();
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.setCorrectOption(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(options, never()).setCorrectOption(anyLong(), anyLong());
    }

    @Test
    void anOptionIdFromAnotherQuestionIsRefused() {
        givenTheQuestionIsWritable();
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

    // â”€â”€ Labels and creation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void createRestoresAnOptionWhoseLabelWasRemoved() {
        // uk_question_options_label is unique on (question_id, option_label) and
        // does not know about deleted_at, so the label stays taken. The label is
        // an identity, so the option comes back rather than colliding.
        givenTheQuestionIsWritable();
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
        givenTheQuestionIsWritable();
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
        givenTheQuestionIsWritable();
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
        givenTheQuestionIsWritable();
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
    void updateChangesTheTextAndNotTheLabel() {
        givenTheQuestionIsWritable();
        QuestionOption existing = activeOption(OPTION_ID, "A");
        when(options.findByIdAndDeletedAtIsNull(OPTION_ID)).thenReturn(Mono.just(existing));
        when(options.save(existing)).thenAnswer(invocation -> Mono.just(existing));

        StepVerifier.create(service.update(
                        STATEMENT_ID, QUESTION_ID, OPTION_ID, request(OTHER_QUESTION_ID_LABEL, "outro", null),
                        ACCOUNT_ID, false))
                .assertNext(response -> {
                    // The label is the option's identity and is unique per
                    // question, so editing the text must not rename it.
                    assertThat(response.optionLabel()).isEqualTo("A");
                    assertThat(response.optionText()).isEqualTo("outro");
                })
                .verifyComplete();
    }

    // â”€â”€ Reordering â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void reorderRefusesAListThatMissesAQuestion() {
        // Not spelled out, so a question would be left on an order nobody chose.
        givenTheQuestionIsWritable();
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(activeOption(1L, "A"), activeOption(2L, OTHER_QUESTION_ID_LABEL)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verify(options, never()).save(any(QuestionOption.class));
    }

    @Test
    void reorderRefusesAListThatNamesTheSameOptionTwice() {
        givenTheQuestionIsWritable();
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(activeOption(1L, "A"), activeOption(2L, OTHER_QUESTION_ID_LABEL)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(1L, 1L), ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }

    @Test
    void reorderWritesTheGivenSequence() {
        givenTheQuestionIsWritable();
        QuestionOption first = activeOption(1L, "A");
        QuestionOption second = activeOption(2L, OTHER_QUESTION_ID_LABEL);
        when(options.findAllByQuestionIdAndDeletedAtIsNull(QUESTION_ID))
                .thenReturn(Flux.just(first, second));
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.reorder(STATEMENT_ID, QUESTION_ID, List.of(2L, 1L), ACCOUNT_ID, false))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(second.getOrderIndex()).isEqualTo(0);
        assertThat(first.getOrderIndex()).isEqualTo(1);
    }

    // â”€â”€ Removal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void softDeleteThenRestoreLeavesTheOptionActiveAgain() {
        givenTheQuestionIsWritable();
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
    void restoringTheAnswerReMarksItAndLeavesTheQuestionWithOneAnswer() {
        // setCorrectOption only touches the active rows, so a correct option in
        // the trash keeps its flag. Restoring the loaded entity as it stands
        // would bring that flag back next to whichever option is correct now —
        // two answers, and findCorrectOptionByQuestionId answers with LIMIT 1.
        givenTheQuestionIsWritable();
        QuestionOption wasTheAnswer = activeOption(OPTION_ID, "B");
        wasTheAnswer.markAsCorrect();
        wasTheAnswer.markAsDeleted();
        when(options.findByIdAndDeletedAtIsNotNull(OPTION_ID)).thenReturn(Mono.just(wasTheAnswer));
        when(options.save(any(QuestionOption.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(options.setCorrectOption(QUESTION_ID, OPTION_ID)).thenReturn(Mono.just(2));

        StepVerifier.create(service.restore(STATEMENT_ID, QUESTION_ID, OPTION_ID, ACCOUNT_ID, false))
                .verifyComplete();

        assertThat(wasTheAnswer.isDeleted()).isFalse();
        verify(options).setCorrectOption(QUESTION_ID, OPTION_ID);
    }

    @Test
    void restoringAnOptionThatWasNotTheAnswerLeavesTheKeyAlone() {
        givenTheQuestionIsWritable();
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
        givenTheQuestionIsWritable();
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
    void reusingTheLabelOfAnOptionThatWasTheAnswerDoesNotSilentlyMakeItOne() {
        // The caller asked for an option that is not the answer and would have
        // got one that is: the row came out of the trash carrying its old flag.
        givenTheQuestionIsWritable();
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
    void aQuestionFromAnotherStatementIsNotFound() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.just(statement(STATEMENT_ID)));
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement(STATEMENT_ID)));
        Question question = question(QUESTION_ID);
        question.setStatementId(999L);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question));

        StepVerifier.create(service.findAll(STATEMENT_ID, QUESTION_ID, true))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(options);
    }

    // â”€â”€ Fixtures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void givenTheQuestionIsWritable() {
        when(writeAccess.requireCanWrite(STATEMENT_ID, ACCOUNT_ID, false))
                .thenReturn(Mono.just(statement(STATEMENT_ID)));
        when(statements.findByIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Mono.just(statement(STATEMENT_ID)));
        Question question = question(QUESTION_ID);
        when(questions.findByIdAndDeletedAtIsNull(QUESTION_ID)).thenReturn(Mono.just(question));
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

    private Question question(Long id) {
        Question question = new Question(STATEMENT_ID, 1, "Resolva x+1=2", "multiple_choice");
        question.setId(id);
        return question;
    }

    private Statement statement(Long id) {
        Statement statement = new Statement("P1", "Prova de MatemÃ¡tica");
        statement.setId(id);
        statement.setInstitutionId(1L);
        statement.setClassId(2L);
        statement.setSubjectId(3L);
        return statement;
    }

    private QuestionOptionRequest request(String label, String text, Boolean correct) {
        return new QuestionOptionRequest(label, text, correct);
    }
}