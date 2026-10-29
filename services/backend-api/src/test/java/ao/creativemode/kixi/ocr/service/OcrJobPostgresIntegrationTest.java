package ao.creativemode.kixi.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.ocr.client.OcrUploadedFile;
import ao.creativemode.kixi.ocr.dto.OcrJobStatusResponse;
import ao.creativemode.kixi.ocr.dto.OcrJobAcceptedResponse;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.context.ApplicationContext;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
    "spring.r2dbc.pool.max-size=2",
    "spring.r2dbc.pool.initial-size=0",
    "app.ocr.jobs.poll-interval-ms=86400000",
    "app.ocr.jobs.cleanup-interval-ms=86400000",
    "app.simulations.expiry-poll-ms=86400000",
    "app.simulations.expiry-enabled=false",
    "app.jwt.secret=integration-test-secret-which-is-long-enough",
    "ocr.service.url=http://127.0.0.1:1",
    "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class OcrJobPostgresIntegrationTest {

    private static final long ACCOUNT = 425_801L;
    private static final long OTHER_ACCOUNT = 425_802L;
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired OcrJobService jobs;
    @Autowired DatabaseClient database;
    @Autowired ApplicationContext applicationContext;
    @Autowired JwtService jwtService;
    private WebTestClient client;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String name = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void createAccounts() {
        client = WebTestClient.bindToApplicationContext(applicationContext)
            .apply(springSecurity()).configureClient().responseTimeout(WAIT).build();
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNT + ", " + OTHER_ACCOUNT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNT + ", " + OTHER_ACCOUNT + ")");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES "
            + "(" + ACCOUNT + ", 'ocr-job-425-a', 'ocr-job-425-a@example.com', 'x', true, true),"
            + "(" + OTHER_ACCOUNT + ", 'ocr-job-425-b', 'ocr-job-425-b@example.com', 'x', true, true)");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ACCOUNT
            + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + OTHER_ACCOUNT
            + ", id FROM roles WHERE name = 'TEACHER'");
    }

    @AfterEach
    void removeAccounts() {
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNT + ", " + OTHER_ACCOUNT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNT + ", " + OTHER_ACCOUNT + ")");
        sql("UPDATE ocr_job_worker_lock SET owner = NULL, lease_until = NULL WHERE singleton = TRUE");
    }

    @Test
    void persistsAndClaimsOneJobAndCancellationRemovesSourceBytes() {
        UUID requestId = UUID.randomUUID();
        UUID jobId = jobs.enqueue(ACCOUNT, requestId, 137,
            List.of(new OcrUploadedFile("fixture.pdf", MediaType.APPLICATION_PDF, new byte[] {1, 2, 3})))
            .block(WAIT);
        assertThat(jobId).isNotNull();

        UUID worker = UUID.randomUUID();
        OcrJobService.OcrJob claimed = jobs.claimNext(worker).block(WAIT);
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.requestId()).isEqualTo(requestId);
        assertThat(claimed.frontendUploadMs()).isEqualTo(137);
        assertThat(claimed.files()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("fixture.pdf");
            assertThat(file.content()).containsExactly(1, 2, 3);
        });
        assertThat(jobs.claimNext(UUID.randomUUID()).block(WAIT)).isNull();

        jobs.cancel(ACCOUNT, jobId).block(WAIT);
        OcrJobStatusResponse status = jobs.status(ACCOUNT, jobId).block(WAIT);
        assertThat(status.status()).isEqualTo("CANCELLED");
        assertThat(countFiles(jobId)).isZero();
        jobs.release(worker, jobId).block(WAIT);
    }

    @Test
    void activeJobIsUniquePerAccountAndNeverVisibleToAnotherAccount() {
        UUID jobId = jobs.enqueue(ACCOUNT, UUID.randomUUID(), 0,
            List.of(new OcrUploadedFile("one.jpg", MediaType.IMAGE_JPEG, new byte[] {9})))
            .block(WAIT);
        assertThatThrownBy(() -> jobs.enqueue(ACCOUNT, UUID.randomUUID(), 0,
            List.of(new OcrUploadedFile("two.jpg", MediaType.IMAGE_JPEG, new byte[] {8}))).block(WAIT))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(409));
        assertThatThrownBy(() -> jobs.status(OTHER_ACCOUNT, jobId).block(WAIT))
            .isInstanceOf(ApiException.class);
        jobs.cancel(ACCOUNT, jobId).block(WAIT);
    }

    @Test
    void aJobWithoutClientHeartbeatsIsCancelledAndItsBytesAreDeleted() {
        UUID jobId = jobs.enqueue(ACCOUNT, UUID.randomUUID(), 0,
            List.of(new OcrUploadedFile("abandoned.pdf", MediaType.APPLICATION_PDF, new byte[] {7})))
            .block(WAIT);
        UUID worker = UUID.randomUUID();
        assertThat(jobs.claimNext(worker).block(WAIT)).isNotNull();
        database.sql("UPDATE ocr_jobs SET client_seen_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
            .bind("id", jobId).fetch().rowsUpdated().block(WAIT);

        assertThat(jobs.renewLease(worker, jobId).block(WAIT)).isFalse();
        assertThat(jobs.status(ACCOUNT, jobId).block(WAIT).status()).isEqualTo("CANCELLED");
        assertThat(countFiles(jobId)).isZero();
        jobs.release(worker, jobId).block(WAIT);
    }

    @Test
    void anAbandonedQueuedJobIsRemovedBeforeTheWorkerClaimsOrStartsIt() {
        UUID jobId = jobs.enqueue(ACCOUNT, UUID.randomUUID(), 0,
            List.of(new OcrUploadedFile("abandoned-queued.pdf", MediaType.APPLICATION_PDF, new byte[] {6})))
            .block(WAIT);
        database.sql("UPDATE ocr_jobs SET client_seen_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
            .bind("id", jobId).fetch().rowsUpdated().block(WAIT);

        assertThat(jobs.claimNext(UUID.randomUUID()).block(WAIT)).isNull();
        assertThat(jobs.status(ACCOUNT, jobId).block(WAIT).status()).isEqualTo("CANCELLED");
        assertThat(countFiles(jobId)).isZero();
    }

    @Test
    void httpAcceptsAValidatedUploadAndScopesStatusAndCancellationToItsTeacher() {
        sql("UPDATE ocr_job_worker_lock SET owner = '00000000-0000-0000-0000-000000000425', "
            + "lease_until = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE singleton = TRUE");
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        StringBuilder fixture = new StringBuilder("%PDF-1.4\n");
        for (int page = 1; page <= 10; page++) {
            fixture.append(page).append(" 0 obj << /Type /Page >> endobj\n");
        }
        fixture.append("%%EOF");
        byte[] pdf = fixture.toString().getBytes(StandardCharsets.ISO_8859_1);
        multipart.part("files", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "fixture.pdf"; }
        }).contentType(MediaType.APPLICATION_PDF);
        String teacher = jwtService.generateToken(ACCOUNT, List.of("TEACHER"));
        String otherTeacher = jwtService.generateToken(OTHER_ACCOUNT, List.of("TEACHER"));

        OcrJobAcceptedResponse accepted = client.post().uri("/api/v1/ocr/jobs")
            .headers(headers -> headers.setBearerAuth(teacher))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .header("X-OCR-Request-ID", UUID.randomUUID().toString())
            .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(multipart.build()))
            .exchange().expectStatus().isAccepted().expectBody(OcrJobAcceptedResponse.class).returnResult().getResponseBody();
        assertThat(accepted).isNotNull();
        assertThat(accepted.status()).isEqualTo("QUEUED");

        client.get().uri("/api/v1/ocr/jobs/{id}", accepted.jobId())
            .headers(headers -> headers.setBearerAuth(otherTeacher))
            .exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/ocr/jobs/{id}", accepted.jobId())
            .headers(headers -> headers.setBearerAuth(teacher))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("QUEUED");
        client.delete().uri("/api/v1/ocr/jobs/{id}", accepted.jobId())
            .headers(headers -> headers.setBearerAuth(teacher))
            .exchange().expectStatus().isNoContent();
        assertThat(countFiles(accepted.jobId())).isZero();
    }

    private long countFiles(UUID jobId) {
        return database.sql("SELECT COUNT(*) AS n FROM ocr_job_files WHERE job_id = :id")
            .bind("id", jobId)
            .map((row, meta) -> row.get("n", Long.class))
            .one().block(WAIT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
