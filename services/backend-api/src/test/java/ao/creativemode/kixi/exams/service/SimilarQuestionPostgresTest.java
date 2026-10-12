package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
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
 * The "exercícios semelhantes" search against a migrated database (issue #115).
 *
 * <p>Subjects are shared across the platform — {@code subjects} has no institution of
 * its own — so a filter on the subject alone reaches every school. This is the test
 * that proves the query does not: two schools, the same subject, the same words in the
 * question, and only the one of the asker's own school comes back.</p>
 *
 * <p>The second school inherits its institution from the class, on purpose: that is the
 * shape a statement from before the institution columns existed still has, and the
 * {@code COALESCE} is what has to catch it. Ids from 940001, school year 2193-2194:
 * {@code uk_school_years_start_end} is global and shared with the other Postgres tests,
 * which is why these years are far away from theirs.</p>
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
class SimilarQuestionPostgresTest {

    private static final long SCHOOL_A = 940001L;
    private static final long SCHOOL_B = 940002L;
    private static final long COURSE_A = 940001L;
    private static final long COURSE_B = 940002L;
    private static final long CLASS_A = 940001L;
    private static final long CLASS_B = 940002L;
    private static final long YEAR = 940001L;
    private static final long SUBJECT = 940001L;
    private static final long OWN_STATEMENT = 940001L;
    private static final long OWN_QUESTION = 940001L;
    private static final long SAME_SCHOOL_STATEMENT = 940002L;
    private static final long SAME_SCHOOL_QUESTION = 940002L;
    private static final long OTHER_SCHOOL_STATEMENT = 940003L;
    private static final long OTHER_SCHOOL_QUESTION = 940003L;

    @Autowired QuestionRepository questions;
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
                "DELETE FROM questions WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM statements WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM classes WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM courses WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM subjects WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM school_years WHERE id BETWEEN 940000 AND 949999",
                "DELETE FROM institutions WHERE id BETWEEN 940000 AND 949999")) {
            sql(cleanup);
        }
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES "
                + "(940001, 'A940', 'Escola A', now(), now()), (940002, 'B940', 'Escola B', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) "
                + "VALUES (940001, 2193, 2194, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES "
                + "(940001, 'CA940', 'Informática A', 940001, now(), now()), "
                + "(940002, 'CB940', 'Informática B', 940002, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) "
                + "VALUES (940001, '12A', 12, 940001, 940001, 940001, now(), now()), "
                + "(940002, '12B', 12, 940002, 940001, 940002, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) "
                + "VALUES (940001, 'R940', 'Redes', now(), now())");
        // The paper the student is working on: published, of school A.
        sql("INSERT INTO statements (id, exam_type, title, subject_id, class_id, course_id, school_year_id, "
                + "institution_id, visible, created_at) "
                + "VALUES (940001, 'Teste', 'Prova de redes', 940001, 940001, 940001, 940001, 940001, true, now())");
        // A published paper of the same school.
        sql("INSERT INTO statements (id, exam_type, title, subject_id, class_id, course_id, school_year_id, "
                + "institution_id, visible, created_at) "
                + "VALUES (940002, 'Teste', 'Teste de redes anterior', 940001, 940001, 940001, 940001, 940001, true, now())");
        // A published paper of another school, with no institution of its own: it is
        // school B through its class, which is exactly what the COALESCE has to read.
        sql("INSERT INTO statements (id, exam_type, title, subject_id, class_id, course_id, school_year_id, "
                + "institution_id, visible, created_at) "
                + "VALUES (940003, 'Teste', 'Teste de redes de outra escola', 940001, 940002, 940002, 940001, NULL, true, now())");
        for (long[] row : new long[][] {
                {OWN_QUESTION, OWN_STATEMENT}, {SAME_SCHOOL_QUESTION, SAME_SCHOOL_STATEMENT},
                {OTHER_SCHOOL_QUESTION, OTHER_SCHOOL_STATEMENT}}) {
            sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index, "
                    + "needs_review, created_at, updated_at) "
                    + "VALUES (" + row[0] + ", " + row[1] + ", 1, 'Quantos hosts tem uma sub-rede /29?', "
                    + "'multiple_choice', 2.0, 0, false, now(), now())");
        }
    }

    @Test
    void theSearchNeverLeavesTheSchoolOfTheStudentAsking() {
        List<Long> found = candidateIds(SCHOOL_A);

        assertThat(found)
                .containsExactlyInAnyOrder(SAME_SCHOOL_QUESTION)
                .doesNotContain(OTHER_SCHOOL_QUESTION);
    }

    @Test
    void theSchoolIsInheritedFromTheClassWhenTheStatementHasNone() {
        // The other school's statement carries no institution_id: the filter has to read
        // it through the class, or the statement is treated as belonging to nobody and
        // leaks into every school's results.
        assertThat(candidateIds(SCHOOL_B)).containsExactly(OTHER_SCHOOL_QUESTION);
    }

    private List<Long> candidateIds(long institutionId) {
        return questions
                .findCatalogCandidates(SUBJECT, institutionId, OWN_STATEMENT, 50)
                .map(Question::getId)
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
