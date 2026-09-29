package ao.creativemode.kixi.ocr.controller;

import ao.creativemode.kixi.exams.dto.StatementOcrResponse;
import ao.creativemode.kixi.ocr.service.LegacyOcrStatementService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Simple OCR-to-statement creation, under the same base path as
 * StatementController (exams module) but living in ocr so that exams never
 * needs to depend on the OCR client. See LegacyOcrStatementService for why
 * this path exists separately from OcrPersistenceService.
 *
 * Base path: /api/v1/statements
 */
@RestController
@RequestMapping("/api/v1/statements")
public class LegacyOcrStatementController {

    private static final Logger log = LoggerFactory.getLogger(
        LegacyOcrStatementController.class
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

    private static final int MAX_FILES = 10;

    private final LegacyOcrStatementService legacyOcrStatementService;
    private final CurrentAccountService currentAccountService;

    public LegacyOcrStatementController(
        LegacyOcrStatementService legacyOcrStatementService,
        CurrentAccountService currentAccountService
    ) {
        this.legacyOcrStatementService = legacyOcrStatementService;
        this.currentAccountService = currentAccountService;
    }

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
    @PostMapping(
        value = "/ocr/extract",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<StatementOcrResponse>> createFromOcr(
        @RequestPart("files") Flux<FilePart> files,
        UriComponentsBuilder uriBuilder
    ) {
        log.info("OCR statement creation request received");

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
                        return Mono.error(invalidFileTypeException());
                    }
                }

                log.info(
                    "Processing {} file(s) for OCR-based statement creation",
                    fileList.size()
                );

                return legacyOcrStatementService.createFromOcr(fileList, createdBy);
            }))
            .map(result -> {
                URI location = uriBuilder
                    .path("/api/v1/statements/{id}")
                    .buildAndExpand(result.statement().getId())
                    .toUri();

                StatementOcrResponse response = StatementOcrResponse.from(
                    result
                );

                return ResponseEntity.created(location).body(response);
            })
            .doOnSuccess(response ->
                log.info(
                    "Statement created from OCR: id={}",
                    response.getBody() != null ? response.getBody().id() : null
                )
            )
            .doOnError(error -> log.error(
                "OCR statement creation failed: type={}",
                error.getClass().getSimpleName()));
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
    @PostMapping(
        value = "/ocr/extract/single",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<StatementOcrResponse>> createFromOcrSingle(
        @RequestPart("file") FilePart file,
        UriComponentsBuilder uriBuilder
    ) {
        log.info(
            "Single-file OCR statement creation request received: {}",
            safeFilename(file.filename())
        );

        // Validate file type
        if (!isAllowedFileType(file.filename())) {
            return Mono.error(
                invalidFileTypeException()
            );
        }

        return currentAccountService.requiredAccountId()
            .flatMap(createdBy -> legacyOcrStatementService
                .createFromOcr(List.of(file), createdBy))
            .map(result -> {
                URI location = uriBuilder
                    .path("/api/v1/statements/{id}")
                    .buildAndExpand(result.statement().getId())
                    .toUri();

                StatementOcrResponse response = StatementOcrResponse.from(
                    result
                );

                return ResponseEntity.created(location).body(response);
            })
            .doOnSuccess(response ->
                log.info(
                    "Statement created from single-file OCR: id={}",
                    response.getBody() != null ? response.getBody().id() : null
                )
            )
            .doOnError(error -> log.error(
                "Single-file OCR statement creation failed: type={}",
                error.getClass().getSimpleName()));
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

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "<unnamed>";
        }
        String sanitized = filename.replace("\r", "").replace("\n", "");
        return sanitized.substring(0, Math.min(sanitized.length(), 255));
    }
}
