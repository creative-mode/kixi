package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The «Experiência» timeline and the private academic history, against a migrated database.
 *
 * <p>Ana (account 982001, user 982101) studied at school A in 2024/2025 (class 10A) and at school B in
 * 2025/2026 (class 11B). Bruno (982002) is another student at school B. Carla (982003) is a teacher
 * at both schools. Dino (982004) keeps his profile private. Everything uses ids from 982000 up.</p>
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
class ExperiencePostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long ANA = 982001L;
    private static final long BRUNO = 982002L;
    private static final long CARLA = 982003L;
    private static final long DINO = 982004L;
    private static final long NOBODY = 982005L;
    private static final long Y24 = 982301L;
    private static final long Y25 = 982302L;

    @Autowired ExperienceService service;
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

    @AfterEach
    void leaveNothingBehind() {
        clean();
    }

    @BeforeEach
    void world() {
        clean();
        populate();
    }

    private void clean() {
        String accounts = "982001,982002,982003,982004,982005";
        sql("DELETE FROM simulations WHERE account_id IN (" + accounts + ")");
        sql("DELETE FROM exam_rooms WHERE id BETWEEN 982800 AND 982899");
        sql("DELETE FROM statements WHERE id BETWEEN 982600 AND 982699");
        sql("DELETE FROM teaching_assignments WHERE id BETWEEN 982500 AND 982599");
        sql("DELETE FROM enrollments WHERE account_id IN (" + accounts + ")");
        sql("DELETE FROM user_profile_settings WHERE user_id BETWEEN 982101 AND 982199");
        sql("DELETE FROM institution_students WHERE institution_id BETWEEN 982201 AND 982299");
        sql("DELETE FROM classes WHERE id BETWEEN 982401 AND 982499");
        sql("DELETE FROM courses WHERE id BETWEEN 982201 AND 982299");
        sql("DELETE FROM institution_teachers WHERE institution_id BETWEEN 982201 AND 982299");
        sql("DELETE FROM teachers WHERE id BETWEEN 982101 AND 982199");
        sql("DELETE FROM users WHERE id BETWEEN 982101 AND 982199");
        sql("DELETE FROM institutions WHERE id BETWEEN 982201 AND 982299");
        sql("DELETE FROM subjects WHERE id BETWEEN 982701 AND 982799");
        sql("DELETE FROM school_years WHERE id IN (" + Y24 + "," + Y25 + ")");
        sql("DELETE FROM accounts WHERE id IN (" + accounts + ")");

    }

    private void populate() {
        for (long id : new long[] {ANA, BRUNO, CARLA, DINO, NOBODY}) {
            sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                    + "VALUES (" + id + ", 'exp-" + id + "', 'exp-" + id + "@example.com', 'x', true, true, now(), now())");
        }
        sql("INSERT INTO school_years (id, start_year, end_year) VALUES (" + Y24 + ", 3301, 3302), (" + Y25 + ", 3302, 3303)");
        for (long[] u : new long[][] {{982101, ANA}, {982102, BRUNO}, {982104, DINO}, {982105, NOBODY}}) {
            sql("INSERT INTO users (id, account_id, first_name, last_name) VALUES (" + u[0] + ", " + u[1] + ", 'U" + u[1] + "', 'Teste')");
        }
        sql("INSERT INTO users (id, account_id, first_name, last_name) VALUES (982103, " + CARLA + ", 'Carla', 'Prof')");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name) VALUES (982101, " + CARLA + ", 'Carla', 'Prof')");
        sql("INSERT INTO institutions (id, code, name) VALUES (982201, 'EXPA', 'Escola A'), (982202, 'EXPB', 'Escola B')");
        sql("INSERT INTO courses (id, code, name, institution_id) VALUES (982201, 'INF', 'Informática', 982201), (982202, 'INF', 'Informática', 982202)");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id) VALUES "
                + "(982401, '10A', 10, 982201, " + Y24 + ", 982201), (982402, '11B', 11, 982202, " + Y25 + ", 982202), "
                + "(982403, '11C', 11, 982202, " + Y25 + ", 982202)");
        sql("INSERT INTO subjects (id, code, name) VALUES (982701, 'EXPMAT', 'Matemática'), (982702, 'EXPFIS', 'Física')");
        sql("INSERT INTO institution_students (institution_id, user_id) VALUES (982201, 982101), (982202, 982101), (982202, 982102), (982202, 982104)");
        sql("INSERT INTO user_profile_settings (user_id, visibility) VALUES (982104, 'private')");
        sql("INSERT INTO institution_teachers (institution_id, teacher_id) VALUES (982201, 982101), (982202, 982101)");
    }

    @Test
    void aStudentInTwoSchoolsInTwoYearsGetsTwoEntriesNewestFirst() {
        enroll(ANA, 982401, Y24);
        enroll(ANA, 982402, Y25);

        ExperienceResponse r = service.experience(BRUNO, false, ANA).block(WAIT);

        assertThat(r.role()).isEqualTo("STUDENT");
        assertThat(r.restricted()).isFalse();
        assertThat(r.entries()).hasSize(2);
        assertThat(r.entries().get(0).schoolYear().label()).isEqualTo("3302/3303");
        assertThat(r.entries().get(0).institution().name()).isEqualTo("Escola B");
        assertThat(r.entries().get(0).course().name()).isEqualTo("Informática");
        assertThat(r.entries().get(0).classes()).extracting(c -> c.name()).containsExactly("11B");
        assertThat(r.entries().get(0).subjects()).isEmpty();
        assertThat(r.entries().get(1).institution().name()).isEqualTo("Escola A");
        assertThat(r.entries().get(1).classes().get(0).grade()).isEqualTo(10);
    }

    @Test
    void aTeacherWithTwoSchoolsGetsTheClassesAndSubjectsOfEachYear() {
        assign(982501, 982402, 982701, Y25);
        assign(982502, 982403, 982701, Y25);
        assign(982503, 982403, 982702, Y25);
        assign(982504, 982401, 982701, Y24);

        ExperienceResponse r = service.experience(ANA, false, CARLA).block(WAIT);

        assertThat(r.role()).isEqualTo("TEACHER");
        assertThat(r.entries()).hasSize(2);
        assertThat(r.entries().get(0).institution().name()).isEqualTo("Escola B");
        assertThat(r.entries().get(0).classes()).extracting(c -> c.name()).containsExactly("11B", "11C");
        assertThat(r.entries().get(0).subjects()).extracting(s -> s.name()).containsExactly("Física", "Matemática");
        assertThat(r.entries().get(1).institution().name()).isEqualTo("Escola A");
        assertThat(r.entries().get(1).subjects()).extracting(s -> s.name()).containsExactly("Matemática");
    }

    @Test
    void aPersonWithoutSchoolingGetsAnEmptyTimeline() {
        assertThat(service.experience(ANA, false, NOBODY).block(WAIT).entries()).isEmpty();
    }

    @Test
    void aPrivateProfileShowsNoTimelineToOthersButShowsItToItsOwnerAndAdmins() {
        enroll(DINO, 982402, Y25);

        ExperienceResponse others = service.experience(ANA, false, DINO).block(WAIT);
        assertThat(others.restricted()).isTrue();
        assertThat(others.entries()).isEmpty();

        assertThat(service.experience(DINO, false, DINO).block(WAIT).entries()).hasSize(1);
        assertThat(service.experience(ANA, true, DINO).block(WAIT).entries()).hasSize(1);
    }

    @Test
    void theUnknownPersonIsNotFound() {
        assertThatThrownBy(() -> service.experience(ANA, false, 989999L).block(WAIT)).isInstanceOf(ApiException.class);
    }

    @Test
    void theHistoryOfAYearHoldsOnlyThatYearsFinishedResultsOfTheCaller() {
        enroll(ANA, 982401, Y24);
        enroll(ANA, 982402, Y25);
        enroll(BRUNO, 982402, Y25);
        statement(982601, 982701, "Teste de Matemática", 20, Y25);
        statement(982602, 982702, "Teste de Física", 20, Y25);
        statement(982603, 982701, "Exame de 3301", 20, Y24);
        simulation(ANA, 982601, null, 10, "FINISHED", null);
        simulation(ANA, 982601, null, 15, "FINISHED", 1L);
        simulation(ANA, 982602, null, 20, "FINISHED", null);
        simulation(ANA, 982601, null, 5, "IN_PROGRESS", null);
        simulation(ANA, 982603, Y24, 8, "FINISHED", null);
        simulation(BRUNO, 982601, null, 1, "FINISHED", null);

        AcademicHistoryResponse h = service.historyOf(ANA, Y25).block(WAIT);

        assertThat(h.schoolYear().label()).isEqualTo("3302/3303");
        assertThat(h.institution().name()).isEqualTo("Escola B");
        assertThat(h.schoolClass().name()).isEqualTo("11B");
        assertThat(h.totals().simulations()).isEqualTo(3);
        assertThat(h.totals().exams()).isEqualTo(1);
        assertThat(h.subjects()).hasSize(2);
        var math = h.subjects().stream().filter(s -> s.subject().equals("Matemática")).findFirst().orElseThrow();
        assertThat(math.simulations()).isEqualTo(2);
        assertThat(math.average()).isEqualTo(62.5);
        assertThat(math.best()).isEqualTo(75.0);
        assertThat(h.exams()).hasSize(1);
        assertThat(h.exams().get(0).score()).isEqualTo(15.0);
        assertThat(h.exams().get(0).max()).isEqualTo(20.0);

        AcademicHistoryResponse old = service.historyOf(ANA, Y24).block(WAIT);
        assertThat(old.totals().simulations()).isEqualTo(1);
        assertThat(old.institution().name()).isEqualTo("Escola A");
    }

    @Test
    void theHistoryIsNeverAnotherAccountsOrAYearTheCallerDidNotAttend() {
        enroll(ANA, 982401, Y24);
        enroll(BRUNO, 982402, Y25);
        statement(982601, 982701, "Teste", 20, Y25);
        simulation(BRUNO, 982601, null, 20, "FINISHED", null);

        assertThatThrownBy(() -> service.historyOf(ANA, Y25).block(WAIT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.historyOf(CARLA, Y25).block(WAIT)).isInstanceOf(ApiException.class);
        assertThat(service.historyOf(BRUNO, Y25).block(WAIT).totals().simulations()).isEqualTo(1);
    }

    @Test
    void aYearWithoutResultsHasEmptyLists() {
        enroll(ANA, 982402, Y25);

        AcademicHistoryResponse h = service.historyOf(ANA, Y25).block(WAIT);

        assertThat(h.subjects()).isEmpty();
        assertThat(h.exams()).isEmpty();
        assertThat(h.totals().simulations()).isZero();
        assertThat(h.totals().average()).isNull();
    }

    @Test
    void anOldResultWithNoYearFallsBackToTheSchoolYearRunningWhenItFinished() {
        enroll(ANA, 982401, Y24);
        enroll(ANA, 982402, Y25);
        sql("INSERT INTO statements (id, title, subject_id, total_max_score) VALUES (982610, 'Antigo', 982701, 20)");
        oldResult(ANA, 982610, 10, "3301-10-15 10:00:00+00");
        oldResult(ANA, 982610, 12, "3302-03-01 10:00:00+00");
        oldResult(ANA, 982610, 16, "3302-10-01 10:00:00+00");

        assertThat(service.historyOf(ANA, Y24).block(WAIT).totals().simulations()).isEqualTo(2);
        assertThat(service.historyOf(ANA, Y25).block(WAIT).totals().simulations()).isEqualTo(1);
    }

    @Test
    void aResultWithItsOwnYearIsNotMovedByTheFinishDate() {
        enroll(ANA, 982401, Y24);
        statement(982601, 982701, "Teste", 20, Y24);
        sql("INSERT INTO simulations (account_id, statement_id, school_year_id, final_score, status, finished_at) VALUES ("
                + ANA + ", 982601, " + Y24 + ", 10, 'FINISHED', '3302-10-01 10:00:00+00')");

        assertThat(service.historyOf(ANA, Y24).block(WAIT).totals().simulations()).isEqualTo(1);
    }

    private void oldResult(long account, long statement, double score, String finishedAt) {
        sql("INSERT INTO simulations (account_id, statement_id, final_score, status, finished_at) VALUES ("
                + account + "," + statement + "," + score + ",'FINISHED','" + finishedAt + "')");
    }

    private void enroll(long account, long classId, long year) {
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id, status) VALUES (" + account + "," + classId + "," + year + ",'ACTIVE')");
    }

    private void assign(long id, long classId, long subject, long year) {
        sql("INSERT INTO teaching_assignments (id, teacher_id, class_id, subject_id, school_year_id) VALUES ("
                + id + ", 982101, " + classId + ", " + subject + ", " + year + ")");
    }

    private void statement(long id, long subject, String title, int max, long year) {
        sql("INSERT INTO statements (id, title, subject_id, total_max_score, school_year_id) VALUES ("
                + id + ", '" + title + "', " + subject + ", " + max + ", " + year + ")");
    }

    private void simulation(long account, long statement, Long year, double score, String status, Long room) {
        sql("INSERT INTO simulations (account_id, statement_id, school_year_id, final_score, status, finished_at) VALUES ("
                + account + "," + statement + "," + (year == null ? "NULL" : year) + "," + score + ",'" + status + "', now())");
        if (room != null) {
            sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes) VALUES ("
                    + (982800 + room) + ", " + statement + ", " + CARLA + ", now() - interval '2 hours', now() - interval '1 hour', 60) "
                    + "ON CONFLICT (id) DO NOTHING");
            sql("UPDATE simulations SET exam_room_id = " + (982800 + room) + ", exam_room_duration_minutes = 60 "
                    + "WHERE id = (SELECT max(id) FROM simulations WHERE account_id = " + account + ")");
        }
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
