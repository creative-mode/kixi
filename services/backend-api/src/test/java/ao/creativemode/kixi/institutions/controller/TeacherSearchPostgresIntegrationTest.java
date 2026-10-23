package ao.creativemode.kixi.institutions.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.List;

import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.dto.profile.TeacherCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The teacher search over HTTP against PostgreSQL migrated by Flyway (issue #406).
 *
 * <p>The search replaced a frontend workaround that asked one school at a time, so it has to be
 * at least as open as the school profile already was and never more: any signed-in account finds
 * a teacher of a school it does not attend, a teacher at two schools comes back once with both,
 * and no contact ever leaves the API.</p>
 *
 * <p>Fixture: schools 123101 (verified), 123102 and 123103; teachers 123101 and 123102; student
 * account 123301, whose only school is 123103.</p>
 */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "app.security.rate-limit.search-max-attempts=3",
        "app.security.rate-limit.search-window-seconds=60",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class TeacherSearchPostgresIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final long SCHOOL_A = 123101L;
    private static final long SCHOOL_B = 123102L;
    private static final long SCHOOL_C = 123103L;
    private static final long TEACHER_BOTH = 123101L;
    private static final long TEACHER_B = 123102L;
    private static final long TEACHER_BOTH_ACCOUNT = 123201L;
    private static final long TEACHER_B_ACCOUNT = 123202L;
    private static final long STUDENT = 123301L;
    private static final long STUDENT_USER = 123401L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String studentToken;

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
        client = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(20))
                .build();

        sql("DELETE FROM institution_teachers WHERE institution_id IN (" + SCHOOL_A + "," + SCHOOL_B
                + "," + SCHOOL_C + ") OR teacher_id IN (" + TEACHER_BOTH + "," + TEACHER_B + ")");
        sql("DELETE FROM institution_students WHERE institution_id IN (" + SCHOOL_A + "," + SCHOOL_B
                + "," + SCHOOL_C + ") OR user_id = " + STUDENT_USER);
        sql("DELETE FROM teachers WHERE id IN (" + TEACHER_BOTH + "," + TEACHER_B + ")");
        sql("DELETE FROM institutions WHERE id IN (" + SCHOOL_A + "," + SCHOOL_B + "," + SCHOOL_C + ")");
        sql("DELETE FROM users WHERE id = " + STUDENT_USER);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER_BOTH_ACCOUNT + ","
                + TEACHER_B_ACCOUNT + "," + STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER_BOTH_ACCOUNT + "," + TEACHER_B_ACCOUNT
                + "," + STUDENT + ")");
        // A fresh search budget for every test, so the limit test is the only one that hits it.
        sql("DELETE FROM api_abuse_rate_limits");

        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(" + TEACHER_BOTH_ACCOUNT + ", 'it-search-t1', 'it-search-t1@example.com', 'x', true),"
                + "(" + TEACHER_B_ACCOUNT + ", 'it-search-t2', 'it-search-t2@example.com', 'x', true),"
                + "(" + STUDENT + ", 'it-search-student', 'it-search-student@example.com', 'x', true)");
        sql("INSERT INTO account_roles (account_id, role_id) "
                + "SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO users (id, account_id, first_name, last_name) VALUES ("
                + STUDENT_USER + ", " + STUDENT + ", 'Aluna', 'Search')");
        sql("INSERT INTO institutions (id, code, name, short_name, verified) VALUES "
                + "(" + SCHOOL_A + ", 'IT-SEARCH-A', 'Instituto A', 'A', true),"
                + "(" + SCHOOL_B + ", 'IT-SEARCH-B', 'Instituto B', 'B', false),"
                + "(" + SCHOOL_C + ", 'IT-SEARCH-C', 'Instituto C', 'C', false)");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, specialty) VALUES "
                + "(" + TEACHER_BOTH + ", " + TEACHER_BOTH_ACCOUNT + ", 'Joaquim', 'Alberto', 'Matematica'),"
                + "(" + TEACHER_B + ", " + TEACHER_B_ACCOUNT + ", 'Rita', 'Joaquim', 'Fisica')");
        // The first teacher works at two schools; the student attends only C, which has none.
        sql("INSERT INTO institution_teachers (institution_id, teacher_id) VALUES "
                + "(" + SCHOOL_A + ", " + TEACHER_BOTH + "),"
                + "(" + SCHOOL_B + ", " + TEACHER_BOTH + "),"
                + "(" + SCHOOL_B + ", " + TEACHER_B + ")");
        sql("INSERT INTO institution_students (institution_id, user_id) VALUES ("
                + SCHOOL_C + ", " + STUDENT_USER + ")");

        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
    }

    @Test
    void anAnonymousVisitorCannotSearchTeachers() {
        client.get()
                .uri("/api/v1/profiles/teachers?q=joaquim")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aShortQueryAnswersNothing() {
        List<TeacherCard> found = search(studentToken, "j");

        assertThat(found).isEmpty();
    }

    @Test
    void findsATeacherOfASchoolTheStudentDoesNotAttend() {
        List<TeacherCard> found = search(studentToken, "matematica");

        assertThat(found).hasSize(1);
        TeacherCard teacher = found.get(0);
        assertThat(teacher.displayName()).isEqualTo("Joaquim Alberto");
        assertThat(teacher.specialty()).isEqualTo("Matematica");
        // The student only attends C, yet searches a teacher of A and B.
        assertThat(teacher.schools()).extracting("name")
                .containsExactlyInAnyOrder("Instituto A", "Instituto B");
    }

    @Test
    void aTeacherAtTwoSchoolsIsOneCardCarryingBoth() {
        List<TeacherCard> found = search(studentToken, "joaquim");

        assertThat(found).extracting(TeacherCard::displayName)
                .containsExactlyInAnyOrder("Joaquim Alberto", "Rita Joaquim");

        TeacherCard atTwoSchools = found.stream()
                .filter(card -> card.displayName().equals("Joaquim Alberto"))
                .findFirst()
                .orElseThrow();
        assertThat(atTwoSchools.schools()).hasSize(2);
        // A teacher of a verified school is verified by it.
        assertThat(atTwoSchools.verified()).isTrue();

        TeacherCard atOneSchool = found.stream()
                .filter(card -> card.displayName().equals("Rita Joaquim"))
                .findFirst()
                .orElseThrow();
        assertThat(atOneSchool.schools()).extracting("name").containsExactly("Instituto B");
    }

    @Test
    void theSearchNeverCarriesAContact() {
        client.mutate().defaultHeaders(headers -> headers.setBearerAuth(studentToken)).build()
                .get()
                .uri("/api/v1/profiles/teachers?q=joaquim")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].email").doesNotExist()
                .jsonPath("$[0].employeeNumber").doesNotExist()
                .jsonPath("$[0].phone").doesNotExist();
    }

    @Test
    void aSearchOverTheLimitIsRejectedWithRetryAfter() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            search(studentToken, "joaquim");
        }

        client.mutate().defaultHeaders(headers -> headers.setBearerAuth(studentToken)).build()
                .get()
                .uri("/api/v1/profiles/teachers?q=joaquim")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");
    }

    @Test
    void aStudentStillCannotReachTheAccountAndUserTrees() {
        client.mutate().defaultHeaders(headers -> headers.setBearerAuth(studentToken)).build()
                .get()
                .uri("/api/v1/accounts")
                .exchange()
                .expectStatus().isForbidden();

        client.mutate().defaultHeaders(headers -> headers.setBearerAuth(studentToken)).build()
                .get()
                .uri("/api/v1/users")
                .exchange()
                .expectStatus().isForbidden();
    }

    private List<TeacherCard> search(String token, String query) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build()
                .get()
                .uri("/api/v1/profiles/teachers?q=" + query)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TeacherCard.class)
                .returnResult()
                .getResponseBody();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
