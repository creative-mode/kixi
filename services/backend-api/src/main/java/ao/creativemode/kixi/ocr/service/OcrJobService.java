package ao.creativemode.kixi.ocr.service;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.ExamExtractionResponse;
import ao.creativemode.kixi.ocr.dto.OcrJobStatusResponse;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Durable, account-scoped queue for long-running exam OCR imports. */
@Service
public class OcrJobService {

    private static final Logger log = LoggerFactory.getLogger(OcrJobService.class);
    private static final long QUEUE_LOCK_KEY = 425_425_425L;
    private static final int MAX_ACTIVE_JOBS = 20;
    private static final long MAX_ACTIVE_INPUT_BYTES = 256L * 1024 * 1024;

    private static final String CLAIM_SQL = """
        UPDATE ocr_jobs SET status = 'PROCESSING', lease_owner = :owner,
            lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
            updated_at = CURRENT_TIMESTAMP
        WHERE id = (
            SELECT id FROM ocr_jobs
            WHERE status = 'QUEUED'
               OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP)
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        RETURNING id, account_id, request_id, page_count, completed_pages, current_page, frontend_upload_ms
        """;

    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final OcrServiceClient ocrClient;
    private final OcrImageAssociationService imageAssociationService;
    private final ObjectMapper objectMapper;

    public OcrJobService(
        DatabaseClient database,
        TransactionalOperator transactions,
        OcrServiceClient ocrClient,
        OcrImageAssociationService imageAssociationService,
        ObjectMapper objectMapper
    ) {
        this.database = database;
        this.transactions = transactions;
        this.ocrClient = ocrClient;
        this.imageAssociationService = imageAssociationService;
        this.objectMapper = objectMapper;
    }

    public Mono<UUID> enqueue(long accountId, UUID requestId, int frontendUploadMs,
                              List<OcrUploadedFile> files) {
        UUID jobId = UUID.randomUUID();
        long incomingBytes = files.stream().mapToLong(file -> file.content().length).sum();
        Mono<UUID> insert = database.sql("SELECT pg_advisory_xact_lock(" + QUEUE_LOCK_KEY + ")")
            .fetch().one()
            .then(database.sql("""
                SELECT COUNT(DISTINCT j.id) AS active, COALESCE(SUM(octet_length(f.content)), 0) AS active_bytes
                FROM ocr_jobs j LEFT JOIN ocr_job_files f ON f.job_id = j.id
                WHERE j.status IN ('QUEUED', 'PROCESSING')
                """)
                .map((row, meta) -> new QueueUsage(row.get("active", Long.class), row.get("active_bytes", Long.class)))
                .one())
            .flatMap(usage -> usage.active() >= MAX_ACTIVE_JOBS
                ? Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "OCR queue is full; try again later"))
                : usage.activeBytes() + incomingBytes > MAX_ACTIVE_INPUT_BYTES
                    ? Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "OCR queue storage is full; try again later"))
                : database.sql("""
                    INSERT INTO ocr_jobs (id, account_id, request_id, status, frontend_upload_ms)
                    VALUES (:id, :accountId, :requestId, 'QUEUED', :frontendUploadMs)
                    """)
                    .bind("id", jobId)
                    .bind("accountId", accountId)
                    .bind("requestId", requestId)
                    .bind("frontendUploadMs", frontendUploadMs)
                    .fetch().rowsUpdated()
                    .thenMany(Flux.range(0, files.size())
                        .concatMap(position -> insertFile(jobId, position, files.get(position))))
                    .then(Mono.just(jobId)))
            .as(transactions::transactional);

        return insert
            .doOnSuccess(id -> log.info("OCR job queued: requestId={}, jobId={}", requestId, id))
            .onErrorMap(error -> isActiveJobConflict(error)
                ? ApiException.conflict("An OCR import is already active for this account")
                : error);
    }

    public Mono<OcrJobStatusResponse> status(long accountId, UUID jobId) {
        return database.sql("""
            UPDATE ocr_jobs SET client_seen_at = CURRENT_TIMESTAMP
            WHERE id = :id AND account_id = :accountId AND status IN ('QUEUED', 'PROCESSING')
            """)
            .bind("id", jobId)
            .bind("accountId", accountId)
            .fetch().rowsUpdated()
            .then(database.sql("""
            SELECT id, request_id, status, page_count, completed_pages, current_page,
                   event_type, result_json::text AS result_json, error_message
            FROM ocr_jobs WHERE id = :id AND account_id = :accountId
            """)
            .bind("id", jobId)
            .bind("accountId", accountId)
            .map((row, meta) -> {
                String resultText = row.get("result_json", String.class);
                JsonNode result = parseResult(resultText);
                return new OcrJobStatusResponse(
                    row.get("id", UUID.class), row.get("request_id", UUID.class),
                    row.get("status", String.class), value(row.get("page_count", Integer.class)),
                    value(row.get("completed_pages", Integer.class)), value(row.get("current_page", Integer.class)),
                    row.get("event_type", String.class), result, row.get("error_message", String.class)
                );
            })
            .one())
            .switchIfEmpty(Mono.error(ApiException.notFound("OCR job not found")));
    }

    public Mono<Void> cancel(long accountId, UUID jobId) {
        return database.sql("""
            UPDATE ocr_jobs SET status = 'CANCELLED', event_type = 'error',
                error_message = 'Import cancelled', lease_owner = NULL, lease_until = NULL,
                expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND account_id = :accountId AND status IN ('QUEUED', 'PROCESSING')
            """)
            .bind("id", jobId)
            .bind("accountId", accountId)
            .fetch().rowsUpdated()
            .flatMap(updated -> updated == 1
                ? deleteFiles(jobId)
                : status(accountId, jobId).flatMap(existing -> Mono.error(
                    ApiException.conflict("OCR job is already terminal"))));
    }

    public Mono<OcrJob> claimNext(UUID workerId) {
        return cancelAbandonedQueued()
            .then(acquireWorkerLease(workerId))
            .flatMap(acquired -> acquired ? database.sql(CLAIM_SQL)
                .bind("owner", workerId)
                .map((row, meta) -> new OcrJob(
                    row.get("id", UUID.class), row.get("account_id", Long.class),
                    row.get("request_id", UUID.class), value(row.get("page_count", Integer.class)),
                    value(row.get("completed_pages", Integer.class)), value(row.get("current_page", Integer.class)),
                    value(row.get("frontend_upload_ms", Integer.class)),
                    List.of()))
                .one()
                .flatMap(job -> loadFiles(job.id()).map(files -> job.withFiles(files)))
                .switchIfEmpty(releaseWorkerLease(workerId).then(Mono.empty()))
                : Mono.empty())
            .onErrorResume(error -> releaseWorkerLease(workerId).then(Mono.error(error)));
    }

    private Mono<Void> cancelAbandonedQueued() {
        return database.sql("""
            WITH abandoned AS (
                UPDATE ocr_jobs SET status = 'CANCELLED', event_type = 'error',
                    error_message = 'Import abandoned', lease_owner = NULL, lease_until = NULL,
                    expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'QUEUED'
                  AND client_seen_at < CURRENT_TIMESTAMP - INTERVAL '30 seconds'
                RETURNING id
            )
            DELETE FROM ocr_job_files files USING abandoned
            WHERE files.job_id = abandoned.id
            """)
            .fetch().rowsUpdated().then();
    }

    public Mono<Boolean> renewLease(UUID workerId, UUID jobId) {
        return database.sql("""
            UPDATE ocr_job_worker_lock SET lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds'
            WHERE singleton = TRUE AND owner = :owner
            """)
            .bind("owner", workerId)
            .fetch().rowsUpdated()
            .flatMap(lockUpdated -> lockUpdated == 0 ? Mono.just(false)
                : database.sql("""
                    UPDATE ocr_jobs SET lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
                      AND client_seen_at >= CURRENT_TIMESTAMP - INTERVAL '30 seconds'
                    """)
                    .bind("id", jobId).bind("owner", workerId).fetch().rowsUpdated()
                    .flatMap(updated -> updated == 1 ? Mono.just(true)
                        : cancelIfClientGone(jobId, workerId).thenReturn(false)));
    }

    private Mono<Void> cancelIfClientGone(UUID jobId, UUID workerId) {
        return database.sql("""
            UPDATE ocr_jobs SET status = 'CANCELLED', event_type = 'error',
                error_message = 'Import abandoned', lease_owner = NULL, lease_until = NULL,
                expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
              AND client_seen_at < CURRENT_TIMESTAMP - INTERVAL '30 seconds'
            """)
            .bind("id", jobId).bind("owner", workerId).fetch().rowsUpdated()
            .then(deleteFilesIfTerminal(jobId));
    }

    public Mono<Void> process(OcrJob job, UUID workerId) {
        return ocrClient.extractTextStreamFromUploadedFiles(
                job.files(), (double) job.frontendUploadMs(), job.requestId().toString())
            .flatMapMany(response -> {
                if (response.getStatusCode().isError() || response.getBody() == null) {
                    return Flux.error(new IllegalStateException("OCR service returned no event stream"));
                }
                return response.getBody();
            })
            .concatMap(event -> handleEvent(job, workerId, event))
            .then(Mono.defer(() -> fail(job.id(), workerId, "OCR stream ended without a result")))
            .onErrorResume(error -> {
                log.error("OCR background job failed: requestId={}, type={}",
                    job.requestId(), error.getClass().getSimpleName());
                return fail(job.id(), workerId, "OCR processing failed");
            })
            .then();
    }

    public Mono<Void> release(UUID workerId, UUID jobId) {
        return database.sql("""
            UPDATE ocr_jobs SET lease_owner = NULL, lease_until = NULL
            WHERE id = :id AND lease_owner = :owner
            """)
            .bind("id", jobId).bind("owner", workerId).fetch().rowsUpdated()
            .then(database.sql("""
                UPDATE ocr_job_worker_lock SET owner = NULL, lease_until = NULL
                WHERE singleton = TRUE AND owner = :owner
                """)
                .bind("owner", workerId).fetch().rowsUpdated())
            .then(deleteFilesIfTerminal(jobId));
    }

    public Mono<Void> cleanupExpired() {
        return database.sql("DELETE FROM ocr_jobs WHERE expires_at < CURRENT_TIMESTAMP")
            .fetch().rowsUpdated().then();
    }

    private Mono<Void> handleEvent(OcrJob job, UUID workerId, ServerSentEvent<String> event) {
        if (event == null || event.event() == null || event.data() == null) return Mono.empty();
        try {
            JsonNode data = objectMapper.readTree(event.data());
            return switch (event.event()) {
                case "prepared" -> updateProgress(job.id(), workerId, "prepared",
                    intValue(data, "pageCount"), 0, 0);
                case "rasterized" -> updateProgress(job.id(), workerId, "rasterized",
                    intValue(data, "pageCount"), -1, intValue(data, "page"));
                case "progress" -> updateProgress(job.id(), workerId, "progress",
                    intValue(data, "pageCount"), intValue(data, "page"), intValue(data, "page"));
                case "result" -> complete(job, workerId, event.data());
                case "error" -> fail(job.id(), workerId, "OCR processing failed");
                default -> Mono.empty();
            };
        } catch (Exception error) {
            return Mono.error(new IllegalStateException("Invalid OCR event payload", error));
        }
    }

    private Mono<Void> complete(OcrJob job, UUID workerId, String json) throws Exception {
        OcrResponse response = objectMapper.readValue(json, OcrResponse.class);
        if (!response.isSuccess() && !response.isPartial()) {
            return Mono.error(new IllegalStateException("OCR returned an unsuccessful result"));
        }
        ExamExtractionResponse extraction = ExamExtractionResponse.fromOcrResponse(
            response, imageAssociationService.previewCrops(response.imagesToUpload(), job.files()));
        String result = objectMapper.writeValueAsString(extraction);
        if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 50L * 1024 * 1024) {
            return Mono.error(new IllegalStateException("OCR result exceeds the configured limit"));
        }
        return database.sql("""
            UPDATE ocr_jobs SET status = 'SUCCEEDED', event_type = 'result', result_json = CAST(:result AS JSONB),
                error_message = NULL, expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
            """)
            .bind("result", result).bind("id", job.id()).bind("owner", workerId)
            .fetch().rowsUpdated().then(deleteFiles(job.id()));
    }

    private Mono<Void> fail(UUID jobId, UUID workerId, String message) {
        return database.sql("""
            UPDATE ocr_jobs SET status = 'FAILED', event_type = 'error', error_message = :message,
                expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
            """)
            .bind("message", message).bind("id", jobId).bind("owner", workerId)
            .fetch().rowsUpdated().then(deleteFiles(jobId));
    }

    private Mono<Void> updateProgress(UUID jobId, UUID workerId, String type,
                                      int pages, int completed, int current) {
        return database.sql("""
            UPDATE ocr_jobs SET event_type = :type,
                page_count = CASE WHEN :pages > 0 THEN :pages ELSE page_count END,
                completed_pages = CASE WHEN :completed >= 0 THEN :completed ELSE completed_pages END,
                current_page = CASE WHEN :current >= 0 THEN :current ELSE current_page END,
                lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
            """)
            .bind("type", type).bind("pages", pages).bind("completed", completed)
            .bind("current", current).bind("id", jobId).bind("owner", workerId)
            .fetch().rowsUpdated().then();
    }

    private Mono<Void> insertFile(UUID jobId, int position, OcrUploadedFile file) {
        DatabaseClient.GenericExecuteSpec insert = database.sql("""
            INSERT INTO ocr_job_files (job_id, position, filename, content_type, content)
            VALUES (:jobId, :position, :filename, :contentType, :content)
            """)
            .bind("jobId", jobId).bind("position", position)
            .bind("filename", file.filename()).bind("content", file.content());
        insert = file.contentType() == null
            ? insert.bindNull("contentType", String.class)
            : insert.bind("contentType", file.contentType().toString());
        return insert.fetch().rowsUpdated().then();
    }

    private Mono<List<OcrUploadedFile>> loadFiles(UUID jobId) {
        return database.sql("""
            SELECT filename, content_type, content FROM ocr_job_files
            WHERE job_id = :jobId ORDER BY position
            """)
            .bind("jobId", jobId)
            .map((row, meta) -> new OcrUploadedFile(
                row.get("filename", String.class), mediaType(row.get("content_type", String.class)),
                row.get("content", byte[].class)))
            .all().collectList();
    }

    private Mono<Boolean> acquireWorkerLease(UUID workerId) {
        return database.sql("""
            UPDATE ocr_job_worker_lock SET owner = :owner,
                lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds'
            WHERE singleton = TRUE AND (owner IS NULL OR lease_until < CURRENT_TIMESTAMP OR owner = :owner)
            """)
            .bind("owner", workerId).fetch().rowsUpdated().map(updated -> updated == 1);
    }

    private Mono<Void> releaseWorkerLease(UUID workerId) {
        return database.sql("""
            UPDATE ocr_job_worker_lock SET owner = NULL, lease_until = NULL
            WHERE singleton = TRUE AND owner = :owner
            """)
            .bind("owner", workerId).fetch().rowsUpdated().then();
    }

    private Mono<Void> deleteFiles(UUID jobId) {
        return database.sql("DELETE FROM ocr_job_files WHERE job_id = :id")
            .bind("id", jobId).fetch().rowsUpdated().then();
    }

    private Mono<Void> deleteFilesIfTerminal(UUID jobId) {
        return database.sql("""
            DELETE FROM ocr_job_files WHERE job_id = :id
              AND EXISTS (SELECT 1 FROM ocr_jobs WHERE id = :id AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
            """)
            .bind("id", jobId).fetch().rowsUpdated().then();
    }

    private JsonNode parseResult(String result) {
        if (result == null) return null;
        try {
            return objectMapper.readTree(result);
        } catch (Exception error) {
            throw new IllegalStateException("Stored OCR job result is invalid", error);
        }
    }

    private boolean isActiveJobConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("uq_ocr_jobs_one_active_per_account")) {
                return true;
            }
        }
        return false;
    }

    private MediaType mediaType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try { return MediaType.parseMediaType(value); }
        catch (Exception ignored) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private int intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? Math.max(0, value.intValue()) : 0;
    }

    private int value(Integer value) { return value == null ? 0 : value; }

    private record QueueUsage(long active, long activeBytes) {}

    public record OcrJob(UUID id, long accountId, UUID requestId, int pageCount,
                         int completedPages, int currentPage, int frontendUploadMs,
                         List<OcrUploadedFile> files) {
        OcrJob withFiles(List<OcrUploadedFile> files) {
            return new OcrJob(id, accountId, requestId, pageCount, completedPages, currentPage,
                frontendUploadMs, files);
        }
    }
}
