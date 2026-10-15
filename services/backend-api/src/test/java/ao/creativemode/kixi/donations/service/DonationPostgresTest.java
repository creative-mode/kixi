package ao.creativemode.kixi.donations.service;

import static ao.creativemode.kixi.donations.service.DonationFixtures.png;
import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.donations.dto.DonationDetail;
import ao.creativemode.kixi.donations.dto.DonationReviewRequest;
import ao.creativemode.kixi.donations.dto.DonationSubmission;
import ao.creativemode.kixi.donations.repository.DonationFileRepository;
import ao.creativemode.kixi.donations.repository.DonationRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.DonationRateLimitException;
import ao.creativemode.kixi.shared.storage.ImageStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Donations against a migrated database (issue #252): what is stored is what was sent and what the
 * bytes are, the budget is counted by the database, a statement that goes away does not take the
 * donation with it, and a donation that goes takes its file rows with it.
 *
 * <p>Statement 980001; the donations it makes are found and removed by the institution name.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "app.donations.per-hour=3",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class DonationPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String MARK = "Escola de teste 980000";
    private static final long STATEMENT = 980001L;
    private static final long REVIEWER = 980002L;
    private static final Path STORAGE_ROOT = tempDir();

    @Autowired DonationService service;
    @Autowired DonationRateLimiter limiter;
    @Autowired DonationRepository donations;
    @Autowired DonationFileRepository files;
    @Autowired ImageStorage storage;
    @Autowired DatabaseClient database;

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("kixi-donations-it");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

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
        registry.add("storage.local-root", STORAGE_ROOT::toString);
    }

    @AfterAll
    static void cleanStorage() throws java.io.IOException {
        try (var walk = Files.walk(STORAGE_ROOT)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @BeforeEach
    void clean() {
        sql("DELETE FROM statement_donations WHERE institution_name = '" + MARK + "'");
        sql("DELETE FROM accounts WHERE id = " + REVIEWER);
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "VALUES (" + REVIEWER + ", 'donation-reviewer-980002', 'donation-reviewer-980002@example.com', "
                + "'x', true, true, now(), now())");
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM donation_rate_limits");
    }

    private DonationSubmission submission(byte[]... uploads) {
        List<DonationSubmission.Upload> list = new java.util.ArrayList<>();
        int i = 0;
        for (byte[] bytes : uploads) {
            list.add(new DonationSubmission.Upload("p" + (i++) + ".png", bytes));
        }
        return new DonationSubmission("Ana", "ana@example.com", MARK, "Matemática", "12ª Classe", "2024/2025",
                "Exame", "nota", true, null, System.currentTimeMillis() - 60_000, list);
    }

    private DonationDetail donate(byte[]... uploads) {
        String reference = service.submit(submission(uploads), "a".repeat(64)).block(WAIT).reference();
        Long id = donations.findAll().filter(d -> d.getReference().equals(reference)).blockFirst(WAIT).getId();
        return service.get(id).block(WAIT);
    }

    @Test
    void aDonationIsStoredPendingWithItsFilesOnDiskAndNoAddress() {
        DonationDetail detail = donate(png(30, 30, 1), png(31, 31, 2));

        assertThat(detail.status()).isEqualTo("pending");
        assertThat(detail.files()).hasSize(2);
        var row = donations.findById(detail.id()).block(WAIT);
        assertThat(row.getClientHash()).isEqualTo("a".repeat(64));
        assertThat(row.getConsentVersion()).isNotBlank();
        assertThat(row.getConsentAt()).isNotNull();
        for (var file : files.findAllByDonation(detail.id()).collectList().block(WAIT)) {
            assertThat(storage.get(file.getStorageKey()).block(WAIT)).isNotEmpty();
            assertThat(file.getSha256()).hasSize(64);
        }
    }

    @Test
    void theSameBytesAreNeverStoredTwiceInOneDonationAndTheDatabaseAgrees() {
        DonationDetail detail = donate(png(40, 40, 3), png(40, 40, 3));
        assertThat(detail.files()).hasSize(1);

        var first = files.findAllByDonation(detail.id()).blockFirst(WAIT);
        var clone = ao.creativemode.kixi.donations.model.DonationFile.builder().donationId(detail.id())
                .storageKey("donations/other").originalName("x.png").contentType("image/png").sizeBytes(1L)
                .sha256(first.getSha256()).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> files.save(clone).block(WAIT))
                .as("UNIQUE (donation_id, sha256)").isInstanceOf(Exception.class);
    }

    @Test
    void theListFiltersCountsAndPagesNewestFirst() {
        donate(png(50, 50, 4));
        DonationDetail second = donate(png(51, 51, 5));
        service.review(second.id(), REVIEWER, new DonationReviewRequest("rejected", "ilegível")).block(WAIT);

        var all = service.list(null, 1, 50).block(WAIT);
        assertThat(all.counts()).containsKeys("pending", "approved", "rejected", "converted");
        assertThat(all.counts().get("pending")).isGreaterThanOrEqualTo(1);
        assertThat(all.counts().get("rejected")).isGreaterThanOrEqualTo(1);
        var rejected = service.list("rejected", 1, 50).block(WAIT);
        assertThat(rejected.items()).extracting(item -> item.id()).contains(second.id());
        assertThat(rejected.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("rejected"));
        assertThat(service.list(null, 1, 1).block(WAIT).items()).hasSize(1);
    }

    @Test
    void linkingBecomesConvertedAndAStatementThatGoesDoesNotTakeTheDonation() {
        sql("INSERT INTO statements (id, exam_type, title, visible, created_at) "
                + "VALUES (" + STATEMENT + ", 'Exame', 'Prova doada', false, now())");
        DonationDetail detail = donate(png(60, 60, 6));

        DonationDetail linked = service.linkStatement(detail.id(), REVIEWER, STATEMENT).block(WAIT);
        assertThat(linked.status()).isEqualTo("converted");
        assertThat(linked.statementId()).isEqualTo(STATEMENT);

        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        var after = donations.findById(detail.id()).block(WAIT);
        assertThat(after).as("ON DELETE SET NULL").isNotNull();
        assertThat(after.getStatementId()).isNull();
    }

    @Test
    void removingADonationTakesItsFileRowsWithIt() {
        DonationDetail detail = donate(png(70, 70, 7));
        sql("DELETE FROM statement_donations WHERE id = " + detail.id());

        assertThat(files.findAllByDonation(detail.id()).collectList().block(WAIT)).isEmpty();
    }

    @Test
    void theBudgetIsCountedByTheDatabaseAndTheFourthSubmissionIsRefused() {
        for (int i = 0; i < 3; i++) {
            limiter.checkSubmission("b".repeat(64)).block(WAIT);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.checkSubmission("b".repeat(64)).block(WAIT))
                .isInstanceOf(DonationRateLimitException.class)
                .satisfies(error -> assertThat(((DonationRateLimitException) error).getRetryAfterSeconds())
                        .isBetween(1L, 3600L));
        // Another client is unaffected.
        limiter.checkSubmission("c".repeat(64)).block(WAIT);
    }

    @Test
    void theRowIsRefusedByTheDatabaseIfItsStatusIsNotOneOfTheFour() {
        DonationDetail detail = donate(png(80, 80, 8));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sql("UPDATE statement_donations SET status = 'approvedd' WHERE id = " + detail.id()))
                .isInstanceOf(Exception.class);
        assertThat(ApiException.class).isNotNull();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
