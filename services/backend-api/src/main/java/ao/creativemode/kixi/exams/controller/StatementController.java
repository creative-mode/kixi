package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.dto.StatementOcrResponse;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

/**
 * REST Controller for Statement (exam paper) management.
 *
 * Provides endpoints for:
 * - CRUD operations on statements
 * - Managing statement visibility and review status
 * - Retrieving statements with their questions and options
 *
 * OCR-based creation lives in the ocr module's LegacyOcrStatementController,
 * under this same base path, so that this module never needs to depend on
 * the OCR client.
 *
 * Base path: /api/v1/statements
 */
@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private final StatementService statementService;
    private final CurrentAccountService currentAccountService;

    public StatementController(
        StatementService statementService,
        CurrentAccountService currentAccountService
    ) {
        this.statementService = statementService;
        this.currentAccountService = currentAccountService;
    }

    // =========================================================================
    // Standard CRUD Endpoints
    // =========================================================================

    /**
     * Get all active statements.
     */
    @GetMapping
    public Mono<ResponseEntity<List<StatementSummary>>> listAllActive() {
        return readableStatements(statementService::findAllActive, statementService::findAllVisible)
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get all statements that need review.
     */
    @GetMapping("/review")
    public Mono<ResponseEntity<List<StatementSummary>>> listNeedingReview() {
        return statementService
            .findNeedingReview()
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get all statements created via OCR.
     */
    @GetMapping("/from-ocr")
    public Mono<ResponseEntity<List<StatementSummary>>> listFromOcr() {
        return statementService
            .findFromOcr()
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get all deleted (trashed) statements.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<StatementSummary>>> listTrashed() {
        return statementService
            .findAllDeleted()
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get a statement by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<StatementSummary>> getById(
        @PathVariable Long id
    ) {
        return readableStatement(() -> statementService.findById(id), () -> statementService.findByIdVisible(id))
            .map(StatementSummary::from)
            .map(ResponseEntity::ok);
    }

    /**
     * Get a statement with all its questions and options.
     */
    @GetMapping("/{id}/full")
    public Mono<ResponseEntity<StatementOcrResponse>> getByIdWithQuestions(
        @PathVariable Long id
    ) {
        return readableStatementWithQuestions(
                () -> statementService.findByIdWithQuestions(id),
                () -> statementService.findByIdWithQuestionsVisible(id))
            .map(StatementOcrResponse::from)
            .map(ResponseEntity::ok);
    }

    /**
     * Search statements by title.
     */
    @GetMapping("/search")
    public Mono<ResponseEntity<List<StatementSummary>>> searchByTitle(
        @RequestParam String query
    ) {
        return readableStatements(
                () -> statementService.searchByTitle(query),
                () -> statementService.searchByTitleVisible(query))
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get statements by school year.
     */
    @GetMapping("/by-school-year/{schoolYearId}")
    public Mono<ResponseEntity<List<StatementSummary>>> getBySchoolYear(
        @PathVariable Long schoolYearId
    ) {
        return readableStatements(
                () -> statementService.findBySchoolYear(schoolYearId),
                () -> statementService.findBySchoolYearVisible(schoolYearId))
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Get statements by subject.
     */
    @GetMapping("/by-subject/{subjectId}")
    public Mono<ResponseEntity<List<StatementSummary>>> getBySubject(
        @PathVariable Long subjectId
    ) {
        return readableStatements(
                () -> statementService.findBySubject(subjectId),
                () -> statementService.findBySubjectVisible(subjectId))
            .map(StatementSummary::from)
            .collectList()
            .map(ResponseEntity::ok);
    }

    /** The signed-in account and whether it is an administrator, for the write operations. */
    private Mono<Tuple2<Long, Boolean>> currentAuthor() {
        return Mono.zip(
            currentAccountService.requiredAccountId(),
            currentAccountService.hasAnyRole("ADMIN")
        );
    }

    private Flux<Statement> readableStatements(
        Supplier<Flux<Statement>> staffQuery,
        Supplier<Flux<Statement>> studentQuery
    ) {
        return currentAccountService.hasAnyRole("ADMIN", "TEACHER")
            .flatMapMany(isStaff -> isStaff ? staffQuery.get() : studentQuery.get());
    }

    private Mono<Statement> readableStatement(
        Supplier<Mono<Statement>> staffQuery,
        Supplier<Mono<Statement>> studentQuery
    ) {
        return currentAccountService.hasAnyRole("ADMIN", "TEACHER")
            .flatMap(isStaff -> isStaff ? staffQuery.get() : studentQuery.get());
    }

    private Mono<StatementWithQuestions> readableStatementWithQuestions(
        Supplier<Mono<StatementWithQuestions>> staffQuery,
        Supplier<Mono<StatementWithQuestions>> studentQuery
    ) {
        return currentAccountService.hasAnyRole("ADMIN", "TEACHER")
            .flatMap(isStaff -> isStaff ? staffQuery.get() : studentQuery.get());
    }

    /**
     * Soft delete a statement.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return currentAuthor()
            .flatMap(author -> statementService.softDelete(id, author.getT1(), author.getT2()))
            .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Restore a soft-deleted statement.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return statementService
            .restore(id)
            .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Permanently delete a statement (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return statementService
            .hardDelete(id)
            .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Approve review and make statement visible.
     */
    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<StatementSummary>> approveReview(
        @PathVariable Long id
    ) {
        return currentAuthor()
            .flatMap(author -> statementService.approveReview(id, author.getT1(), author.getT2()))
            .map(StatementSummary::from)
            .map(ResponseEntity::ok);
    }

    /**
     * Set statement visibility.
     */
    @PatchMapping("/{id}/visibility")
    public Mono<ResponseEntity<StatementSummary>> setVisibility(
        @PathVariable Long id,
        @RequestParam boolean visible
    ) {
        return currentAuthor()
            .flatMap(author ->
                statementService.setVisible(id, visible, author.getT1(), author.getT2()))
            .map(StatementSummary::from)
            .map(ResponseEntity::ok);
    }

    // =========================================================================
    // Statistics Endpoints
    // =========================================================================

    /**
     * Get statement statistics.
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> getStatistics() {
        return Mono.zip(
            statementService.countActive(),
            statementService.countNeedingReview(),
            statementService.countBySource("ocr"),
            statementService.countBySource("manual")
        )
            .map(tuple -> {
                Map<String, Object> stats = new HashMap<>();
                stats.put("totalActive", tuple.getT1());
                stats.put("needingReview", tuple.getT2());
                stats.put("fromOcr", tuple.getT3());
                stats.put("manual", tuple.getT4());
                return stats;
            })
            .map(ResponseEntity::ok);
    }

    // =========================================================================
    // Response DTOs
    // =========================================================================

    /**
     * Summary response for statement listing.
     */
    public record StatementSummary(
        Long id,
        String title,
        String examType,
        Integer durationMinutes,
        String variant,
        Double totalMaxScore,
        Boolean visible,
        Boolean needsReview,
        String source,
        Double ocrConfidence,
        Long schoolYearId,
        Long termId,
        Long subjectId,
        Long classId,
        Long institutionId
    ) {
        public static StatementSummary from(Statement statement) {
            return new StatementSummary(
                statement.getId(),
                statement.getTitle(),
                statement.getExamType(),
                statement.getDurationMinutes(),
                statement.getVariant(),
                statement.getTotalMaxScore(),
                statement.getVisible(),
                statement.getNeedsReview(),
                statement.getSource(),
                statement.getOcrConfidence(),
                statement.getSchoolYearId(),
                statement.getTermId(),
                statement.getSubjectId(),
                statement.getClassId(),
                statement.getInstitutionId()
            );
        }
    }
}
