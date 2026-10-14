package ao.creativemode.kixi.issue122;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import ao.creativemode.kixi.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The feed's main HTTP path with real scope, repositories, PostgreSQL and Flyway. */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.jwt.secret=feed-integration-test-secret-which-is-long-enough",
        "app.simulations.expiry-enabled=false",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class FeedPostgresIntegrationTest {
    private static final long STUDENT = 122401L;
    private static final long OTHER_STUDENT = 122402L;
    private static final long INSTITUTION = 122401L;
    private static final long OTHER_INSTITUTION = 122402L;
    private static final long YEAR = 122401L;
    private static final long COURSE = 122401L;
    private static final long OTHER_COURSE = 122402L;
    private static final long CLASS = 122401L;
    private static final long OTHER_CLASS = 122402L;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String studentToken;
    private String otherStudentToken;

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
    void fixture() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        sql("DELETE FROM post_reports WHERE post_id IN (SELECT id FROM posts WHERE author_account_id IN ("
                + STUDENT + "," + OTHER_STUDENT + "))");
        sql("DELETE FROM post_comments WHERE post_id IN (SELECT id FROM posts WHERE author_account_id IN ("
                + STUDENT + "," + OTHER_STUDENT + "))");
        sql("DELETE FROM post_reactions WHERE post_id IN (SELECT id FROM posts WHERE author_account_id IN ("
                + STUDENT + "," + OTHER_STUDENT + "))");
        sql("DELETE FROM posts WHERE author_account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM feed_rate_limits WHERE rate_key IN ('account:" + STUDENT + "','account:" + OTHER_STUDENT + "')");
        sql("DELETE FROM enrollments WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM classes WHERE id IN (" + CLASS + "," + OTHER_CLASS + ")");
        sql("DELETE FROM courses WHERE id IN (" + COURSE + "," + OTHER_COURSE + ")");
        sql("DELETE FROM institutions WHERE id IN (" + INSTITUTION + "," + OTHER_INSTITUTION + ")");
        sql("DELETE FROM school_years WHERE id = " + YEAR);

        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION
                + ", 'I122FA', 'Feed #122 A', now(), now()), (" + OTHER_INSTITUTION
                + ", 'I122FB', 'Feed #122 B', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (" + YEAR
                + ", 2424, 2425, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (" + COURSE
                + ", 'C122FA', 'Feed A', " + INSTITUTION + ", now(), now()), (" + OTHER_COURSE
                + ", 'C122FB', 'Feed B', " + OTHER_INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES ("
                + CLASS + ", 'F122A', 12, " + COURSE + ", " + YEAR + ", " + INSTITUTION + ", now(), now()), ("
                + OTHER_CLASS + ", 'F122B', 12, " + OTHER_COURSE + ", " + YEAR + ", " + OTHER_INSTITUTION + ", now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES ("
                + STUDENT + ", 'feed-122-a', 'feed-122-a@kixi.test', 'x', true, true), ("
                + OTHER_STUDENT + ", 'feed-122-b', 'feed-122-b@kixi.test', 'x', true, true)");
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id, status, created_at, updated_at) VALUES ("
                + STUDENT + ", " + CLASS + ", " + YEAR + ", 'ACTIVE', now(), now()), ("
                + OTHER_STUDENT + ", " + OTHER_CLASS + ", " + YEAR + ", 'ACTIVE', now(), now())");
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
        otherStudentToken = jwtService.generateToken(OTHER_STUDENT, List.of("STUDENT"));
    }

    @Test
    void realFeedPathCreatesListsAndScopesSocialActions() {
        AtomicLong postId = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/feed/posts").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"TOPIC\",\"classId\":" + CLASS
                        + ",\"title\":\"Topic\",\"content\":\"A real feed post\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.institutionId").isEqualTo((int) INSTITUTION)
                .jsonPath("$.classId").isEqualTo((int) CLASS)
                .jsonPath("$.commentCount").isEqualTo(0)
                .jsonPath("$.hasReacted").isEqualTo(false)
                .jsonPath("$.id").value(value -> postId.set(((Number) value).longValue()));

        long id = postId.get();
        bearer(studentToken).get().uri("/api/v1/feed/posts?page=1&limit=1")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].id").isEqualTo((int) id);
        bearer(studentToken).post().uri("/api/v1/feed/posts/" + id + "/comments")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"content\":\"Useful\"}")
                .exchange().expectStatus().isCreated();
        bearer(studentToken).post().uri("/api/v1/feed/posts/" + id + "/reactions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reaction\":\"APPLAUSE\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.reacted").isEqualTo(true);
        bearer(studentToken).post().uri("/api/v1/feed/posts/" + id + "/reports")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"Needs review\"}")
                .exchange().expectStatus().isCreated();

        bearer(otherStudentToken).get().uri("/api/v1/feed/posts").exchange()
                .expectStatus().isOk().expectBody().json("[]");
        bearer(otherStudentToken).post().uri("/api/v1/feed/posts/" + id + "/comments")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"content\":\"Out of scope\"}")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void realFeedPathRejectsForgedScopeAndInvalidPagination() {
        bearer(studentToken).get().uri("/api/v1/feed/posts?classId=" + OTHER_CLASS)
                .exchange().expectStatus().isNotFound();
        bearer(studentToken).get().uri("/api/v1/feed/posts?page=0&limit=10")
                .exchange().expectStatus().isBadRequest();
        bearer(studentToken).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"TOPIC\",\"classId\":" + OTHER_CLASS + ",\"content\":\"forged\"}")
                .exchange().expectStatus().isNotFound();
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
