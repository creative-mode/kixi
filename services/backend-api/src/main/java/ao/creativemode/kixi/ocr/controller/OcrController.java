package ao.creativemode.kixi.ocr.controller;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.ocr.dto.ExamExtractionResponse;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.ocr.service.OcrImageAssociationService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService.StatementWithRelations;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private static final long MAX_PERSIST_SOURCE_SIZE = 50 * 1024 * 1024;
    private static final int MAX_STREAM_RESULT_SIZE = 50 * 1024 * 1024;
    private static final int MAX_FILES = 10;

    private final OcrServiceClient ocrServiceClient;
    private final OcrPersistenceService ocrPersistenceService;
    private final CurrentAccountService currentAccountService;
    private final ApiAbuseRateLimiter abuseRateLimiter;
    private final OcrImageAssociationService imageAssociationService;
    private final ObjectMapper objectMapper;

    public OcrController(
        OcrServiceClient ocrServiceClient,
        OcrPersistenceService ocrPersistenceService,
        CurrentAccountService currentAccountService,
        ApiAbuseRateLimiter abuseRateLimiter,
        OcrImageAssociationService imageAssociationService,
        ObjectMapper objectMapper
    ) {
        this.ocrServiceClient = ocrServiceClient;
        this.ocrPersistenceService = ocrPersistenceService;
        this.currentAccountService = currentAccountService;
        this.abuseRateLimiter = abuseRateLimiter;
        this.imageAssociationService = imageAssociationService;
        this.objectMapper = objectMapper;
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

        return currentAccountService.requiredAccountId()
            .flatMap(abuseRateLimiter::checkUpload)
            .then(files.collectList())
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
                        return Mono.error(invalidFileTypeException());
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
            .doOnError(error -> log.error(
                "OCR extraction failed: type={}", error.getClass().getSimpleName()));
    }

    /** Stream page-completion events for the Manager importer. The stream is request-scoped. */
    @PostMapping(
        value = "/extract/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> extractTextStream(
        @RequestPart("files") Flux<FilePart> files,
        @RequestHeader(name = "X-OCR-Upload-Ms", required = false) Double frontendUploadMs
    ) {
        return currentAccountService.requiredAccountId()
            .flatMap(abuseRateLimiter::checkUpload)
            .then(files.collectList())
            .flatMap(fileList -> {
                if (fileList.isEmpty()) {
                    return Mono.error(ApiException.badRequest("At least one file is required"));
                }
                if (fileList.size() > MAX_FILES) {
                    return Mono.error(ApiException.badRequest("Maximum " + MAX_FILES + " files allowed per request"));
                }

                long declaredTotal = 0;
                for (FilePart file : fileList) {
                    if (!isAllowedFileType(file.filename())) {
                        return Mono.error(invalidFileTypeException());
                    }
                    long size = file.headers().getContentLength();
                    if (size > MAX_FILE_SIZE) {
                        return Mono.error(new ApiException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "Each OCR file must be at most 20 MB"));
                    }
                    if (size > 0) declaredTotal += size;
                    if (declaredTotal > MAX_PERSIST_SOURCE_SIZE) {
                        return Mono.error(new ApiException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "Total OCR upload must be at most 50 MB"));
                    }
                }

                return bufferFilesForPersistence(fileList).flatMap(uploaded ->
                    ocrServiceClient.extractTextStreamFromUploadedFiles(uploaded, frontendUploadMs)
                        .map(upstream -> ResponseEntity.status(upstream.getStatusCode())
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .header("Cache-Control", "no-cache, no-transform")
                            .header("X-Accel-Buffering", "no")
                            .body(upstream.getBody().concatMap(event -> Mono.fromCallable(
                                    () -> normalizeResultEvent(event, uploaded))
                                .subscribeOn(Schedulers.boundedElastic())))));
            })
            .doOnError(error -> log.error(
                "OCR stream setup failed: type={}", error.getClass().getSimpleName()));
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
        log.info("Single-file OCR extraction request received");

        // Validate file type
        if (!isAllowedFileType(file.filename())) {
            return Mono.error(
                invalidFileTypeException()
            );
        }

        return currentAccountService.requiredAccountId()
            .flatMap(abuseRateLimiter::checkUpload)
            .then(ocrServiceClient.extractText(List.of(file)))
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
                log.error("Single-file OCR extraction failed: type={}",
                    error.getClass().getSimpleName())
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

        return currentAccountService.requiredAccountId()
            .flatMap(abuseRateLimiter::checkUpload)
            .then(files.collectList())
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
                        return Mono.error(invalidFileTypeException());
                    }
                }

                log.info(
                    "Processing {} file(s) for Angolan exam extraction",
                    fileList.size()
                );

                // Keep the source bytes: the figures the OCR finds are cropped from them for the builder.
                return bufferFilesForPersistence(fileList).flatMap(uploaded ->
                    ocrServiceClient.extractTextFromUploadedFiles(uploaded)
                        .map(ocrResponse -> new ExtractedExam(ocrResponse, uploaded)));
            })
            .map(extracted -> {
                OcrResponse ocrResponse = extracted.response();
                // Convert to structured exam format, with each figure next to its question
                ExamExtractionResponse examResponse = ExamExtractionResponse.fromOcrResponse(
                    ocrResponse,
                    imageAssociationService.previewCrops(ocrResponse.imagesToUpload(), extracted.files()));

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
                log.error("Angolan exam extraction failed: type={}",
                    error.getClass().getSimpleName())
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
            .flatMap(createdBy -> abuseRateLimiter.checkUpload(createdBy)
                .then(files.collectList()).flatMap(fileList -> {
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
                        return Mono.error(invalidFileTypeException());
                    }
                }

                log.info(
                    "Processing {} file(s) for OCR extraction and persistence",
                    fileList.size()
                );

                // Retain the exact source bytes for OCR-region association.
                return bufferFilesForPersistence(fileList)
                    .flatMap(uploadedFiles -> ocrPersistenceService.processAndPersist(
                        uploadedFiles,
                        createdBy
                    ));
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
                                          img.region(),
                                          img.pageIndex(),
                                          img.bbox(),
                                          img.sourceWidth(),
                                          img.sourceHeight(),
                                          img.sourceFileIndex(),
                                          img.contractVersion()
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
                log.error("OCR extraction and persistence failed: type={}",
                    error.getClass().getSimpleName())
            );
    }

    private record ExtractedExam(OcrResponse response, List<OcrUploadedFile> files) {}

    private ServerSentEvent<String> normalizeResultEvent(
            ServerSentEvent<String> event, List<OcrUploadedFile> sourceFiles) {
        if (event == null || !"result".equals(event.event()) || event.data() == null) {
            return event;
        }

        try {
            OcrResponse response = objectMapper.readValue(event.data(), OcrResponse.class);
            ExamExtractionResponse extracted = ExamExtractionResponse.fromOcrResponse(
                response,
                imageAssociationService.previewCrops(response.imagesToUpload(), sourceFiles));
            byte[] serialized = objectMapper.writeValueAsBytes(extracted);
            if (serialized.length > MAX_STREAM_RESULT_SIZE) {
                throw new IllegalStateException("OCR stream result exceeds the 50 MB limit");
            }
            String data = new String(serialized, StandardCharsets.UTF_8);
            return ServerSentEvent.<String>builder(data)
                .id(event.id())
                .event(event.event())
                .retry(event.retry())
                .comment(event.comment())
                .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not normalize OCR stream result", error);
        }
    }

    private Mono<List<OcrUploadedFile>> bufferFilesForPersistence(List<FilePart> files) {
        long[] bufferedBytes = { 0 };
        return Flux.fromIterable(files)
            .concatMap(file -> {
                long remaining = MAX_PERSIST_SOURCE_SIZE - bufferedBytes[0];
                if (remaining <= 0) {
                    return Mono.error(new ApiException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "Total OCR upload must be at most 50 MB"));
                }
                int maxFileBytes = (int) Math.min(MAX_FILE_SIZE, remaining);
                return DataBufferUtils.join(file.content(), maxFileBytes)
                .map(buffer -> {
                    try {
                        int readableBytes = buffer.readableByteCount();
                        if (readableBytes > maxFileBytes) {
                            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Total OCR upload must be at most 50 MB");
                        }
                        byte[] content = new byte[readableBytes];
                        buffer.read(content);
                        bufferedBytes[0] += readableBytes;
                        return new OcrUploadedFile(
                            file.filename(),
                            file.headers().getContentType(),
                            content
                        );
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .switchIfEmpty(Mono.error(ApiException.badRequest("OCR files cannot be empty")));
            })
            .collectList()
            .onErrorMap(DataBufferLimitException.class,
                error -> new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "OCR upload exceeds the per-file or aggregate size limit"));
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
                log.error("OCR health check failed: type={}",
                    error.getClass().getSimpleName());
                Map<String, Object> response = Map.of(
                    "service",
                    "ocr-service",
                    "status",
                    "unavailable",
                    "available",
                    false
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
                log.error("Failed to get supported languages: type={}",
                    error.getClass().getSimpleName());
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

    private static ApiException invalidFileTypeException() {
        return ApiException.badRequest(
            "Invalid file type. Allowed: " + String.join(", ", ALLOWED_EXTENSIONS));
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
        String region,
        Integer pageIndex,
        List<Integer> bbox,
        Integer sourceWidth,
        Integer sourceHeight,
        Integer sourceFileIndex,
        Integer contractVersion
    ) {}
}
