package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.common.exception.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for OCR operations.
 *
 * Provides endpoints for extracting text from images and PDFs
 * using the OCR microservice.
 *
 * Endpoints:
 * - POST /api/v1/ocr/extract - Extract text from uploaded images
 * - GET /api/v1/ocr/health - Check OCR service health
 * - GET /api/v1/ocr/languages - Get supported OCR languages
 */
@RestController
@RequestMapping("/api/v1/ocr")
public class OcrController {

    private static final Logger log = LoggerFactory.getLogger(OcrController.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".pdf", ".webp", ".bmp", ".tiff", ".tif"
    );

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB
    private static final int MAX_FILES = 10;

    private final OcrServiceClient ocrServiceClient;

    public OcrController(OcrServiceClient ocrServiceClient) {
        this.ocrServiceClient = ocrServiceClient;
    }

    /**
     * Extract text from uploaded images/PDFs.
     *
     * @param files List of uploaded file parts (images or PDFs)
     * @return OCR extraction result with structured data
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<OcrResponse>> extractText(
            @RequestPart("files") Flux<FilePart> files) {

        log.info("OCR extraction request received");

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

                    log.info("Processing {} file(s) for OCR extraction", fileList.size());

                    // Call OCR service
                    return ocrServiceClient.extractText(fileList);
                })
                .map(ocrResponse -> {
                    // Return appropriate status based on OCR result
                    if (ocrResponse.isSuccess()) {
                        return ResponseEntity.ok(ocrResponse);
                    } else if (ocrResponse.isPartial()) {
                        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(ocrResponse);
                    } else {
                        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ocrResponse);
                    }
                })
                .doOnSuccess(response -> log.info(
                        "OCR extraction completed: status={}",
                        response.getStatusCode()))
                .doOnError(error -> log.error("OCR extraction failed", error));
    }

    /**
     * Extract text from a single uploaded image/PDF.
     *
     * Simplified endpoint for single-file extraction.
     *
     * @param file Single uploaded file part
     * @return OCR extraction result with structured data
     */
    @PostMapping(value = "/extract/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<OcrResponse>> extractTextSingle(
            @RequestPart("file") FilePart file) {

        log.info("Single-file OCR extraction request received: {}", file.filename());

        // Validate file type
        if (!isAllowedFileType(file.filename())) {
            return Mono.error(ApiException.badRequest(
                    "Invalid file type: " + file.filename() +
                    ". Allowed: " + String.join(", ", ALLOWED_EXTENSIONS)));
        }

        return ocrServiceClient.extractText(List.of(file))
                .map(ocrResponse -> {
                    if (ocrResponse.isSuccess()) {
                        return ResponseEntity.ok(ocrResponse);
                    } else if (ocrResponse.isPartial()) {
                        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(ocrResponse);
                    } else {
                        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ocrResponse);
                    }
                })
                .doOnSuccess(response -> log.info(
                        "Single-file OCR extraction completed: status={}",
                        response.getStatusCode()))
                .doOnError(error -> log.error("Single-file OCR extraction failed", error));
    }

    /**
     * Check OCR service health.
     *
     * @return Health status of the OCR service
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> checkHealth() {
        return ocrServiceClient.healthCheck()
                .map(healthy -> {
                    Map<String, Object> response = Map.of(
                            "service", "ocr-service",
                            "status", healthy ? "healthy" : "unhealthy",
                            "available", healthy
                    );
                    return healthy
                            ? ResponseEntity.ok(response)
                            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                })
                .onErrorResume(error -> {
                    log.error("OCR health check failed", error);
                    Map<String, Object> response = Map.of(
                            "service", "ocr-service",
                            "status", "unavailable",
                            "available", false,
                            "error", error.getMessage()
                    );
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response));
                });
    }

    /**
     * Get supported OCR languages.
     *
     * @return List of supported languages
     */
    @GetMapping("/languages")
    public Mono<ResponseEntity<OcrServiceClient.SupportedLanguagesResponse>> getSupportedLanguages() {
        return ocrServiceClient.getSupportedLanguages()
                .map(ResponseEntity::ok)
                .onErrorResume(error -> {
                    log.error("Failed to get supported languages", error);
                    return Mono.error(ApiException.badRequest("Failed to retrieve supported languages"));
                });
    }

    /**
     * Get OCR service information.
     *
     * @return Information about the OCR service capabilities
     */
    @GetMapping("/info")
    public Mono<ResponseEntity<Map<String, Object>>> getInfo() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "service", "OCR Service",
                "version", "1.0.0",
                "supportedFormats", List.of("JPEG", "PNG", "PDF", "WebP", "BMP", "TIFF"),
                "maxFileSize", MAX_FILE_SIZE,
                "maxFiles", MAX_FILES,
                "features", List.of(
                        "Text extraction from images",
                        "PDF multi-page support",
                        "Question detection and segmentation",
                        "Multiple choice option extraction",
                        "Metadata extraction (school year, subject, etc.)",
                        "Confidence scores for all extracted data",
                        "Portuguese language optimization"
                )
        )));
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
}
