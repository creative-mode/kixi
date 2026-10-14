package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * The simulation a student gets on entering an exam room, against a database migrated by Flyway
 * (issue #233).
 *
 * <p>The leaderboard counts only simulations of its cohort's school year, so a room simulation
 * born without one is never ranked, whatever the student scored. A mock cannot say what the
 * {@code INSERT} wrote, which is the whole point of this test.</p>
 *
 * <p>Ids from 960000, cleaned up like the other Postgres suites.</p>
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
class RoomSimulationSchoolYearPostgresTest {

    private static final long CLASS_YEAR = 960001L;
    private static final long STATEMENT_YEAR = 960002L;
    private static final long ACCOUNT = 960001L;
    private static final long TEACHER = 960002L;
    private static final long ROOM = 960001L;

    @Autowired SimulationRepository simulations;
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
                "DELETE FROM simulations WHERE account_id BETWEEN 960000 AND 969999",
                "DELETE FROM exam_rooms WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM statements WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM classes WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM courses WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM accounts WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM school_years WHERE id BETWEEN 960000 AND 969999",
                "DELETE FROM institutions WHERE id BETWEEN 960000 AND 969999")) {
            sql(cleanup);
        }
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) "
                + "VALUES (960001, 'IT960', 'Escola de teste', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES "
                + "(960001, 2096, 2097, now(), now()), (960002, 2098, 2099, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) "
                + "VALUES (960001, 'C960', 'Informática', 960001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) "
                + "VALUES (960001, '12B', 12, 960001, 960001, 960001, now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "SELECT i, 'rs' || i, 'rs' || i || '@kixi.test', 'x', true, true, now(), now() "
                + "FROM generate_series(960001, 960002) i");
    }

    @Test
    void theSimulationTakesTheStatementsSchoolYear() {
        statement(960001L, STATEMENT_YEAR);
        room(ROOM, 960001L);

        assertThat(yearOfNewSimulation(960001L)).isEqualTo(STATEMENT_YEAR);
    }

    @Test
    void aStatementWithoutAYearFallsBackToTheRoomsClass() {
        statement(960002L, null);
        room(ROOM, 960002L);

        assertThat(yearOfNewSimulation(960002L)).isEqualTo(CLASS_YEAR);
    }

    private Long yearOfNewSimulation(long statementId) {
        return simulations.insertExamRoomSimulation(ACCOUNT, statementId, ROOM, 30, LocalDateTime.now())
                .block(Duration.ofSeconds(10))
                .getSchoolYearId();
    }

    private void statement(long id, Long schoolYearId) {
        sql("INSERT INTO statements (id, title, exam_type, class_id, school_year_id, created_at, updated_at) VALUES ("
                + id + ", 'Prova', 'Teste', 960001, " + schoolYearId + ", now(), now())");
    }

    private void room(long id, long statementId) {
        sql("INSERT INTO exam_rooms (id, statement_id, class_id, teacher_account_id, starts_at, ends_at, duration_minutes, status, created_at, updated_at) "
                + "VALUES (" + id + ", " + statementId + ", 960001, " + TEACHER
                + ", now(), now() + interval '1 hour', 30, 'RUNNING', now(), now())");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
