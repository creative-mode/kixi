package ao.creativemode.kixi.institutions.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.CreateEnrollmentCodeRequest;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollmentCodeResponse;
import ao.creativemode.kixi.institutions.model.EnrollmentCode;
import ao.creativemode.kixi.institutions.repository.EnrollmentCodeRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enrollment codes (#255): the school's way of handing a student a class, instead of the
 * student picking any classId they can guess.
 *
 * <p>Issuing, listing and revoking a code are ordinary CRUD over {@code enrollment_codes},
 * guarded ADMIN-only by {@link ao.creativemode.kixi.institutions.controller.EnrollmentCodeController}.
 * {@link #redeem} is the one method a student's own self-enroll calls, and it is the one
 * that has to be race-safe: a code with {@code max_uses = 1} given to a class of 40 students
 * racing to be first must let exactly one of them in, not however many land inside the same
 * read-then-write window. The single {@code UPDATE ... WHERE uses < max_uses RETURNING} below
 * is what Postgres itself serializes per row, the same trick {@code ApiAbuseRateLimiter}
 * already uses for its own reserve-under-a-cap problem.</p>
 */
@Service
public class EnrollmentCodeService {

    private static final int CODE_LENGTH = 8;
    // Sem O/0 nem I/1/L, que se confundem a ditar ou a ler um código à pressa.
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    private static final String REDEEM_SQL = """
            UPDATE enrollment_codes
            SET uses = uses + 1, updated_at = CURRENT_TIMESTAMP
            WHERE code = :code
              AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
              AND uses < max_uses
            RETURNING id, class_id
            """;

    private final EnrollmentCodeRepository codes;
    private final ClassRepository classes;
    private final DatabaseClient databaseClient;
    private final SecureRandom random = new SecureRandom();

    public EnrollmentCodeService(
        EnrollmentCodeRepository codes,
        ClassRepository classes,
        DatabaseClient databaseClient
    ) {
        this.codes = codes;
        this.classes = classes;
        this.databaseClient = databaseClient;
    }

    public Mono<EnrollmentCodeResponse> create(Long createdByAccountId, CreateEnrollmentCodeRequest request) {
        int maxUses = request.maxUses() != null ? request.maxUses() : 1;
        if (request.expiresAt() != null && !request.expiresAt().isAfter(LocalDateTime.now())) {
            return Mono.error(ApiException.badRequest("expiresAt must be in the future"));
        }
        return classes.findByIdAndDeletedAtIsNull(request.classId())
            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
            .then(Mono.defer(() -> generateUniqueCode(MAX_CODE_GENERATION_ATTEMPTS)))
            .flatMap(code -> codes.save(
                new EnrollmentCode(code, request.classId(), maxUses, request.expiresAt(), createdByAccountId)))
            .map(EnrollmentCodeService::toResponse);
    }

    public Flux<EnrollmentCodeResponse> listByClass(Long classId) {
        return codes.findAllByClassIdOrderByIdDesc(classId).map(EnrollmentCodeService::toResponse);
    }

    public Mono<EnrollmentCodeResponse> revoke(Long id) {
        return codes.findById(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Enrollment code not found")))
            .flatMap(code -> {
                if (code.isRevoked()) {
                    return Mono.just(code);
                }
                code.setRevokedAt(LocalDateTime.now());
                return codes.save(code);
            })
            .map(EnrollmentCodeService::toResponse);
    }

    /**
     * Consumes one use of {@code code}, atomically. Fails the same way whether the code
     * never existed, was revoked, expired or already used up: telling those apart would
     * only help someone fishing for a valid one.
     */
    public Mono<Redemption> redeem(String code) {
        if (code == null || code.isBlank()) {
            return Mono.error(ApiException.badRequest("An enrollment code is required"));
        }
        return databaseClient.sql(REDEEM_SQL)
            .bind("code", normalize(code))
            .map((row, metadata) -> new Redemption(
                row.get("id", Long.class),
                row.get("class_id", Long.class)))
            .one()
            .switchIfEmpty(Mono.error(ApiException.badRequest(
                "Invalid, expired, revoked or already used enrollment code")));
    }

    private Mono<String> generateUniqueCode(int attemptsLeft) {
        if (attemptsLeft <= 0) {
            return Mono.error(ApiException.serviceUnavailable("Could not generate a unique enrollment code"));
        }
        String candidate = randomCode();
        return codes.existsByCode(candidate)
            .flatMap(exists -> exists ? generateUniqueCode(attemptsLeft - 1) : Mono.just(candidate));
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase();
    }

    static EnrollmentCodeResponse toResponse(EnrollmentCode code) {
        return new EnrollmentCodeResponse(
            code.getId(),
            code.getCode(),
            code.getClassId(),
            code.getMaxUses(),
            code.getUses(),
            code.getExpiresAt(),
            code.getRevokedAt(),
            code.getCreatedAt()
        );
    }

    /** What a successful redemption resolves to: the code itself, and the class it opens. */
    public record Redemption(Long enrollmentCodeId, Long classId) { }
}
