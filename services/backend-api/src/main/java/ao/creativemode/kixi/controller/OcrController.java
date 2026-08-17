package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.ocr.ExamExtractionResponse;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.service.OcrPersistenceService;
import ao.creativemode.kixi.service.OcrPersistenceService.StatementWithRelations;
import ao.creativemode.kixi.service.CurrentAccountService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST Controller for OCR operations.
 *
 * Provides endpoints for extracting text from images and PDFs
 * using the OCR microservice, and persisting the results.
 *
 * Endpoints:
 * - POST /api/v1/ocr/extract - Extract text from uploaded images (OCR only)
 * - POST /api/v1/ocr/extract-and-persist - Extract and persist to database
 * - GET /api/v1/ocr/health - Check OCR service health
 * - GET /api/v1/ocr/languages - Get supported OCR languages
 */
@RestController
@RequestMapping("/api/v1/ocr")
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(
        OcrController.class
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".jpg",
        ".jpeg",
        ".png",
        ".pdf",
        ".webp",
        ".bmp",
        ".tiff",
        ".tif"
    );

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final int MAX_FILES = 10;

    private final OcrServiceClient ocrServiceClient;
    private final OcrPersistenceService ocrPersistenceService;
    private final CurrentAccountService currentAccountService;

    public OcrController(
        OcrServiceClient ocrServiceClient,
        OcrPersistenceService ocrPersistenceService,
        CurrentAccountService currentAccountService
    ) {
        this.ocrServiceClient = ocrServiceClient;
        this.ocrPersistenceService = ocrPersistenceService;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Extract text from uploaded images/PDFs (OCR only, no persistence).
     *
     * @param files List of uploaded file parts (images or PDFs)
     * @return OCR extraction result with structured data
     */
    @PostMapping(
        value = "/extract",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<OcrResponse>> extractText(
        @RequestPart("files") Flux<FilePart> files
    ) {
        log.info("OCR extraction request received");

        return files
            .collectList()
            .flatMap(fileList -> {
                // Validate file count
                if (fileList.isEmpty()) {
                    return Mono.error(
                        ApiException.badRequest("At least one file is required")
                    );
                }
                if (fileList.size() > MAX_FILES) {
                    return Mono.error(
                        ApiException.badRequest(
                            "Maximum " +
                                MAX_FILES +
                                " files allowed per request"
                        )
                    );
                }

                // Validate file types
                for (FilePart file : fileList) {
                    if (!isAllowedFileType(file.filename())) {
                        return Mono.error(
                            ApiException.badRequest(
                                "Invalid file type: " +
                                    file.filename() +
                                    ". Allowed: " +
                                    String.join(", ", ALLOWED_EXTENSIONS)
                            )
                        );
                    }
                }

                log.info(
                    "Processing {} file(s) for OCR extraction",
                    fileList.size()
                );

                // Call OCR service
                return ocrServiceClient.extractText(fileList);
            })
            .map(ocrResponse -> {
                // Return appropriate status based on OCR result
                if (ocrResponse.isSuccess()) {
                    return ResponseEntity.ok(ocrResponse);
                } else if (ocrResponse.isPartial()) {
                    return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(
                        ocrResponse
                    );
                } else {
                    return ResponseEntity.status(
                        HttpStatus.UNPROCESSABLE_ENTITY
                    ).body(ocrResponse);
                }
            })
            .doOnSuccess(response ->
                log.info(
                    "OCR extraction completed: status={}",
                    response.getStatusCode()
                )
            )
            .doOnError(error -> log.error("OCR extraction failed", error));
    }

    /**
     * Extract text from a single uploaded image/PDF (OCR only).
     *
     * @param file Single uploaded file part
     * @return OCR extraction result with structured data
     */
    @PostMapping(
        value = "/extract/single",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<OcrResponse>> extractTextSingle(
        @RequestPart("file") FilePart file
    ) {
        log.info(
            "Single-file OCR extraction request received: {}",
            file.filename()
        );

        // Validate file type
        if (!isAllowedFileType(file.filename())) {
            return Mono.error(
                ApiException.badRequest(
                    "Invalid file type: " +
                        file.filename() +
                        ". Allowed: " +
                        String.join(", ", ALLOWED_EXTENSIONS)
                )
            );
        }

        return ocrServiceClient
            .extractText(List.of(file))
            .map(ocrResponse -> {
                if (ocrResponse.isSuccess()) {
                    return ResponseEntity.ok(ocrResponse);
                } else if (ocrResponse.isPartial()) {
                    return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(
                        ocrResponse
                    );
                } else {
                    return ResponseEntity.status(
                        HttpStatus.UNPROCESSABLE_ENTITY
                    ).body(ocrResponse);
                }
            })
            .doOnSuccess(response ->
                log.info(
                    "Single-file OCR extraction completed: status={}",
                    response.getStatusCode()
                )
            )
            .doOnError(error ->
                log.error("Single-file OCR extraction failed", error)
            );
    }

    /**
     * Extract exam data in structured Angolan format.
     *
     * Returns the extraction result in the exact JSON structure required
     * for Angolan exam papers (12ª classe), including:
     * - exam_type, duration_minutes, variant, title, instructions
     * - school_year_start, school_year_end, class_grade, course_name, subject_name
     * - total_max_score
     * - questions with number, subitems, text, type, cotacao, options, has_image
     * - images_to_upload with suggested_filename, description, region
     *
     * @param files List of uploaded file parts (images or PDFs)
     * @return Structured exam extraction result
     */
    @PostMapping(
        value = "/extract/exam",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<ExamExtractionResponse>> extractExam(
        @RequestPart("files") Flux<FilePart> files
    ) {
        log.info("Angolan exam extraction request received");

        return files
            .collectList()
            .flatMap(fileList -> {
                // Validate file count
                if (fileList.isEmpty()) {
                    return Mono.error(
                        ApiException.badRequest("At least one file is required")
                    );
                }
                if (fileList.size() > MAX_FILES) {
                    return Mono.error(
                        ApiException.badRequest(
                            "Maximum " +
                                MAX_FILES +
                                " files allowed per request"
                        )
                    );
                }

                // Validate file types
                for (FilePart file : fileList) {
                    if (!isAllowedFileType(file.filename())) {
                        return Mono.error(
                            ApiException.badRequest(
                                "Invalid file type: " +
                                    file.filename() +
                                    ". Allowed: " +
                                    String.join(", ", ALLOWED_EXTENSIONS)
                            )
                        );
                    }
                }

                log.info(
                    "Processing {} file(s) for Angolan exam extraction",
                    fileList.size()
                );

                // Call OCR service
                return ocrServiceClient.extractText(fileList);
            })
            .map(ocrResponse -> {
                // Convert to structured exam format
                ExamExtractionResponse examResponse =
                    ExamExtractionResponse.fromOcrResponse(ocrResponse);

                if (ocrResponse.isSuccess()) {
                    return ResponseEntity.ok(examResponse);
                } else if (ocrResponse.isPartial()) {
                    return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(
                        examResponse
                    );
                } else {
                    return ResponseEntity.status(
                        HttpStatus.UNPROCESSABLE_ENTITY
                    ).body(examResponse);
                }
            })
            .doOnSuccess(response ->
                log.info(
                    "Angolan exam extraction completed: status={}",
                    response.getStatusCode()
                )
            )
            .doOnError(error ->
                log.error("Angolan exam extraction failed", error)
            );
    }

    /**
     * Extract text from uploaded images/PDFs and persist to database.
     *
     * This endpoint performs full OCR extraction and creates/updates:
     * - SchoolYear (lookup or create by start_year/end_year)
     * - Course (lookup or create by name)
     * - Subject (lookup or create by name)
     * - Class (lookup or create by grade/course/school_year)
     * - Statement with Questions and Options
     *
     * @param files List of uploaded file parts (images or PDFs)
     * @return Created statement with all related entities
     */
    @PostMapping(
        value = "/extract-and-persist",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<
        ResponseEntity<StatementWithRelationsResponse>
    > extractAndPersist(
        @RequestPart("files") Flux<FilePart> files
    ) {
        log.info("OCR extraction and persistence request received");

        return currentAccountService.requiredAccountId()
            .flatMap(createdBy -> files.collectList().flatMap(fileList -> {
                // Validate file count
                if (fileList.isEmpty()) {
                    return Mono.error(
                        ApiException.badRequest("At least one file is required")
                    );
                }
                if (fileList.size() > MAX_FILES) {
                    return Mono.error(
                        ApiException.badRequest(
                            "Maximum " +
                                MAX_FILES +
                                " files allowed per request"
                        )
                    );
                }

                // Validate file types
                for (FilePart file : fileList) {
                    if (!isAllowedFileType(file.filename())) {
                        return Mono.error(
                            ApiException.badRequest(
                                "Invalid file type: " +
                                    file.filename() +
                                    ". Allowed: " +
                                    String.join(", ", ALLOWED_EXTENSIONS)
                            )
                        );
                    }
                }

                log.info(
                    "Processing {} file(s) for OCR extraction and persistence",
                    fileList.size()
                );

                // Process and persist
                return ocrPersistenceService.processAndPersist(
                    fileList,
                    createdBy
                );
            }))
            .map(result -> {
                StatementWithRelationsResponse response =
                    new StatementWithRelationsResponse(
                        result.statement().getId(),
                        result.statement().getTitle(),
                        result.statement().getExamType(),
                        result.statement().getVariant(),
                        result.statement().getDurationMinutes(),
                        result.statement().getTotalMaxScore(),
                        result.statement().getOcrConfidence(),
                        result.statement().getNeedsReview(),
                        result.schoolYear() != null
                            ? new SchoolYearInfo(
                                  result.schoolYear().getId(),
                                  result.schoolYear().getStartYear(),
                                  result.schoolYear().getEndYear()
                              )
                            : null,
                        result.course() != null
                            ? new EntityInfo(
                                  result.course().getId(),
                                  result.course().getName()
                              )
                            : null,
                        result.subject() != null
                            ? new EntityInfo(
                                  result.subject().getId(),
                                  result.subject().getName()
                              )
                            : null,
                        result.classEntity() != null
                            ? new ClassInfo(
                                  result.classEntity().getId(),
                                  result.classEntity().getGrade(),
                                  result.classEntity().getCode()
                              )
                            : null,
                        result.questions() != null
                            ? result.questions().size()
                            : 0,
                        result.imagesToUpload() != null
                            ? result
                                  .imagesToUpload()
                                  .stream()
                                  .map(img ->
                                      new ImageToUploadInfo(
                                          img.suggestedFilename(),
                                          img.description(),
                                          img.region()
                                      )
                                  )
                                  .toList()
                            : List.of()
                    );
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            })
            .doOnSuccess(response ->
                log.info(
                    "OCR extraction and persistence completed: statementId={}",
                    response.getBody() != null
                        ? response.getBody().statementId()
                        : "null"
                )
            )
            .doOnError(error ->
                log.error("OCR extraction and persistence failed", error)
            );
    }

    /**
     * Check OCR service health.
     *
     * @return Health status of the OCR service
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> checkHealth() {
        return ocrServiceClient
            .healthCheck()
            .map(healthy -> {
                Map<String, Object> response = Map.of(
                    "service",
                    "ocr-service",
                    "status",
                    healthy ? "healthy" : "unhealthy",
                    "available",
                    healthy
                );
                return healthy
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.status(
                          HttpStatus.SERVICE_UNAVAILABLE
                      ).body(response);
            })
            .onErrorResume(error -> {
                log.error("OCR health check failed", error);
                Map<String, Object> response = Map.of(
                    "service",
                    "ocr-service",
                    "status",
                    "unavailable",
                    "available",
                    false,
                    "error",
                    error.getMessage()
                );
                return Mono.just(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                        response
                    )
                );
            });
    }

    /**
     * Get supported OCR languages.
     *
     * @return List of supported languages
     */
    @GetMapping("/languages")
    public Mono<
        ResponseEntity<OcrServiceClient.SupportedLanguagesResponse>
    > getSupportedLanguages() {
        return ocrServiceClient
            .getSupportedLanguages()
            .map(ResponseEntity::ok)
            .onErrorResume(error -> {
                log.error("Failed to get supported languages", error);
                return Mono.error(
                    ApiException.badRequest(
                        "Failed to retrieve supported languages"
                    )
                );
            });
    }

    /**
     * Get OCR service information.
     *
     * @return Information about the OCR service capabilities
     */
    @GetMapping("/info")
    public Mono<ResponseEntity<Map<String, Object>>> getInfo() {
        return Mono.just(
            ResponseEntity.ok(
                Map.of(
                    "service",
                    "OCR Service",
                    "version",
                    "1.0.0",
                    "supportedFormats",
                    List.of("JPEG", "PNG", "PDF", "WebP", "BMP", "TIFF"),
                    "maxFileSize",
                    MAX_FILE_SIZE,
                    "maxFiles",
                    MAX_FILES,
                    "features",
                    List.of(
                        "Text extraction from images",
                        "PDF multi-page support",
                        "Question detection and segmentation",
                        "Multiple choice option extraction",
                        "Metadata extraction (school year, subject, etc.)",
                        "Confidence scores for all extracted data",
                        "Portuguese language optimization",
                        "Angolan exam format support (12ª classe)",
                        "Structured exam extraction (exam_type, cotacao, subitems)",
                        "Automatic entity creation (SchoolYear, Course, Subject, Class)",
                        "Image region detection (cabecalho, questoes, rodape/coordenacao)"
                    ),
                    "endpoints",
                    Map.of(
                        "extract",
                        "POST /api/v1/ocr/extract - Raw OCR extraction",
                        "extractExam",
                        "POST /api/v1/ocr/extract/exam - Structured Angolan exam format",
                        "extractAndPersist",
                        "POST /api/v1/ocr/extract-and-persist - Extract and save to database"
                    )
                )
            )
        );
    }

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
     * Response DTO for extract-and-persist endpoint.
     */
    public record StatementWithRelationsResponse(
        Long statementId,
        String title,
        String examType,
        String variant,
        Integer durationMinutes,
        Double totalMaxScore,
        Double ocrConfidence,
        Boolean needsReview,
        SchoolYearInfo schoolYear,
        EntityInfo course,
        EntityInfo subject,
        ClassInfo classInfo,
        Integer questionCount,
        List<ImageToUploadInfo> imagesToUpload
    ) {}

    /**
     * School year info DTO.
     */
    public record SchoolYearInfo(Long id, Integer startYear, Integer endYear) {}

    /**
     * Generic entity info DTO.
     */
    public record EntityInfo(Long id, String name) {}

    /**
     * Class info DTO.
     */
    public record ClassInfo(Long id, Integer grade, String code) {}

    /**
     * Image to upload info DTO.
     */
    public record ImageToUploadInfo(
        String suggestedFilename,
        String description,
        String region
    ) {}
}
