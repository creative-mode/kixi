package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionOption;
import ao.creativemode.kixi.service.StatementService;
import ao.creativemode.kixi.service.StatementService.StatementWithQuestions;
import ao.creativemode.kixi.common.exception.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for Statement (exam paper) management.
 *
 * Provides endpoints for:
 * - CRUD operations on statements
 * - OCR-based statement creation from images
 * - Managing statement visibility and review status
 * - Retrieving statements with their questions and options
 *
 * Base path: /api/v1/statements
 */
@RestController
@RequestMapping("/api/v1/statements")
public class StatementController {

    private static final Logger log = LoggerFactory.getLogger(StatementController.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".pdf", ".webp", ".bmp", ".tiff", ".tif"
    );

    private static final int MAX_FILES = 10;

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    // =========================================================================
    // OCR Endpoints
    // =========================================================================

    /**
     * Create a statement from uploaded images using OCR.
     *
     * This endpoint receives image files, sends them to the OCR service,
     * and creates a Statement with Questions based on the extracted data.
     *
     * @param files Uploaded image files (multipart/form-data)
     * @param uriBuilder URI builder for location header
     * @return Created statement with questions and options
     */
    @PostMapping(value = "/ocr/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<StatementOcrResponse>> createFromOcr(
            @RequestPart("files") Flux<FilePart> files,
            UriComponentsBuilder uriBuilder) {

        log.info("OCR statement creation request received");

        return files
                .collectList()
                .flatMap(fileList -> {
                    // Validate file count
                    if (fileList.isEmpty()) {
                        return Mono.error(ApiException.badRequest("At least one file is required"));
                    }
                    if (fileList.size() > MAX_FILES) {
                        return Mono.error(ApiException.badRequest(
                                "Maximum " + MAX_FILES + " files allowed per request"));
                    }

                    // Validate file types
                    for (FilePart file : fileList) {
                        if (!isAllowedFileType(file.filename())) {
                            return Mono.error(ApiException.badRequest(
                                    "Invalid file type: " + file.filename() +
                                    ". Allowed: " + String.join(", ", ALLOWED_EXTENSIONS)));
                        }
                    }

                    log.info("Processing {} file(s) for OCR-based statement creation", fileList.size());

                    // TODO: Get actual user ID from authentication context
                    Long createdBy = 1L; // Placeholder

                    return statementService.createFromOcr(fileList, createdBy);
                })
                .map(result -> {
                    URI location = uriBuilder
                            .path("/api/v1/statements/{id}")
                            .buildAndExpand(result.statement().getId())
                            .toUri();

                    StatementOcrResponse response = StatementOcrResponse.from(result);

                    return ResponseEntity.created(location).body(response);
                })
                .doOnSuccess(response -> log.info(
                        "Statement created from OCR: id={}",
                        response.getBody() != null ? response.getBody().id() : null))
                .doOnError(error -> log.error("OCR statement creation failed", error));
    }

    /**
     * Create a statement from a single uploaded image using OCR.
     *
     * Simplified endpoint for single-file uploads.
     *
     * @param file Single uploaded image file
     * @param uriBuilder URI builder for location header
     * @return Created statement with questions and options
     */
    @PostMapping(value = "/ocr/extract/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<StatementOcrResponse>> createFromOcrSingle(
            @RequestPart("file") FilePart file,
            UriComponentsBuilder uriBuilder) {

        log.info("Single-file OCR statement creation request received: {}", file.filename());

        // Validate file type
        if (!isAllowedFileType(file.filename())) {
            return Mono.error(ApiException.badRequest(
                    "Invalid file type: " + file.filename() +
                    ". Allowed: " + String.join(", ", ALLOWED_EXTENSIONS)));
        }

        // TODO: Get actual user ID from authentication context
        Long createdBy = 1L; // Placeholder

        return statementService.createFromOcr(List.of(file), createdBy)
                .map(result -> {
                    URI location = uriBuilder
                            .path("/api/v1/statements/{id}")
                            .buildAndExpand(result.statement().getId())
                            .toUri();

                    StatementOcrResponse response = StatementOcrResponse.from(result);

                    return ResponseEntity.created(location).body(response);
                })
                .doOnSuccess(response -> log.info(
                        "Statement created from single-file OCR: id={}",
                        response.getBody() != null ? response.getBody().id() : null))
                .doOnError(error -> log.error("Single-file OCR statement creation failed", error));
    }

    // =========================================================================
    // Standard CRUD Endpoints
    // =========================================================================

    /**
     * Get all active statements.
     */
    @GetMapping
    public Mono<ResponseEntity<List<StatementSummary>>> listAllActive() {
        return statementService.findAllActive()
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get all statements that need review.
     */
    @GetMapping("/review")
    public Mono<ResponseEntity<List<StatementSummary>>> listNeedingReview() {
        return statementService.findNeedingReview()
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get all statements created via OCR.
     */
    @GetMapping("/from-ocr")
    public Mono<ResponseEntity<List<StatementSummary>>> listFromOcr() {
        return statementService.findFromOcr()
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get all deleted (trashed) statements.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<StatementSummary>>> listTrashed() {
        return statementService.findAllDeleted()
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get a statement by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<StatementSummary>> getById(@PathVariable Long id) {
        return statementService.findById(id)
                .map(StatementSummary::from)
                .map(ResponseEntity::ok);
    }

    /**
     * Get a statement with all its questions and options.
     */
    @GetMapping("/{id}/full")
    public Mono<ResponseEntity<StatementOcrResponse>> getByIdWithQuestions(@PathVariable Long id) {
        return statementService.findByIdWithQuestions(id)
                .map(StatementOcrResponse::from)
                .map(ResponseEntity::ok);
    }

    /**
     * Search statements by title.
     */
    @GetMapping("/search")
    public Mono<ResponseEntity<List<StatementSummary>>> searchByTitle(
            @RequestParam String query) {
        return statementService.searchByTitle(query)
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get statements by school year.
     */
    @GetMapping("/by-school-year/{schoolYearId}")
    public Mono<ResponseEntity<List<StatementSummary>>> getBySchoolYear(
            @PathVariable Long schoolYearId) {
        return statementService.findBySchoolYear(schoolYearId)
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Get statements by subject.
     */
    @GetMapping("/by-subject/{subjectId}")
    public Mono<ResponseEntity<List<StatementSummary>>> getBySubject(
            @PathVariable Long subjectId) {
        return statementService.findBySubject(subjectId)
                .map(StatementSummary::from)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Soft delete a statement.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return statementService.softDelete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Restore a soft-deleted statement.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return statementService.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently delete a statement (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return statementService.hardDelete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Approve review and make statement visible.
     */
    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<StatementSummary>> approveReview(@PathVariable Long id) {
        return statementService.approveReview(id)
                .map(StatementSummary::from)
                .map(ResponseEntity::ok);
    }

    /**
     * Set statement visibility.
     */
    @PatchMapping("/{id}/visibility")
    public Mono<ResponseEntity<StatementSummary>> setVisibility(
            @PathVariable Long id,
            @RequestParam boolean visible) {
        return statementService.setVisible(id, visible)
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
        ).map(tuple -> Map.of(
                "totalActive", tuple.getT1(),
                "needingReview", tuple.getT2(),
                "fromOcr", tuple.getT3(),
                "manual", tuple.getT4()
        )).map(ResponseEntity::ok);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Validate file extension.
     */
    private boolean isAllowedFileType(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }

        String lowerFilename = filename.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
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
            Long classId
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
                    statement.getClassId()
            );
        }
    }

    /**
     * Full response including questions and options.
     */
    public record StatementOcrResponse(
            Long id,
            String title,
            String examType,
            Integer durationMinutes,
            String variant,
            String instructions,
            Double totalMaxScore,
            Boolean visible,
            Boolean needsReview,
            String source,
            Double ocrConfidence,
            String ocrRequestId,
            Long schoolYearId,
            Long termId,
            Long subjectId,
            Long classId,
            List<QuestionResponse> questions
    ) {
        public static StatementOcrResponse from(StatementWithQuestions result) {
            Statement s = result.statement();
            List<Question> questions = result.questions();
            List<QuestionOption> allOptions = result.options();

            List<QuestionResponse> questionResponses = questions.stream()
                    .map(q -> {
                        List<OptionResponse> options = allOptions.stream()
                                .filter(opt -> opt.getQuestionId().equals(q.getId()))
                                .map(OptionResponse::from)
                                .toList();
                        return QuestionResponse.from(q, options);
                    })
                    .toList();

            return new StatementOcrResponse(
                    s.getId(),
                    s.getTitle(),
                    s.getExamType(),
                    s.getDurationMinutes(),
                    s.getVariant(),
                    s.getInstructions(),
                    s.getTotalMaxScore(),
                    s.getVisible(),
                    s.getNeedsReview(),
                    s.getSource(),
                    s.getOcrConfidence(),
                    s.getOcrRequestId(),
                    s.getSchoolYearId(),
                    s.getTermId(),
                    s.getSubjectId(),
                    s.getClassId(),
                    questionResponses
            );
        }
    }

    /**
     * Question response DTO.
     */
    public record QuestionResponse(
            Long id,
            Integer number,
            String text,
            String questionType,
            Double maxScore,
            Integer orderIndex,
            Double ocrConfidence,
            Integer pageIndex,
            Boolean needsReview,
            List<OptionResponse> options
    ) {
        public static QuestionResponse from(Question q, List<OptionResponse> options) {
            return new QuestionResponse(
                    q.getId(),
                    q.getNumber(),
                    q.getText(),
                    q.getQuestionType(),
                    q.getMaxScore(),
                    q.getOrderIndex(),
                    q.getOcrConfidence(),
                    q.getPageIndex(),
                    q.getNeedsReview(),
                    options
            );
        }
    }

    /**
     * Option response DTO.
     */
    public record OptionResponse(
            Long id,
            String optionLabel,
            String optionText,
            Boolean isCorrect,
            Integer orderIndex,
            Double ocrConfidence
    ) {
        public static OptionResponse from(QuestionOption o) {
            return new OptionResponse(
                    o.getId(),
                    o.getOptionLabel(),
                    o.getOptionText(),
                    o.getIsCorrect(),
                    o.getOrderIndex(),
                    o.getOcrConfidence()
            );
        }
    }
}
