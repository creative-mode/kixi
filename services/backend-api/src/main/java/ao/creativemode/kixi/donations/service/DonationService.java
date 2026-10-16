package ao.creativemode.kixi.donations.service;

import ao.creativemode.kixi.donations.config.DonationProperties;
import ao.creativemode.kixi.donations.dto.DonationCreatedResponse;
import ao.creativemode.kixi.donations.dto.DonationDetail;
import ao.creativemode.kixi.donations.dto.DonationPage;
import ao.creativemode.kixi.donations.dto.DonationReviewRequest;
import ao.creativemode.kixi.donations.dto.DonationSubmission;
import ao.creativemode.kixi.donations.dto.DonationSummary;
import ao.creativemode.kixi.donations.model.Donation;
import ao.creativemode.kixi.donations.model.DonationFile;
import ao.creativemode.kixi.donations.repository.DonationFileRepository;
import ao.creativemode.kixi.donations.repository.DonationRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import ao.creativemode.kixi.shared.storage.UploadSniffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Donated statements (issue #252): the public submission and the administrator's review.
 *
 * <p>The public side is an endpoint anyone can call, so it is written against the caller: every
 * field is bounded, nothing the client says about a file is believed, a bot gets a convincing answer
 * and no row, and the caller's address is only ever kept as a peppered hash.</p>
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    static final Set<String> EXAM_TYPES = Set.of("Teste", "Prova", "Exame", "Trabalho", "Outro");
    static final Set<String> REVIEW_DECISIONS = Set.of(Donation.APPROVED, Donation.REJECTED);
    private static final Set<String> STATUSES = Set.of(
            Donation.PENDING, Donation.APPROVED, Donation.REJECTED, Donation.CONVERTED);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]{2,}$");
    private static final Pattern SCHOOL_YEAR = Pattern.compile("^\\d{4}(\\s*[/-]\\s*\\d{4})?$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    /** No 0/O, 1/I/L or U: a code read aloud or copied by hand does not get mistaken. */
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DonationRepository donations;
    private final DonationFileRepository files;
    private final ImageStorage storage;
    private final StatementRepository statements;
    private final OcrServiceClient ocr;
    private final DonationProperties properties;
    private final Clock clock;
    private final TransactionalOperator tx;
    private final String pepper;

    public DonationService(
            DonationRepository donations,
            DonationFileRepository files,
            ImageStorage storage,
            StatementRepository statements,
            OcrServiceClient ocr,
            DonationProperties properties,
            Clock clock,
            TransactionalOperator tx,
            @org.springframework.beans.factory.annotation.Value("${app.jwt.secret:}") String jwtSecret) {
        this.donations = donations;
        this.files = files;
        this.storage = storage;
        this.statements = statements;
        this.ocr = ocr;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        // Stable across restarts and never sent anywhere: the hash of an address only has to be
        // unreadable, and it is not a secret the operator has to remember.
        this.pepper = properties.getPepper().isBlank() ? "donations:" + jwtSecret : properties.getPepper();
    }

    // ── Public submission ───────────────────────────────────────────────────

    /** The SHA-256 of the caller's address with the pepper: what is kept, never the address. */
    public String clientHash(String clientAddress) {
        return sha256(pepper + "|" + clientAddress);
    }

    public Mono<DonationCreatedResponse> submit(DonationSubmission submission, String clientHash) {
        return Mono.defer(() -> {
            // A script is answered before any validation, so it cannot learn the rules by probing them.
            if (looksLikeABot(submission)) {
                log.info("Donation taken for a bot and dropped");
                return Mono.just(new DonationCreatedResponse(newReference()));
            }
            Fields fields = validatedFields(submission);
            List<Prepared> prepared = validatedFiles(submission.files());
            return store(prepared)
                    .flatMap(keys -> tx.transactional(save(fields, prepared, keys, clientHash))
                            .onErrorResume(error -> removeStored(keys).then(Mono.error(error))))
                    .map(donation -> new DonationCreatedResponse(donation.getReference()));
        });
    }

    boolean looksLikeABot(DonationSubmission submission) {
        if (submission.website() != null && !submission.website().isBlank()) {
            return true;
        }
        Long startedAt = submission.formStartedAt();
        if (startedAt == null) {
            return true;
        }
        long elapsed = clock.millis() - startedAt;
        // Faster than a person can read the page, or from the future: a clock nobody has.
        return elapsed < properties.getMinFillSeconds() * 1000L || elapsed < 0;
    }

    private record Fields(String donorName, String donorEmail, String institutionName, String subjectName,
                          String classGrade, String schoolYear, String examType, String notes) { }

    private Fields validatedFields(DonationSubmission s) {
        if (!s.consent()) {
            throw ApiException.badRequest("Consent is required to donate a statement");
        }
        String examType = required(s.examType(), "examType", 40);
        if (!EXAM_TYPES.contains(examType)) {
            throw ApiException.badRequest("examType must be one of " + String.join(", ", EXAM_TYPES.stream().sorted().toList()));
        }
        String email = optional(s.donorEmail(), "donorEmail", 150);
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("donorEmail is not a valid e-mail address");
        }
        String schoolYear = optional(s.schoolYear(), "schoolYear", 20);
        if (schoolYear != null && !SCHOOL_YEAR.matcher(schoolYear).matches()) {
            throw ApiException.badRequest("schoolYear must look like 2024 or 2024/2025");
        }
        return new Fields(
                optional(s.donorName(), "donorName", 100),
                email,
                required(s.institutionName(), "institutionName", 150),
                required(s.subjectName(), "subjectName", 100),
                optional(s.classGrade(), "classGrade", 30),
                schoolYear,
                examType,
                optional(s.notes(), "notes", 1000));
    }

    /** Trimmed, control characters gone, no markup, and within its length. Null when it ends up empty. */
    static String optional(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String clean = CONTROL.matcher(value).replaceAll("").replaceAll("[ \\t]+", " ").trim();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.indexOf('<') >= 0 || clean.indexOf('>') >= 0) {
            throw ApiException.badRequest(field + " must not contain markup");
        }
        if (clean.length() > max) {
            throw ApiException.badRequest(field + " must not exceed " + max + " characters");
        }
        return clean;
    }

    static String required(String value, String field, int max) {
        String clean = optional(value, field, max);
        if (clean == null || clean.length() < 2) {
            throw ApiException.badRequest(field + " is required");
        }
        return clean;
    }

    private record Prepared(String originalName, byte[] content, UploadSniffer.Kind kind, String sha256, Integer pages) { }

    /** Count, size, real type and safety of every file, and the exact duplicates dropped. */
    private List<Prepared> validatedFiles(List<DonationSubmission.Upload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw ApiException.badRequest("At least one file is required");
        }
        if (uploads.size() > properties.getMaxFiles()) {
            throw ApiException.badRequest("At most " + properties.getMaxFiles() + " files per donation");
        }
        long total = 0;
        Map<String, Prepared> unique = new LinkedHashMap<>();
        for (DonationSubmission.Upload upload : uploads) {
            byte[] content = upload.content();
            if (content == null || content.length == 0) {
                throw ApiException.badRequest("A file is empty");
            }
            if (content.length > properties.getMaxFileBytes()) {
                throw payloadTooLarge("A file exceeds " + properties.getMaxFileBytes() / (1024 * 1024) + " MB");
            }
            total += content.length;
            if (total > properties.getMaxTotalBytes()) {
                throw payloadTooLarge("The files exceed " + properties.getMaxTotalBytes() / (1024 * 1024) + " MB in total");
            }
            UploadSniffer.Kind kind = UploadSniffer.detect(content).orElseThrow(() -> new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                    "Only JPG, PNG, WEBP and PDF files are accepted", "DONATION_UNSUPPORTED_TYPE"));
            Integer pages = UploadSniffer.requireSafe(kind, content,
                    properties.getMaxImagePixels(), properties.getMaxPdfPages());
            String sha = sha256(content);
            unique.putIfAbsent(sha, new Prepared(safeName(upload.filename(), kind), content, kind, sha, pages));
        }
        return new ArrayList<>(unique.values());
    }

    private static ApiException payloadTooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large", message, "DONATION_TOO_LARGE");
    }

    /** What the donor called the file, cleaned for display. It is never used as a path. */
    static String safeName(String name, UploadSniffer.Kind kind) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = CONTROL.matcher(base).replaceAll("").replaceAll("[<>\"|?*:]", "_").trim();
        if (base.isEmpty()) {
            base = "ficheiro." + kind.extension();
        }
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private Mono<List<String>> store(List<Prepared> prepared) {
        // One at a time, so a failure leaves a known list to clean up, not whatever was in flight.
        return Flux.fromIterable(prepared)
                .concatMap(file -> {
                    String key = "donations/" + UUID.randomUUID();
                    return storage.put(key, MediaType.parseMediaType(file.kind().mimeType()), file.content())
                            .thenReturn(key);
                })
                .collectList();
    }

    private Mono<Void> removeStored(List<String> keys) {
        return Flux.fromIterable(keys)
                .concatMap(key -> storage.delete(key).onErrorResume(error -> {
                    log.warn("Could not remove an orphan donation file {}", key);
                    return Mono.empty();
                }))
                .then();
    }

    /** The donation and its files stand or fall together: the caller wraps it in a transaction. */
    Mono<Donation> save(Fields f, List<Prepared> prepared, List<String> keys, String clientHash) {
        LocalDateTime now = LocalDateTime.now(clock);
        return uniqueReference().flatMap(reference -> {
            Donation donation = Donation.builder()
                    .reference(reference)
                    .donorName(f.donorName())
                    .donorEmail(f.donorEmail())
                    .institutionName(f.institutionName())
                    .subjectName(f.subjectName())
                    .classGrade(f.classGrade())
                    .schoolYear(f.schoolYear())
                    .examType(f.examType())
                    .notes(f.notes())
                    .consentAt(now)
                    .consentVersion(properties.getConsentVersion())
                    .status(Donation.PENDING)
                    .clientHash(clientHash)
                    .createdAt(now)
                    .build();
            return donations.save(donation);
        }).flatMap(saved -> Flux.range(0, prepared.size())
                .concatMap(i -> files.save(DonationFile.builder()
                        .donationId(saved.getId())
                        .storageKey(keys.get(i))
                        .originalName(prepared.get(i).originalName())
                        .contentType(prepared.get(i).kind().mimeType())
                        .sizeBytes((long) prepared.get(i).content().length)
                        .sha256(prepared.get(i).sha256())
                        .pageCount(prepared.get(i).pages())
                        .createdAt(now)
                        .build()))
                .then(Mono.just(saved)));
    }

    private Mono<String> uniqueReference() {
        return Mono.defer(() -> {
            String candidate = newReference();
            return donations.existsByReference(candidate)
                    .flatMap(exists -> exists ? uniqueReference() : Mono.just(candidate));
        });
    }

    /** {@code KD-7F3K-92QX}: eight characters from an alphabet without lookalikes. */
    static String newReference() {
        StringBuilder out = new StringBuilder("KD-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                out.append('-');
            }
            out.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return out.toString();
    }

    // ── Administrator ───────────────────────────────────────────────────────

    public Mono<DonationPage> list(String status, int page, int limit) {
        String filter = status == null || status.isBlank() ? null : status.trim().toLowerCase(java.util.Locale.ROOT);
        if (filter != null && !STATUSES.contains(filter)) {
            return Mono.error(ApiException.badRequest("Unknown status: " + status));
        }
        if (page < 1) {
            return Mono.error(ApiException.badRequest("page must be at least 1"));
        }
        if (limit < 1 || limit > 50) {
            return Mono.error(ApiException.badRequest("limit must be between 1 and 50"));
        }
        return Mono.zip(
                        donations.findPage(filter, limit, (long) (page - 1) * limit).collectList(),
                        donations.countMatching(filter),
                        donations.countsByStatus().collectMap(
                                DonationRepository.StatusCount::status, DonationRepository.StatusCount::total))
                .flatMap(tuple -> {
                    List<Donation> rows = tuple.getT1();
                    if (rows.isEmpty()) {
                        return Mono.just(new DonationPage(List.of(), tuple.getT2(), page, limit, withAllStatuses(tuple.getT3())));
                    }
                    return files.countsForDonations(rows.stream().map(Donation::getId).toList())
                            .collectMap(DonationFileRepository.FileCount::donationId, DonationFileRepository.FileCount::total)
                            .map(counts -> new DonationPage(
                                    rows.stream().map(row -> toSummary(row, counts.getOrDefault(row.getId(), 0L).intValue())).toList(),
                                    tuple.getT2(), page, limit, withAllStatuses(tuple.getT3())));
                });
    }

    private static Map<String, Long> withAllStatuses(Map<String, Long> counts) {
        Map<String, Long> all = new LinkedHashMap<>();
        for (String status : List.of(Donation.PENDING, Donation.APPROVED, Donation.REJECTED, Donation.CONVERTED)) {
            all.put(status, counts.getOrDefault(status, 0L));
        }
        return all;
    }

    public Mono<DonationDetail> get(Long id) {
        return findDonation(id).flatMap(donation -> files.findAllByDonation(id).collectList()
                .map(list -> toDetail(donation, list)));
    }

    /** A file's bytes for the administrator, with what it really is. */
    public Mono<StoredFile> file(Long donationId, Long fileId) {
        return findDonation(donationId)
                .then(files.findByIdAndDonation(fileId, donationId))
                .switchIfEmpty(Mono.error(ApiException.notFound("Donation file not found")))
                .flatMap(file -> storage.get(file.getStorageKey())
                        .map(bytes -> new StoredFile(file.getOriginalName(), file.getContentType(), bytes)));
    }

    public record StoredFile(String name, String contentType, byte[] content) { }

    @Transactional
    public Mono<DonationDetail> review(Long id, Long accountId, DonationReviewRequest request) {
        String decision = request.status() == null ? "" : request.status().trim().toLowerCase(java.util.Locale.ROOT);
        if (!REVIEW_DECISIONS.contains(decision)) {
            return Mono.error(ApiException.badRequest("status must be approved or rejected"));
        }
        String note;
        try {
            note = optional(request.note(), "note", 500);
        } catch (ApiException error) {
            return Mono.error(error);
        }
        return findDonation(id).flatMap(donation -> {
            if (Donation.CONVERTED.equals(donation.getStatus())) {
                // A converted donation already became a statement: it is not reopened from here.
                return Mono.error(ApiException.conflict("A converted donation can no longer be reviewed"));
            }
            donation.setStatus(decision);
            donation.setReviewNote(note);
            donation.setReviewedByAccountId(accountId);
            donation.setReviewedAt(LocalDateTime.now(clock));
            return donations.save(donation);
        }).flatMap(saved -> get(saved.getId()));
    }

    @Transactional
    public Mono<DonationDetail> linkStatement(Long id, Long accountId, Long statementId) {
        return findDonation(id).flatMap(donation -> {
            if (Donation.REJECTED.equals(donation.getStatus())) {
                return Mono.error(ApiException.conflict("A rejected donation cannot become a statement"));
            }
            if (Donation.CONVERTED.equals(donation.getStatus())) {
                return statementId.equals(donation.getStatementId())
                        ? Mono.just(donation)
                        : Mono.error(ApiException.conflict("This donation was already converted into another statement"));
            }
            return statements.findByIdAndDeletedAtIsNull(statementId)
                    .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found: " + statementId)))
                    .flatMap(statement -> {
                        donation.setStatus(Donation.CONVERTED);
                        donation.setStatementId(statementId);
                        if (donation.getReviewedByAccountId() == null) {
                            donation.setReviewedByAccountId(accountId);
                            donation.setReviewedAt(LocalDateTime.now(clock));
                        }
                        return donations.save(donation);
                    });
        }).flatMap(saved -> get(saved.getId()));
    }

    /** The OCR over the stored files, as the exam builder reads it. Nothing is persisted. */
    public Mono<OcrResponse> extract(Long id) {
        return findDonation(id).flatMap(donation -> files.findAllByDonation(id).collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return Mono.error(ApiException.conflict("This donation has no files"));
                    }
                    return Flux.fromIterable(list)
                            .concatMap(file -> storage.get(file.getStorageKey())
                                    .map(bytes -> new OcrUploadedFile(
                                            file.getOriginalName(), MediaType.parseMediaType(file.getContentType()), bytes)))
                            .collectList()
                            .flatMap(ocr::extractTextFromUploadedFiles);
                }));
    }

    private Mono<Donation> findDonation(Long id) {
        return donations.findById(id).switchIfEmpty(Mono.error(ApiException.notFound("Donation not found: " + id)));
    }

    private static DonationSummary toSummary(Donation d, int fileCount) {
        return new DonationSummary(d.getId(), d.getReference(), d.getStatus(), d.getInstitutionName(),
                d.getSubjectName(), d.getExamType(), d.getClassGrade(), d.getSchoolYear(), fileCount, d.getCreatedAt());
    }

    private static DonationDetail toDetail(Donation d, List<DonationFile> list) {
        return new DonationDetail(d.getId(), d.getReference(), d.getStatus(), d.getDonorName(), d.getDonorEmail(),
                d.getInstitutionName(), d.getSubjectName(), d.getClassGrade(), d.getSchoolYear(), d.getExamType(),
                d.getNotes(), d.getConsentAt(), d.getConsentVersion(), d.getReviewNote(), d.getReviewedAt(),
                d.getStatementId(), d.getCreatedAt(),
                list.stream().map(f -> new DonationDetail.DonationFileInfo(
                        f.getId(), f.getOriginalName(), f.getContentType(), f.getSizeBytes())).toList());
    }

    private static String sha256(byte[] content) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }
}
