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
    private StatementService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        questionRepository = mock(QuestionRepository.class);
        optionRepository = mock(QuestionOptionRepository.class);
        accessService = mock(InstitutionAccessService.class);
        validator = mock(StatementLinkValidationService.class);
        service = new StatementService(
                statementRepository, questionRepository, optionRepository, accessService, validator);
    }

    /**
     * Statements built in the exam builder carry an institution, which is what the
     * rule is checked against; the default mock lets every check pass.
     */
    private void givenAuthorIsAllowed() {
        when(accessService.requireCanAuthor(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyLong(), anyLong(), anyLong())).thenReturn(Mono.empty());
        when(accessService.requireCanAuthor(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull())).thenReturn(Mono.empty());
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

        StepVerifier.create(service.approveReview(1L, 9L, true))
                .assertNext(result -> assertThat(result.getNeedsReview()).isFalse())
                .verifyComplete();
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
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L))
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
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L))
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
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L))
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
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L))
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
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L))
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
    void approveReviewChecksTheClassScopeOfTheStatement() {
        Statement existing = statementOfSchool(1L, 4L, 3L);
        existing.setNeedsReview(true);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        givenAuthorIsAllowed();

        StepVerifier.create(service.approveReview(1L, 9L, false)).expectNextCount(1).verifyComplete();

        verify(accessService).requireCanAuthor(9L, false, 4L, 5L, 3L);
    }

    @Test
    void aStatementFromBeforeTheInstitutionModelIsNotChecked() {
        Statement legacy = statement(1L);
        legacy.setNeedsReview(true);
        assertThat(legacy.getInstitutionId()).isNull();
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(legacy));
        when(statementRepository.save(legacy)).thenReturn(Mono.just(legacy));

        StepVerifier.create(service.approveReview(1L, 9L, false)).expectNextCount(1).verifyComplete();

        verifyNoInteractions(accessService);
    }

    // ── A school statement with no class is the administrator's alone ───────

    /** A statement of the school but of no class: nothing to match an assignment against. */
    private Statement statementOfSchoolWithoutClass() {
        Statement statement = statement(1L);
        statement.setInstitutionId(4L);
        statement.setSubjectId(5L);
        statement.setClassId(null);
        statement.setNeedsReview(true);
        return statement;
    }

    @Test
    void aTeacherMayNotApproveASchoolStatementThatNamesNoClass() {
        // Only an administrator may build one (ManualStatementService), so only an
        // administrator may change it. Letting the rule lapse here would hand any
        // teacher affiliated to the school — holding no assignment at all — the
        // right to approve or delete a statement the school made on purpose.
        Statement existing = statementOfSchoolWithoutClass();
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approveReview(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(statementRepository, never()).save(any());
        verifyNoInteractions(accessService);
    }

    @Test
    void anAdministratorMayApproveASchoolStatementThatNamesNoClass() {
        Statement existing = statementOfSchoolWithoutClass();
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));
        // The rule itself imposes nothing on the administrator; the institution
        // and subject checks still run.
        when(accessService.requireCanAuthor(9L, true, 4L, 5L, null)).thenReturn(Mono.empty());

        StepVerifier.create(service.approveReview(1L, 9L, true)).expectNextCount(1).verifyComplete();

        verify(statementRepository).save(existing);
    }

    @Test
    void aTeacherMayNotPurgeASchoolStatementThatNamesNoClass() {
        Statement trashed = statementOfSchoolWithoutClass();
        trashed.markAsDeleted();
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.hardDelete(1L, 9L, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        assertThat(trashed.getDeletedAt()).isNotNull();
        verify(statementRepository, never()).delete(any());
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

        // Once against the class the statement is in now (4L), once against the
        // class it is being moved to (1L). Dropping either half lets a teacher
        // steal another teacher's paper, or hand one to a class they do not teach.
        verify(accessService).requireCanAuthor(9L, false, 4L, 5L, 3L);
        verify(accessService).requireCanAuthor(9L, false, 1L, 2L, CLASS_ID);
    }

    @Test
    void updateIsForbiddenWhenTheTeacherCannotMoveTheStatementToTheNewClass() {
        when(validator.requireAClassForTeachers(any(), anyBoolean())).thenReturn(Mono.empty());
        givenAStatementToEdit();
        when(accessService.requireCanAuthor(9L, false, 4L, 5L, 3L)).thenReturn(Mono.empty());
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
    void aTeacherMayNotEditAStatementWithoutAClass() {
        Statement existing = statementOfSchool(1L, 4L, null);
        when(validator.requireAClassForTeachers(CLASS_ID, false)).thenReturn(Mono.empty());
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));

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

    // ── Fixtures ────────────────────────────────────────────────────────────

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

    /** A statement built in the exam builder: it belongs to a school, a class and a subject. */
    private Statement statementOfSchool(Long id, Long institutionId, Long classId) {
        Statement statement = statement(id);
        statement.setInstitutionId(institutionId);
        statement.setClassId(classId);
        statement.setSubjectId(5L);
        return statement;
    }
}
