package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.exams.dto.statement.StatementRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Covers the administrative/CRUD surface of StatementService (listing,
 * lookups, search, lifecycle, review, stats) that backs most of
 * StatementController. The OCR-to-statement mapping internals
 * (createFromOcr/createStatementFromOcrResponse) are exercised through
 * OcrPersistenceServiceTest and manual end-to-end testing instead, since
 * building a realistic OcrResponse fixture by hand for this legacy path
 * isn't worth the weight it would add here.
 */
class StatementServiceTest {

    private StatementRepository statementRepository;
    private QuestionRepository questionRepository;
    private QuestionOptionRepository optionRepository;
    private InstitutionAccessService accessService;
    private StatementLinkValidationService validator;
    private StatementWriteAccessService writeAccess;
    private StatementService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        questionRepository = mock(QuestionRepository.class);
        optionRepository = mock(QuestionOptionRepository.class);
        accessService = mock(InstitutionAccessService.class);
        validator = mock(StatementLinkValidationService.class);
        writeAccess = mock(StatementWriteAccessService.class);
        service = new StatementService(statementRepository, questionRepository, optionRepository,
                accessService, validator, writeAccess);
        givenTheWriteRuleAllows();
    }

    /**
     * The statement-scoped write rule has its own test, and the real chain is
     * covered over HTTP, so here it is a mock: these tests are about what
     * StatementService does once the caller is allowed in. Tests that care about
     * the rule stub it themselves.
     */
    private void givenTheWriteRuleAllows() {
        // The null guard matters: when a test then stubs this method itself,
        // Mockito runs the call with any() supplying null, and Mono.just(null)
        // would blow up while the stub is being registered.
        when(writeAccess.checkCanWrite(any(), anyLong(), anyBoolean())).thenAnswer(invocation -> {
            Statement statement = invocation.getArgument(0);
            return statement == null ? Mono.empty() : Mono.just(statement);
        });
    }

    @Test
    void findAllActiveDelegatesToRepository() {
        when(statementRepository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findAllActive())
                .assertNext(s -> assertThat(s.getId()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void findAllVisibleDelegatesToRepository() {
        when(statementRepository.findAllByVisibleTrueAndDeletedAtIsNull()).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findAllVisible()).expectNextCount(1).verifyComplete();
    }

    @Test
    void findAllDeletedDelegatesToRepository() {
        when(statementRepository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(statement(2L)));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(s -> assertThat(s.getId()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdReturnsNotFoundForMissingStatement() {
        when(statementRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void findByIdVisibleReturnsNotFoundWhenNotVisible() {
        when(statementRepository.findByIdAndVisibleTrueAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdVisible(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void findByIdWithQuestionsReturnsEmptyListsWhenStatementHasNoQuestions() {
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(statement(1L)));
        when(questionRepository.findAllByStatementIdOrderByOrderIndex(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.findByIdWithQuestions(1L))
                .assertNext(result -> {
                    assertThat(result.questions()).isEmpty();
                    assertThat(result.options()).isEmpty();
                })
                .verifyComplete();

        verify(optionRepository, never()).findAllByQuestionIds(any());
    }

    @Test
    void findByIdWithQuestionsLoadsQuestionsAndTheirOptions() {
        Question question = new Question();
        question.setId(10L);
        QuestionOption option = new QuestionOption();
        option.setId(20L);

        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(statement(1L)));
        when(questionRepository.findAllByStatementIdOrderByOrderIndex(1L)).thenReturn(Flux.just(question));
        when(optionRepository.findAllByQuestionIds(List.of(10L))).thenReturn(Flux.just(option));

        StepVerifier.create(service.findByIdWithQuestions(1L))
                .assertNext(result -> {
                    assertThat(result.questions()).containsExactly(question);
                    assertThat(result.options()).containsExactly(option);
                })
                .verifyComplete();
    }

    @Test
    void findNeedingReviewDelegatesToRepository() {
        when(statementRepository.findAllByNeedsReviewTrueAndDeletedAtIsNull()).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findNeedingReview()).expectNextCount(1).verifyComplete();
    }

    @Test
    void findFromOcrDelegatesToRepository() {
        when(statementRepository.findAllFromOcr()).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findFromOcr()).expectNextCount(1).verifyComplete();
    }

    @Test
    void findBySchoolYearDelegatesToRepository() {
        when(statementRepository.findAllBySchoolYearIdAndDeletedAtIsNull(1L)).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findBySchoolYear(1L)).expectNextCount(1).verifyComplete();
    }

    @Test
    void findBySubjectDelegatesToRepository() {
        when(statementRepository.findAllBySubjectIdAndDeletedAtIsNull(1L)).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.findBySubject(1L)).expectNextCount(1).verifyComplete();
    }

    @Test
    void searchByTitleDelegatesToRepository() {
        when(statementRepository.searchByTitle("Matemática")).thenReturn(Flux.just(statement(1L)));

        StepVerifier.create(service.searchByTitle("Matemática")).expectNextCount(1).verifyComplete();
    }

    @Test
    void softDeleteMarksStatementAsDeletedAndCascadesToQuestions() {
        Statement existing = statement(1L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        when(questionRepository.softDeleteAllByStatementId(any(), any()))
                .thenReturn(Mono.just(3));

        StepVerifier.create(service.softDelete(1L, 9L, true)).verifyComplete();

        assertThat(existing.getDeletedAt()).isNotNull();
        // The questions must be stamped with the very same value the
        // statement got, otherwise the restore below cannot tell which
        // questions its own cascade touched.
        verify(questionRepository)
                .softDeleteAllByStatementId(eq(1L), eq(existing.getDeletedAt()));
    }

    @Test
    void softDeleteRejectsMissingStatement() {
        when(statementRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void restoreRejectsStatementThatIsNotInTrash() {
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(statementRepository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAtAndUncascadesOnlyItsOwnQuestions() {
        Statement deleted = statement(1L);
        deleted.markAsDeleted();
        java.time.LocalDateTime cascadedAt = deleted.getDeletedAt();
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(statementRepository.save(deleted)).thenReturn(Mono.just(deleted));
        when(questionRepository.restoreAllDeletedByStatementIdAndDeletedAt(any(), any()))
                .thenReturn(Mono.just(3));

        StepVerifier.create(service.restore(1L, 9L, true)).verifyComplete();

        assertThat(deleted.getDeletedAt()).isNull();
        // Only the questions stamped by the statement's own soft delete are
        // brought back; ones deleted individually keep their own deleted_at.
        verify(questionRepository)
                .restoreAllDeletedByStatementIdAndDeletedAt(eq(1L), eq(cascadedAt));
    }

    @Test
    void hardDeleteRemovesQuestionsThenStatementForTrashedEntity() {
        Statement existing = statement(1L);
        existing.markAsDeleted();

        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L))
                .thenReturn(Mono.just(existing));
        when(questionRepository.deleteAllByStatementId(1L)).thenReturn(Mono.empty());
        when(statementRepository.delete(existing)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L, 9L, true)).verifyComplete();

        verify(questionRepository).deleteAllByStatementId(1L);
        verify(statementRepository).delete(existing);
    }

    @Test
    void hardDeleteRejectsStatementThatIsNotInTrash() {
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(statementRepository, never()).delete(any(Statement.class));
    }

    @Test
    void approveReviewClearsNeedsReviewFlag() {
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenTheAnswerKeyIsComplete();
        givenTheScoresAddUp(0.0);

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .assertNext(result -> assertThat(result.getNeedsReview()).isFalse())
                .verifyComplete();
    }

    // ── What has to be true before a statement goes live ────────────────────

    @Test
    void refusesToApproveWhileAQuestionWithOptionsHasNoAnswer() {
        // An unanswered question cannot be graded, and the teacher who finds
        // that out is the one marking it, long after the paper looked ready.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(questionRepository.findQuestionsWithoutCorrectOption(1L))
                .thenReturn(Flux.just(question(2L, 2), question(5L, 7)));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // Named, not just counted: the caller has to know which.
                    assertThat(error.getMessage()).contains("2").contains("7");
                })
                .verify();

        verify(statementRepository, never()).save(any(Statement.class));
    }

    @Test
    void theQuestionTypeDoesNotDecideWhetherAnAnswerIsNeeded() {
        // The gate used to look for question_type = 'multiple_choice', which
        // left it watching only the writers that spell the type that way. The
        // question CRUD writes "open" by default because a question is created
        // before its options exist, so a question that got its alternatives
        // afterwards was never checked at all — with or without an answer.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        Question openWithOptions = question(9L, 4);
        openWithOptions.setQuestionType("open");
        when(questionRepository.findQuestionsWithoutCorrectOption(1L))
                .thenReturn(Flux.just(openWithOptions));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verify(statementRepository, never()).save(any(Statement.class));
    }

    @Test
    void theErrorSaysTheQuestionsHaveOptionsRatherThanNamingTheType() {
        // The message is what the author reads at 11pm. "No correct option"
        // told them what was missing; saying "multiple choice" would have told
        // them to look at a column that decided nothing.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(questionRepository.findQuestionsWithoutCorrectOption(1L))
                .thenReturn(Flux.just(question(4L, 4)));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(error.getMessage())
                        .contains("have options but no correct option marked")
                        .contains("4"))
                .verify();
    }

    @Test
    void anAnswerOnARemovedOptionDoesNotCount() {
        // is_correct is only read from the active options, so a correct flag
        // left on a removed option is not an answer.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(questionRepository.findQuestionsWithoutCorrectOption(1L))
                .thenReturn(Flux.just(question(3L, 4)));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectError(ApiException.class)
                .verify();

        verify(statementRepository, never()).save(any(Statement.class));
    }

    @Test
    void refusesToApproveWhenTheScoresDoNotAddUpToTheDeclaredTotal() {
        // A paper whose parts do not add to its total announces a value it is
        // not worth.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        existing.setTotalMaxScore(20.0);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        givenTheAnswerKeyIsComplete();
        when(questionRepository.calculateTotalMaxScore(eq(1L))).thenReturn(Mono.just(18.5));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // Both sides of the comparison, so it is clear which is wrong.
                    assertThat(error.getMessage()).contains("18.50").contains("20.00");
                })
                .verify();

        verify(statementRepository, never()).save(any(Statement.class));
    }

    @Test
    void theScoreCheckToleratesTheFloatingPointSumOfDecimals() {
        // 0.1 + 0.2 is not 0.3 in binary floating point. The columns are
        // DECIMAL(10, 2) and the sum comes back as a double, so comparing
        // without scaling would reject a paper nobody could fix.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        existing.setTotalMaxScore(0.3);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenTheAnswerKeyIsComplete();
        when(questionRepository.calculateTotalMaxScore(eq(1L))).thenReturn(Mono.just(0.30000000000000004));

        StepVerifier.create(service.approveReview(1L, 9L, true)).expectNextCount(1).verifyComplete();
    }

    @Test
    void aStatementWithNoDeclaredTotalIsNotAskedToAddUp() {
        // The column is nullable and the OCR statements predate it, so there is
        // no value to compare against and nothing to correct.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        existing.setTotalMaxScore(null);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenTheAnswerKeyIsComplete();

        StepVerifier.create(service.approveReview(1L, 9L, true)).expectNextCount(1).verifyComplete();

        verify(questionRepository, never()).calculateTotalMaxScore(anyLong());
    }

    @Test
    void aStatementWithoutQuestionsHasNothingToAnswer() {
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        existing.setTotalMaxScore(0.0);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenTheAnswerKeyIsComplete();
        when(questionRepository.calculateTotalMaxScore(eq(1L))).thenReturn(Mono.empty());

        StepVerifier.create(service.approveReview(1L, 9L, true)).expectNextCount(1).verifyComplete();
    }

    @Test
    void theAnswerKeyIsCheckedBeforeTheScores() {
        // Both are needed, but the one that names its questions is the one worth
        // acting on first: it says exactly what to do.
        Statement existing = statement(1L);
        existing.setNeedsReview(true);
        existing.setTotalMaxScore(20.0);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(questionRepository.findQuestionsWithoutCorrectOption(1L))
                .thenReturn(Flux.just(question(4L, 3)));

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("no correct option"))
                .verify();

        verify(questionRepository, never()).calculateTotalMaxScore(anyLong());
    }

    @Test
    void setVisibleUpdatesVisibilityFlag() {
        Statement existing = statement(1L);
        existing.setVisible(false);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.setVisible(1L, true, 9L, true))
                .assertNext(result -> assertThat(result.getVisible()).isTrue())
                .verifyComplete();
    }

    // ── Who may change a statement ──────────────────────────────────────────

    @Test
    void approveReviewIsForbiddenForATeacherOutsideTheirClassAndSubject() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(writeAccess.checkCanWrite(any(), eq(9L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.approveReview(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statementRepository, never()).save(any());
    }

    @Test
    void setVisibilityIsForbiddenForATeacherOutsideTheirClassAndSubject() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(writeAccess.checkCanWrite(any(), eq(9L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.setVisible(1L, true, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statementRepository, never()).save(any());
    }

    @Test
    void softDeleteIsForbiddenForATeacherOutsideTheirClassAndSubject() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(writeAccess.checkCanWrite(any(), eq(9L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.softDelete(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        assertThat(existing.getDeletedAt()).isNull();
        verify(questionRepository, never()).softDeleteAllByStatementId(any(), any());
    }

    @Test
    void purgeIsForbiddenForATeacherOutsideTheirClassAndSubject() {
        Statement trashed = statementOfSchool(1L, 4L, 3L);
        trashed.markAsDeleted();
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(trashed));
        when(writeAccess.checkCanWrite(any(), eq(9L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.hardDelete(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(questionRepository, never()).deleteAllByStatementId(any());
        verify(statementRepository, never()).delete(any());
    }

    @Test
    void restoreIsForbiddenForATeacherOutsideTheirClassAndSubject() {
        Statement trashed = statementOfSchool(1L, 4L, 3L);
        trashed.markAsDeleted();
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(trashed));
        when(writeAccess.checkCanWrite(any(), eq(9L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.restore(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        assertThat(trashed.getDeletedAt()).isNotNull();
        verify(questionRepository, never())
                .restoreAllDeletedByStatementIdAndDeletedAt(any(), any());
    }

    @Test
    void approveReviewAsksTheWriteRuleAboutTheStatementItStands() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenTheWriteRuleAllows();
        givenTheAnswerKeyIsComplete();
        givenTheScoresAddUp(0.0);

        StepVerifier.create(service.approveReview(1L, 9L, false)).expectNextCount(1).verifyComplete();

        verify(writeAccess).checkCanWrite(existing, 9L, false);
    }

    // ── The rule itself ─────────────────────────────────────────────────────
    // Which statements a caller may write, and on what grounds, is
    // StatementWriteAccessService's own test; the real chain over HTTP is in
    // StatementApprovalAuthorizationTest. What matters here is that every
    // mutating operation goes through the rule, and the five
    // "...IsForbiddenForATeacherOutsideTheirClassAndSubject" cases above are
    // exactly that.

    @Test
    void everyMutationGoesThroughTheWriteRule() {
        // approveReview, setVisible, softDelete and update are each covered by
        // their own case above; this one pins that the rule is consulted for a
        // purge, where the statement is being destroyed outright.
        Statement trashed = statementOfSchool(1L, 4L, 3L);
        trashed.markAsDeleted();
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(trashed));
        givenTheWriteRuleAllows();
        when(questionRepository.deleteAllByStatementId(1L)).thenReturn(Mono.empty());
        when(statementRepository.delete(trashed)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L, 9L, false)).verifyComplete();

        verify(writeAccess).checkCanWrite(trashed, 9L, false);
    }

    @Test
    void countActiveDelegatesToRepository() {
        when(statementRepository.countByDeletedAtIsNull()).thenReturn(Mono.just(5L));

        StepVerifier.create(service.countActive()).expectNext(5L).verifyComplete();
    }

    @Test
    void countNeedingReviewDelegatesToRepository() {
        when(statementRepository.countByNeedsReviewTrueAndDeletedAtIsNull()).thenReturn(Mono.just(2L));

        StepVerifier.create(service.countNeedingReview()).expectNext(2L).verifyComplete();
    }

    @Test
    void countBySourceDelegatesToRepository() {
        when(statementRepository.countBySourceAndDeletedAtIsNull("ocr")).thenReturn(Mono.just(3L));

        StepVerifier.create(service.countBySource("ocr")).expectNext(3L).verifyComplete();
    }

    private Statement statement(Long id) {
        Statement statement = new Statement();
        statement.setId(id);
        statement.setTitle("Prova Teste");
        return statement;
    }

    /** No question of the statement has alternatives without an answer. */
    private void givenTheAnswerKeyIsComplete() {
        when(questionRepository.findQuestionsWithoutCorrectOption(anyLong())).thenReturn(Flux.empty());
    }

    private void givenTheScoresAddUp(double sum) {
        when(questionRepository.calculateTotalMaxScore(anyLong())).thenReturn(Mono.just(sum));
    }

    private Question question(Long id, Integer number) {
        Question question = new Question(1L, number, "texto", "multiple_choice");
        question.setId(id);
        return question;
    }

    // ── Editing the metadata, never the questions ──────────────────────────

    @Test
    void updateReplacesTheMetadataAndLeavesTheQuestionsAlone() {
        givenTheEditIsAllowed();
        Statement existing = givenAStatementToEdit();
        existing.setTitle("Prova errada");
        existing.setNeedsReview(true);
        existing.setSource("ocr");

        StepVerifier.create(service.update(1L, request(), 9L, false)).expectNextCount(1).verifyComplete();

        assertThat(existing.getTitle()).isEqualTo("Prova de Matemática");
        assertThat(existing.getExamType()).isEqualTo("P1");
        assertThat(existing.getInstitutionId()).isEqualTo(1L);
        assertThat(existing.getClassId()).isEqualTo(CLASS_ID);
        assertThat(existing.getTotalMaxScore()).isEqualTo(20.0);
        // Provenance and the review gate are not the teacher's to move from here.
        assertThat(existing.getSource()).isEqualTo("ocr");
        assertThat(existing.getNeedsReview()).isTrue();
        verify(questionRepository, never()).deleteAllByStatementId(any());
        verify(questionRepository, never()).save(any());
    }

    @Test
    void updateDropsABlankVariantAndInstructions() {
        Statement existing = givenAStatementToEdit();
        givenTheEditIsAllowed();

        StepVerifier.create(service.update(1L, requestWithBlanks(), 9L, false))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(existing.getVariant()).isNull();
        assertThat(existing.getInstructions()).isNull();
    }

    @Test
    void updateChecksTheStatementAsItStandsAndTheMetadataBeingWritten() {
        givenTheEditIsAllowed();
        givenAStatementToEdit();

        StepVerifier.create(service.update(1L, request(), 9L, false)).expectNextCount(1).verifyComplete();

        // Once the statement as it stands, which the write rule weighs, and once the
        // metadata being written. Dropping either half lets a teacher steal
        // another teacher's paper, or hand one to a class they do not teach.
        verify(writeAccess).checkCanWrite(any(), eq(9L), eq(false));
        verify(accessService).requireCanAuthor(9L, false, 1L, 2L, CLASS_ID);
    }

    @Test
    void updateIsForbiddenWhenTheTeacherCannotMoveTheStatementToTheNewClass() {
        when(validator.requireAClassForTeachers(any(), anyBoolean())).thenReturn(Mono.empty());
        givenAStatementToEdit();
        givenTheWriteRuleAllows();
        when(accessService.requireCanAuthor(9L, false, 1L, 2L, CLASS_ID))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.update(1L, request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statementRepository, never()).save(any());
    }


    @Test
    void updateRefusesAMetadataWithoutAClassFromATeacher() {
        when(validator.requireAClassForTeachers(null, false))
                .thenReturn(Mono.error(ApiException.unprocessableEntity("A teacher must choose the class")));

        StepVerifier.create(service.update(1L, requestWithoutClass(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verifyNoInteractions(statementRepository);
    }

    @Test
    void updateOfAStatementThatDoesNotExistIsNotFound() {
        givenTheEditIsAllowed();
        when(statementRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(statementRepository, never()).save(any());
    }

    @Test
    void updateRefusesMetadataThatDoesNotHangTogether() {
        givenTheEditIsAllowed();
        givenAStatementToEdit();
        when(validator.validate(7L, 8L, CLASS_ID, 2L, 9L))
                .thenReturn(Mono.error(ApiException.unprocessableEntity(
                        "The school year must be the one of the class")));

        StepVerifier.create(service.update(1L, request(), 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verify(statementRepository, never()).save(any());
    }

    @Test
    void updateReplacesEveryFieldItIsGivenAndClearsTheOnesItIsNot() {
        // PUT semantics, pinned on purpose: the optional references are cleared
        // when omitted, not kept. Clearing schoolYearId also switches off the
        // class-to-year check, since there is then nothing to compare against.
        Statement existing = givenAStatementToEdit();
        existing.setTermId(8L);
        existing.setCourseId(9L);
        existing.setDurationMinutes(60);
        givenTheEditIsAllowed();

        StepVerifier.create(service.update(1L, requestWithoutTheOptionalReferences(), 9L, false))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(existing.getTermId()).isNull();
        assertThat(existing.getCourseId()).isNull();
        assertThat(existing.getDurationMinutes()).isEqualTo(120);
        assertThat(existing.getSchoolYearId()).isEqualTo(7L);
    }

    @Test
    void updateTakesTheTotalScoreFromTheCallerAndNotFromTheQuestions() {
        // The deliberate asymmetry with creation, where the total is summed from
        // the questions: here it is the teacher's number, because correcting what
        // the OCR read off a scan is the point of the edit.
        Statement existing = givenAStatementToEdit();
        givenTheEditIsAllowed();

        StepVerifier.create(service.update(1L, request(), 9L, false)).expectNextCount(1).verifyComplete();

        assertThat(existing.getTotalMaxScore()).isEqualTo(20.0);
        verifyNoInteractions(questionRepository);
    }

    @Test
    void updateClearsTheTotalScoreWhenTheCallerOmitsIt() {
        Statement existing = givenAStatementToEdit();
        existing.setTotalMaxScore(20.0);
        givenTheEditIsAllowed();

        StepVerifier.create(service.update(1L, requestWithoutTotalScore(), 9L, false))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(existing.getTotalMaxScore()).isNull();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

// ── The hole in the institution-less carve-out ─────────────────────────

    private static final Long SOMEONE_ELSES_CLASS_ID = 3L;





    private static final Long CLASS_ID = 6L;

    /** Every check an edit passes, so a test only has to stub the one it is about. */
    private void givenTheEditIsAllowed() {
        when(validator.requireAClassForTeachers(any(), anyBoolean())).thenReturn(Mono.empty());
        when(accessService.requireCanAuthor(anyLong(), anyBoolean(), anyLong(), anyLong(), any()))
                .thenReturn(Mono.empty());
        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    /** A statement of a school, a class and a subject, readable and writable. */
    private Statement givenAStatementToEdit() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        return existing;
    }

    private StatementRequest request() {
        return new StatementRequest(
                1L, 2L, "Prova de Matemática", "P1", 120, "Variante A", "Leia com atenção", 20.0,
                7L, 8L, CLASS_ID, 9L);
    }

    private StatementRequest requestWithoutClass() {
        return new StatementRequest(
                1L, 2L, "Prova de Matemática", "P1", 120, null, null, 20.0, 7L, 8L, null, null);
    }

    private StatementRequest requestWithBlanks() {
        return new StatementRequest(
                1L, 2L, "Prova de Matemática", "P1", 120, "  ", "   ", 20.0, 7L, 8L, CLASS_ID, 9L);
    }

    private StatementRequest requestWithoutTotalScore() {
        return new StatementRequest(
                1L, 2L, "Prova de Matemática", "P1", 120, null, null, null, 7L, 8L, CLASS_ID, 9L);
    }

    private StatementRequest requestWithoutTheOptionalReferences() {
        return new StatementRequest(
                1L, 2L, "Prova de Matemática", "P1", 120, null, null, 20.0, 7L, null, CLASS_ID, null);
    }

    /** A statement built in the exam builder: it belongs to a school, a class and a subject. */
    private Statement statementOfSchool(Long id, Long institutionId, Long classId) {
        Statement statement = statement(id);
        statement.setInstitutionId(institutionId);
        statement.setClassId(classId);
        statement.setSubjectId(5L);
        return statement;
    }
}
