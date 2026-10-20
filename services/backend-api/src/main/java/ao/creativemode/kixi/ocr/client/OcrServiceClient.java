package ao.creativemode.kixi.ocr.client;

import ao.creativemode.kixi.ocr.dto.OcrResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WebClient-based client for the OCR microservice.
 *
 * This client handles all communication with the OCR service for
 * extracting text from images and PDFs.
 *
 * Features:
 * - Reactive non-blocking HTTP calls
 * - Automatic retry with exponential backoff
 * - Timeout configuration
 * - Error handling and mapping
 */
@Component
public class OcrServiceClient {

    private static final Logger log = LoggerFactory.getLogger(OcrServiceClient.class);

    /** The OCR service answers a degraded result with its own {@code "status": "error"} in the body. */
    private static final Pattern CONTENT_ERROR = Pattern.compile("\"status\"\\s*:\\s*\"error\"");

    private final WebClient webClient;
    private final Duration timeout;
    private final int maxRetries;
    private final String apiKey;

    /**
     * Construct the OCR service client.
     *
     * @param ocrServiceUrl   Base URL of the OCR service (e.g., http://ocr-service:8000)
     * @param timeoutMs       Request timeout in milliseconds
     * @param maxRetries      Maximum number of retry attempts
     */
    public OcrServiceClient(
            @Value("${ocr.service.url:http://localhost:8000}") String ocrServiceUrl,
            @Value("${ocr.service.timeout-ms:120000}") long timeoutMs,
            @Value("${ocr.service.max-retries:2}") int maxRetries) {
        this(ocrServiceUrl, timeoutMs, maxRetries, "");
    }

    @Autowired
    public OcrServiceClient(
            @Value("${ocr.service.url:http://localhost:8000}") String ocrServiceUrl,
            @Value("${ocr.service.timeout-ms:120000}") long timeoutMs,
            @Value("${ocr.service.max-retries:2}") int maxRetries,
            @Value("${ocr.service.api-key:}") String apiKey) {

        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxRetries = maxRetries;
        this.apiKey = apiKey == null ? "" : apiKey.trim();

        this.webClient = WebClient.builder()
                .baseUrl(ocrServiceUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(50 * 1024 * 1024)) // 50MB for large images
                .build();

        log.info("OCR Service Client initialized: url={}, timeout={}ms, maxRetries={}",
                ocrServiceUrl, timeoutMs, maxRetries);
    }

    /**
     * Extract text from uploaded file parts.
     *
     * @param files List of uploaded file parts (images or PDFs)
     * @return Mono containing the OCR response
     */
    public Mono<OcrResponse> extractText(List<FilePart> files) {
        if (files == null || files.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one file is required"));
        }

        log.info("Sending OCR request: {} file(s)", files.size());

        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        for (FilePart file : files) {
            builder.asyncPart("images", file.content(), DataBuffer.class)
                    .filename(file.filename())
                    .contentType(getContentType(file.filename()));
        }

        return webClient.post()
                .uri("/ocr/v1/extract")
                .headers(this::applyAuthentication)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrClientException(
                                        "OCR request failed: " + body,
                                        response.statusCode().value()))))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrServerException(
                                        "OCR service error: " + body,
                                        response.statusCode().value(),
                                        isContentError(body)))))
                .bodyToMono(OcrResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> log.warn(
                                "Retrying OCR request, attempt {}: type={}",
                                signal.totalRetries() + 1,
                                signal.failure().getClass().getSimpleName())))
                .doOnSuccess(response -> log.info(
                        "OCR request successful: requestId={}, status={}, confidence={}",
                        response.requestId(),
                        response.status(),
                        response.overallConfidence()))
                .doOnError(error -> log.error("OCR request failed: type={}", error.getClass().getSimpleName()));
    }

    /** Stream measured page progress and the final result without retrying a consumed upload. */
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> extractTextStream(List<FilePart> files) {
        return extractTextStream(files, null);
    }

    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> extractTextStream(
            List<FilePart> files, Double frontendUploadMs) {
        if (files == null || files.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one file is required"));
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (FilePart file : files) {
            builder.asyncPart("images", file.content(), DataBuffer.class)
                    .filename(file.filename())
                    .contentType(getContentType(file.filename()));
        }

        return stream(builder, frontendUploadMs);
    }

    /** Stream OCR events from already bounded source bytes retained for image previews. */
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> extractTextStreamFromUploadedFiles(
            List<OcrUploadedFile> files, Double frontendUploadMs) {
        if (files == null || files.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one file is required"));
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (OcrUploadedFile file : files) {
            builder.part("images", file.content())
                    .filename(file.filename())
                    .contentType(file.contentType() != null
                            ? file.contentType()
                            : getContentType(file.filename()));
        }

        return stream(builder, frontendUploadMs);
    }

    private Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(
            MultipartBodyBuilder builder, Double frontendUploadMs) {
        return webClient.post()
                .uri("/ocr/v1/extract/stream")
                .headers(headers -> {
                    applyAuthentication(headers);
                    if (frontendUploadMs != null && Double.isFinite(frontendUploadMs)
                            && frontendUploadMs >= 0 && frontendUploadMs <= 180_000) {
                        headers.set("X-OCR-Upload-Ms", frontendUploadMs.toString());
                    }
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new OcrClientException(
                                "OCR stream request failed: " + body, response.statusCode().value()))))
                .toEntityFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(entity -> ResponseEntity.status(entity.getStatusCode())
                        .headers(entity.getHeaders())
                        .body(entity.getBody().timeout(timeout)))
                .doOnError(error -> log.error("OCR stream failed: type={}", error.getClass().getSimpleName()));
    }

    /**
     * Extract text from bytes retained by the persistence flow. This variant
     * avoids replaying a consumed multipart stream when OCR regions are later
     * associated with their source page.
     */
    public Mono<OcrResponse> extractTextFromUploadedFiles(List<OcrUploadedFile> files) {
        if (files == null || files.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one file is required"));
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (OcrUploadedFile file : files) {
            builder.part("images", file.content())
                    .filename(file.filename())
                    .contentType(file.contentType() != null
                            ? file.contentType()
                            : getContentType(file.filename()));
        }

        log.info("Sending retained OCR request: {} file(s)", files.size());
        return webClient.post()
                .uri("/ocr/v1/extract")
                .headers(this::applyAuthentication)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrClientException(
                                        "OCR request failed: " + body,
                                        response.statusCode().value()))))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrServerException(
                                        "OCR service error: " + body,
                                        response.statusCode().value(),
                                        isContentError(body)))))
                .bodyToMono(OcrResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                        .filter(this::isRetryable))
                .doOnSuccess(response -> log.info(
                        "OCR retained request successful: requestId={}, status={}",
                        response.requestId(), response.status()))
                .doOnError(error -> log.error("OCR retained request failed: type={}",
                        error.getClass().getSimpleName()));
    }

    /**
     * Extract text from raw image bytes.
     *
     * @param imageBytes Raw image bytes
     * @param filename   Original filename for content type detection
     * @return Mono containing the OCR response
     */
    public Mono<OcrResponse> extractTextFromBytes(byte[] imageBytes, String filename) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.error(new IllegalArgumentException("Image bytes cannot be empty"));
        }

        log.info("Sending OCR request for single image ({} bytes)", imageBytes.length);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("images", imageBytes)
                .filename(filename)
                .contentType(getContentType(filename));

        return webClient.post()
                .uri("/ocr/v1/extract")
                .headers(this::applyAuthentication)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrClientException(
                                        "OCR request failed: " + body,
                                        response.statusCode().value()))))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new OcrServerException(
                                        "OCR service error: " + body,
                                        response.statusCode().value(),
                                        isContentError(body)))))
                .bodyToMono(OcrResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                        .filter(this::isRetryable))
                .doOnSuccess(response -> log.info(
                        "OCR request successful: requestId={}, status={}",
                        response.requestId(),
                        response.status()))
                .doOnError(error -> log.error("OCR request failed: type={}", error.getClass().getSimpleName()));
    }

    private void applyAuthentication(HttpHeaders headers) {
        if (!apiKey.isBlank()) {
            headers.set("X-OCR-API-Key", apiKey);
        }
    }

    /**
     * Simple health check for the OCR service.
     *
     * @return Mono containing true if the service is healthy
     */
    public Mono<Boolean> healthCheck() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> true)
                .timeout(Duration.ofSeconds(10))
                .onErrorReturn(false)
                .doOnNext(healthy -> log.debug("OCR service health check: {}", healthy ? "OK" : "FAILED"));
    }

    /**
     * Get supported languages from the OCR service.
     *
     * @return Mono containing the languages response
     */
    public Mono<SupportedLanguagesResponse> getSupportedLanguages() {
        return webClient.get()
                .uri("/ocr/v1/supported-languages")
                .retrieve()
                .bodyToMono(SupportedLanguagesResponse.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Determine if an exception is retryable.
     */
    private boolean isRetryable(Throwable throwable) {
        // Retry on network errors and 5xx server errors
        if (throwable instanceof WebClientRequestException) {
            return true;
        }
        if (throwable instanceof OcrServerException serverError) {
            // A 5xx that carries the service's own content error (it read nothing) does not
            // change on a retry: repeating it only costs another full OCR pass.
            return !serverError.isContentError();
        }
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        return false;
    }

    /**
     * Whether a 5xx body is the OCR service's own degraded result ({@code "status": "error"},
     * no text read) rather than an infrastructure failure.
     */
    private static boolean isContentError(String body) {
        return body != null && CONTENT_ERROR.matcher(body).find();
    }

    /**
     * Get content type based on file extension.
     */
    private MediaType getContentType(String filename) {
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        String lowerFilename = filename.toLowerCase();

        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        } else if (lowerFilename.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        } else if (lowerFilename.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        } else if (lowerFilename.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        } else if (lowerFilename.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        } else if (lowerFilename.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        } else if (lowerFilename.endsWith(".tiff") || lowerFilename.endsWith(".tif")) {
            return MediaType.parseMediaType("image/tiff");
        }

        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Response for supported languages endpoint.
     */
    public record SupportedLanguagesResponse(
            List<LanguageInfo> languages,
            String defaultLanguage
    ) {
        public record LanguageInfo(
                String code,
                String name,
                boolean primary
        ) {}
    }

    /**
     * Exception for client-side (4xx) errors from the OCR service.
     */
    public static class OcrClientException extends RuntimeException {
        private final int statusCode;

        public OcrClientException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * Exception for server-side (5xx) errors from the OCR service.
     */
    public static class OcrServerException extends RuntimeException {
        private final int statusCode;
        private final boolean contentError;

        public OcrServerException(String message, int statusCode) {
            this(message, statusCode, false);
        }

        public OcrServerException(String message, int statusCode, boolean contentError) {
            super(message);
            this.statusCode = statusCode;
            this.contentError = contentError;
        }

        public int getStatusCode() {
            return statusCode;
        }

        /** True when the body is the OCR service's own result and repeating it would not help. */
        public boolean isContentError() {
            return contentError;
        }
    }
}
