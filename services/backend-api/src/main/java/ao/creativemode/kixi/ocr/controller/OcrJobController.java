package ao.creativemode.kixi.ocr.controller;

import ao.creativemode.kixi.ocr.dto.OcrJobAcceptedResponse;
import ao.creativemode.kixi.ocr.dto.OcrJobStatusResponse;
import ao.creativemode.kixi.ocr.service.OcrJobService;
import ao.creativemode.kixi.ocr.service.OcrJobWorker;
import ao.creativemode.kixi.ocr.service.OcrUploadBuffer;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** HTTP boundary for durable OCR imports. */
@RestController
@RequestMapping("/api/v1/ocr/jobs")
public class OcrJobController {

    private static final int MAX_FILES = 10;

    private final OcrJobService jobs;
    private final OcrJobWorker worker;
    private final CurrentAccountService accounts;
    private final ApiAbuseRateLimiter abuseRateLimiter;

    public OcrJobController(OcrJobService jobs, OcrJobWorker worker,
                            CurrentAccountService accounts, ApiAbuseRateLimiter abuseRateLimiter) {
        this.jobs = jobs;
        this.worker = worker;
        this.accounts = accounts;
        this.abuseRateLimiter = abuseRateLimiter;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<OcrJobAcceptedResponse>> submit(
        @RequestPart("files") Flux<FilePart> files,
        @RequestHeader(name = "X-OCR-Request-ID", required = false) String suppliedRequestId,
        @RequestHeader(name = "X-OCR-Upload-Ms", required = false) String suppliedUploadMs
    ) {
        UUID requestId = parseRequestId(suppliedRequestId);
        int uploadMs = parseUploadMs(suppliedUploadMs);
        return accounts.requiredAccountId().flatMap(accountId ->
            abuseRateLimiter.checkUpload(accountId)
                .then(files.collectList())
                .flatMap(fileList -> {
                    if (fileList.isEmpty()) {
                        return Mono.error(ApiException.badRequest("At least one file is required"));
                    }
                    if (fileList.size() > MAX_FILES) {
                        return Mono.error(ApiException.badRequest("Maximum " + MAX_FILES + " files allowed per request"));
                    }
                    return OcrUploadBuffer.buffer(fileList)
                        .flatMap(uploaded -> jobs.enqueue(accountId, requestId, uploadMs, uploaded));
                })
                .map(jobId -> {
                    String statusUrl = "/api/v1/ocr/jobs/" + jobId;
                    return ResponseEntity.accepted()
                        .location(URI.create(statusUrl))
                        .body(new OcrJobAcceptedResponse(jobId, requestId, "QUEUED", statusUrl));
                })
        );
    }

    @GetMapping("/{jobId}")
    public Mono<OcrJobStatusResponse> status(@PathVariable UUID jobId) {
        return accounts.requiredAccountId().flatMap(accountId -> jobs.status(accountId, jobId));
    }

    @DeleteMapping("/{jobId}")
    public Mono<ResponseEntity<Void>> cancel(@PathVariable UUID jobId) {
        return accounts.requiredAccountId().flatMap(accountId -> jobs.cancel(accountId, jobId)
            .then(Mono.fromRunnable(() -> worker.cancel(jobId)))
            .thenReturn(ResponseEntity.noContent().build()));
    }

    private UUID parseRequestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID();
        try {
            UUID requestId = UUID.fromString(value);
            if (!requestId.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return requestId;
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest("X-OCR-Request-ID must be a UUID");
        }
    }

    private int parseUploadMs(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 180_000) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw ApiException.badRequest("X-OCR-Upload-Ms must be between 0 and 180000");
        }
    }
}
