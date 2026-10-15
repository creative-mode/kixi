package ao.creativemode.kixi.donations.controller;

import ao.creativemode.kixi.donations.config.DonationProperties;
import ao.creativemode.kixi.donations.dto.DonationCreatedResponse;
import ao.creativemode.kixi.donations.dto.DonationDetail;
import ao.creativemode.kixi.donations.dto.DonationLinkRequest;
import ao.creativemode.kixi.donations.dto.DonationPage;
import ao.creativemode.kixi.donations.dto.DonationReviewRequest;
import ao.creativemode.kixi.donations.dto.DonationSubmission;
import ao.creativemode.kixi.donations.service.DonationRateLimiter;
import ao.creativemode.kixi.donations.service.DonationService;
import ao.creativemode.kixi.ocr.dto.ExamExtractionResponse;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.ClientAddress;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Donated statements (issue #252).
 *
 * <p>{@code POST /api/v1/donations} is public, so it is the only route in this class that is written
 * against its caller: the body is held to its size before a byte is read, the budget is spent before
 * the multipart is parsed, and nothing in it is believed until the service has looked at the bytes.
 * Everything else is ADMIN, enforced in {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/v1/donations")
public class DonationController {

    /** Room for the text fields and the multipart framing on top of the files. */
    private static final long ENVELOPE_BYTES = 256L * 1024;

    private final DonationService service;
    private final DonationRateLimiter rateLimiter;
    private final DonationProperties properties;
    private final TrustedProxyProperties trustedProxies;
    private final CurrentAccountService currentAccount;

    public DonationController(
            DonationService service,
            DonationRateLimiter rateLimiter,
            DonationProperties properties,
            TrustedProxyProperties trustedProxies,
            CurrentAccountService currentAccount) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.trustedProxies = trustedProxies;
        this.currentAccount = currentAccount;
    }

    // ── Public ──────────────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<DonationCreatedResponse>> donate(ServerWebExchange exchange) {
        // First: how big is what is about to be read. A body without a length is refused outright:
        // there would be no way to tell a donation from a stream that never ends.
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared < 0) {
            return Mono.error(new ApiException(HttpStatus.LENGTH_REQUIRED, "Length Required",
                    "The request must declare its length", "DONATION_LENGTH_REQUIRED"));
        }
        if (declared > properties.getMaxTotalBytes() + ENVELOPE_BYTES) {
            return Mono.error(new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                    "The donation is too large", "DONATION_TOO_LARGE"));
        }
        String clientHash = service.clientHash(ClientAddress.resolve(exchange, trustedProxies));
        // Second: the budget, before the multipart is parsed. A refused call then costs a row of
        // counters, not a body parsed to disk.
        return rateLimiter.checkSubmission(clientHash)
                .then(Mono.defer(exchange::getMultipartData))
                .flatMap(this::toSubmission)
                .flatMap(submission -> service.submit(submission, clientHash))
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    private Mono<DonationSubmission> toSubmission(MultiValueMap<String, Part> parts) {
        List<FilePart> fileParts = new ArrayList<>();
        for (Part part : parts.getOrDefault("files", List.of())) {
            if (part instanceof FilePart file) {
                fileParts.add(file);
            }
        }
        if (fileParts.size() > properties.getMaxFiles()) {
            return Mono.error(ApiException.badRequest("At most " + properties.getMaxFiles() + " files per donation"));
        }
        return Flux.fromIterable(fileParts)
                .concatMap(this::readFile)
                .collectList()
                .map(files -> new DonationSubmission(
                        field(parts, "donorName"), field(parts, "donorEmail"), field(parts, "institutionName"),
                        field(parts, "subjectName"), field(parts, "classGrade"), field(parts, "schoolYear"),
                        field(parts, "examType"), field(parts, "notes"),
                        "true".equalsIgnoreCase(field(parts, "consent")),
                        field(parts, "website"), startedAt(parts), files));
    }

    /** A file read into memory, never past its own limit: the join gives up at the cap. */
    private Mono<DonationSubmission.Upload> readFile(FilePart file) {
        return DataBufferUtils.join(file.content(), (int) properties.getMaxFileBytes())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new DonationSubmission.Upload(file.filename(), bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorMap(DataBufferLimitException.class, error -> new ApiException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                        "A file exceeds " + properties.getMaxFileBytes() / (1024 * 1024) + " MB", "DONATION_TOO_LARGE"));
    }

    private static String field(MultiValueMap<String, Part> parts, String name) {
        Part part = parts.getFirst(name);
        return part instanceof FormFieldPart form ? form.value() : null;
    }

    private static Long startedAt(MultiValueMap<String, Part> parts) {
        String raw = field(parts, "formStartedAt");
        try {
            return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── Administrator ───────────────────────────────────────────────────────

    @GetMapping
    public Mono<ResponseEntity<DonationPage>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(status, page, limit).map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<DonationDetail>> get(@PathVariable Long id) {
        return service.get(id).map(ResponseEntity::ok);
    }

    /**
     * The file as it is, to be looked at and never run: the type is the one the bytes were recognised
     * as when it arrived, it is an attachment, and the browser is told not to guess another one.
     */
    @GetMapping("/{id}/files/{fileId}")
    public Mono<ResponseEntity<byte[]>> file(@PathVariable Long id, @PathVariable Long fileId) {
        return service.file(id, fileId).map(stored -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(stored.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(stored.content()));
    }

    @PatchMapping("/{id}/review")
    public Mono<ResponseEntity<DonationDetail>> review(
            @PathVariable Long id, @Valid @RequestBody DonationReviewRequest request) {
        return currentAccount.requiredAccountId()
                .flatMap(accountId -> service.review(id, accountId, request))
                .map(ResponseEntity::ok);
    }

    /**
     * The OCR over the donation's files, as {@code /ocr/extract/exam} would answer for the same
     * pages. Nothing is saved: the administrator reads it in the exam builder and decides.
     */
    @PostMapping("/{id}/extract")
    public Mono<ResponseEntity<ExamExtractionResponse>> extract(@PathVariable Long id) {
        return service.extract(id).map(ocrResponse -> {
            ExamExtractionResponse body = ExamExtractionResponse.fromOcrResponse(ocrResponse);
            if (ocrResponse.isSuccess()) {
                return ResponseEntity.ok(body);
            }
            return ocrResponse.isPartial()
                    ? ResponseEntity.status(HttpStatus.MULTI_STATUS).body(body)
                    : ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        });
    }

    @PostMapping("/{id}/link-statement")
    public Mono<ResponseEntity<DonationDetail>> linkStatement(
            @PathVariable Long id, @Valid @RequestBody DonationLinkRequest request) {
        return currentAccount.requiredAccountId()
                .flatMap(accountId -> service.linkStatement(id, accountId, request.statementId()))
                .map(ResponseEntity::ok);
    }
}
