package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Issue #116 against a migrated database. {@code statements.created_by} is an account
 * id and {@code teaching_assignments.teacher_id} a teacher id, so the teachers here get
 * ids that differ from their accounts on purpose: with equal ids a query that mixed the
 * two would still pass. Ids from 930001.
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class TutorStylePostgresTest {

    private static final long ACCOUNT_A = 930001L;
    private static final long ACCOUNT_B = 930002L;
    private static final long TEACHER_A = 930011L;
    private static final long TEACHER_B = 930012L;
    private static final long CLASS = 930001L;
    private static final long REDES = 930001L;
    private static final long MATEMATICA = 930002L;

    @Autowired TutorStyleService styles;
    @Autowired DatabaseClient database;

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
    void data() {
        for (String cleanup : List.of(
                "DELETE FROM teaching_assignments WHERE teacher_id BETWEEN 930000 AND 939999",
                "DELETE FROM teachers WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM accounts WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM classes WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM courses WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM subjects WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM school_years WHERE id BETWEEN 930000 AND 939999",
                "DELETE FROM institutions WHERE id BETWEEN 930000 AND 939999")) {
            sql(cleanup);
        }
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) "
                + "VALUES (930001, 'IT930', 'Escola de teste', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES "
                + "(930001, 2093, 2094, now(), now()), (930002, 2095, 2096, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) "
                + "VALUES (930001, 'C930', 'Informática', 930001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) "
                + "VALUES (930001, '12B', 12, 930001, 930001, 930001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES "
                + "(930001, 'R930', 'Redes', now(), now()), (930002, 'M930', 'Matemática', now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "SELECT i, 'tu' || i, 'tu' || i || '@kixi.test', 'x', true, true, now(), now() "
                + "FROM generate_series(930001, 930002) i");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(930011, 930001, 'Helena', 'Teste', now(), now()), (930012, 930002, 'Carlos', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, tutor_style, created_at) VALUES "
                + "(930011, 930001, 930001, 930001, 'explica com exemplos do dia a dia', now()), "
                + "(930011, 930001, 930002, 930001, 'prefere a fórmula primeiro', now()), "
                + "(930012, 930001, 930001, 930001, 'sempre muito breve', now())");
    }

    @Test
    void theStyleIsTheAuthorsAccountsForThatClassAndSubject() {
        assertThat(styleOf(ACCOUNT_A, REDES)).isEqualTo("explica com exemplos do dia a dia");
        assertThat(styleOf(ACCOUNT_A, MATEMATICA)).isEqualTo("prefere a fórmula primeiro");
        assertThat(styleOf(ACCOUNT_B, REDES)).isEqualTo("sempre muito breve");
    }

    @Test
    void aTeacherIdIsNotAnAccountId() {
        // What the first version of this lookup did: created_by matched against teacher_id.
        assertThat(styleOf(TEACHER_A, REDES)).isEmpty();
        assertThat(styleOf(TEACHER_B, REDES)).isEmpty();
    }

    @Test
    void aWithdrawnAssignmentTakesItsStyleWithIt() {
        sql("UPDATE teaching_assignments SET deleted_at = now() WHERE teacher_id = 930012");
        sql("UPDATE teachers SET deleted_at = now() WHERE id = 930011");

        assertThat(styleOf(ACCOUNT_B, REDES)).isEmpty();
        assertThat(styleOf(ACCOUNT_A, REDES)).isEmpty();
    }

    @Test
    void anAssignmentOfAnotherYearIsNotTheClasssOwn() {
        sql("UPDATE teaching_assignments SET school_year_id = 930002 WHERE teacher_id = 930012");

        assertThat(styleOf(ACCOUNT_B, REDES)).isEmpty();
    }

    private String styleOf(long author, long subject) {
        Statement statement = new Statement();
        statement.setCreatedBy(author);
        statement.setClassId(CLASS);
        statement.setSubjectId(subject);
        return styles.styleOf(statement).block(Duration.ofSeconds(10));
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
