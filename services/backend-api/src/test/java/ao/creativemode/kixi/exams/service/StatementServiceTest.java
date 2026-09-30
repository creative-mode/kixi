package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private StatementService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        questionRepository = mock(QuestionRepository.class);
        optionRepository = mock(QuestionOptionRepository.class);
        service = new StatementService(statementRepository, questionRepository, optionRepository);
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

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

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

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
    }

    @Test
    void restoreRejectsStatementThatIsNotInTrash() {
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
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

        StepVerifier.create(service.restore(1L)).verifyComplete();

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

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(questionRepository).deleteAllByStatementId(1L);
        verify(statementRepository).delete(existing);
    }

    @Test
    void hardDeleteRejectsStatementThatIsNotInTrash() {
        when(statementRepository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
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

        StepVerifier.create(service.approveReview(1L))
                .assertNext(result -> assertThat(result.getNeedsReview()).isFalse())
                .verifyComplete();
    }

    @Test
    void setVisibleUpdatesVisibilityFlag() {
        Statement existing = statement(1L);
        existing.setVisible(false);
        when(statementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(statementRepository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.setVisible(1L, true))
                .assertNext(result -> assertThat(result.getVisible()).isTrue())
                .verifyComplete();
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
}
