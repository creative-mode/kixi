package ao.creativemode.kixi.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.academic.service.ClassService;
import ao.creativemode.kixi.academic.service.CourseService;
import ao.creativemode.kixi.academic.service.SchoolYearService;
import ao.creativemode.kixi.academic.service.SubjectService;
import ao.creativemode.kixi.academic.service.TermService;
import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.identity.service.AccountService;
import ao.creativemode.kixi.identity.service.RoleService;
import ao.creativemode.kixi.identity.service.SessionService;
import ao.creativemode.kixi.identity.service.TeacherService;
import ao.creativemode.kixi.identity.service.UserService;
import ao.creativemode.kixi.institutions.service.EnrollmentService;
import ao.creativemode.kixi.institutions.service.InstitutionService;
import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The dashboard counts against a migrated database (issue #416): each number is the {@code length} of the list the
 * manager used to download, computed here with the very service calls the list endpoints make, for an ADMIN and for
 * a TEACHER. The database is shared with the other Postgres tests, so the numbers are compared, never assumed.
 *
 * <p>Ids from 883001, outside every range the other Postgres tests clean: one school, one year, two classes (the teacher is assigned to one of them, for one subject),
 * and statements inside and outside that scope.</p>
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
class DashboardCountsPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long ADMIN = 883001L;
    private static final long TEACHER = 883002L;

    @Autowired DashboardCountsService counts;
    @Autowired DatabaseClient database;
    @Autowired SchoolYearService schoolYears;
    @Autowired TermService terms;
    @Autowired SubjectService subjects;
    @Autowired CourseService courses;
    @Autowired ClassService classes;
    @Autowired RoleService roles;
    @Autowired AccountService accounts;
    @Autowired UserService users;
    @Autowired InstitutionService institutions;
    @Autowired TeacherService teachers;
    @Autowired EnrollmentService enrollments;
    @Autowired TeachingAssignmentService assignments;
    @Autowired SessionService sessions;
    @Autowired SimulationAnswerService answers;
    @Autowired StatementService statements;
    @Autowired StatementReadAccessService statementAccess;
    @Autowired SimulationService simulations;

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

    /** The rows of this test only: the other Postgres tests clean their own ranges, and none of them is this one. */
    private static final List<String> CLEANUP = List.of(
            "DELETE FROM statements WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM teaching_assignments WHERE teacher_id BETWEEN 883000 AND 883999",
            "DELETE FROM teachers WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM accounts WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM classes WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM courses WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM subjects WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM school_years WHERE id BETWEEN 883000 AND 883999",
            "DELETE FROM institutions WHERE id BETWEEN 883000 AND 883999");

    @AfterEach
    void cleanUp() {
        CLEANUP.forEach(this::sql);
    }

    @BeforeEach
    void data() {
        CLEANUP.forEach(this::sql);
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (883001, 'IT883', 'Escola 883', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (883001, 2141, 2142, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (883001, 'C883', 'Informática', 883001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(883001, '12A', 12, 883001, 883001, 883001, now(), now()), (883002, '12B', 12, 883001, 883001, 883001, now(), now())");
        // Two subjects active, one in the bin: the bin never counts.
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at, deleted_at) VALUES "
                + "(883001, 'R883', 'Redes', now(), now(), null), (883002, 'M883', 'Matemática', now(), now(), null), "
                + "(883003, 'X883', 'Apagada', now(), now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "SELECT i, 'dc' || i, 'dc' || i || '@kixi.test', 'x', true, true, now(), now() FROM generate_series(883001, 883002) i");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES (883011, 883002, 'Helena', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES (883011, 883001, 883001, 883001, now())");
        // In the teacher's scope: class 883001 with subject 883001 (one of them waiting for review), and a deleted one.
        // Outside it: the same class with another subject, and the other class.
        statement(883001, 883001L, 883001L, false, false);
        statement(883002, 883001L, 883001L, true, false);
        statement(883003, 883001L, 883002L, false, false);
        statement(883004, 883002L, null, true, false);
        statement(883005, 883001L, 883001L, false, true);
    }

    @Test
    void anAdminSeesTheLengthOfEveryListAndTheReviewQueue() {
        Map<String, Long> got = counts.countsFor(ADMIN, true, false).block(WAIT);

        Map<String, Long> lists = new LinkedHashMap<>();
        lists.put("school-years", count(schoolYears.findAllActive()));
        lists.put("terms", count(terms.findAllActive()));
        lists.put("subjects", count(subjects.findAllActive()));
        lists.put("courses", count(courses.findAllActive(null)));
        lists.put("classes", count(classes.findAllActive(null, null)));
        lists.put("roles", count(roles.findAllActive()));
        lists.put("accounts", count(accounts.findAllActive()));
        lists.put("users", count(users.findAllActive()));
        lists.put("institutions", count(institutions.findAllActive()));
        lists.put("teachers", count(teachers.findAllActive()));
        lists.put("enrollments", count(enrollments.findAllActive()));
        lists.put("teaching-assignments", count(assignments.findAllActive()));
        lists.put("sessions", count(sessions.findAllActive()));
        lists.put("simulation-answers", count(answers.findAllActiveForStaff(ADMIN, true)));
        lists.put("statements", count(statements.findAllActive()));
        lists.put("simulations", simulations.findAllAuthorized(ADMIN, true, false, null, null, null, null).block(WAIT).total());
        lists.put("review", count(statements.findNeedingReview()));

        assertThat(got).containsExactlyEntriesOf(lists);
    }

    @Test
    void aTeacherSeesTheLengthOfTheListsTheyMayReadAndNothingElse() {
        Map<String, Long> got = counts.countsFor(TEACHER, false, true).block(WAIT);

        var scope = statementAccess.teacherScope(TEACHER).block(WAIT);
        Map<String, Long> lists = new LinkedHashMap<>();
        lists.put("school-years", count(schoolYears.findAllActive()));
        lists.put("terms", count(terms.findAllActive()));
        lists.put("subjects", count(subjects.findAllActive()));
        lists.put("courses", count(courses.findAllActive(null)));
        lists.put("classes", count(classes.findAllActive(null, null)));
        lists.put("institutions", count(institutions.findAllActive()));
        lists.put("enrollments", count(enrollments.findVisible(TEACHER, false, true, null)));
        lists.put("simulation-answers", count(answers.findAllActiveForStaff(TEACHER, false)));
        lists.put("statements", count(statements.findAllActive().filter(scope::allows)));
        lists.put("simulations", simulations.findAllAuthorized(TEACHER, false, true, null, null, null, null).block(WAIT).total());
        lists.put("review", count(statements.findNeedingReview().filter(scope::allows)));

        assertThat(got).containsExactlyEntriesOf(lists);
        assertThat(got).doesNotContainKeys("roles", "accounts", "users", "teachers", "teaching-assignments", "sessions");
    }

    @Test
    void aStatementOutsideTheTeachersScopeCountsForTheAdminOnly() {
        Map<String, Long> adminBefore = counts.countsFor(ADMIN, true, false).block(WAIT);
        Map<String, Long> teacherBefore = counts.countsFor(TEACHER, false, true).block(WAIT);

        statement(883006, 883002L, 883001L, true, false); // the other class: not the teacher's
        statement(883007, 883001L, 883001L, true, false); // the teacher's class and subject

        Map<String, Long> adminAfter = counts.countsFor(ADMIN, true, false).block(WAIT);
        Map<String, Long> teacherAfter = counts.countsFor(TEACHER, false, true).block(WAIT);
        assertThat(adminAfter.get("statements") - adminBefore.get("statements")).isEqualTo(2L);
        assertThat(adminAfter.get("review") - adminBefore.get("review")).isEqualTo(2L);
        assertThat(teacherAfter.get("statements") - teacherBefore.get("statements")).isEqualTo(1L);
        assertThat(teacherAfter.get("review") - teacherBefore.get("review")).isEqualTo(1L);
    }

    @Test
    void whatIsInTheBinNeverCounts() {
        long before = counts.countsFor(ADMIN, true, false).block(WAIT).get("subjects");
        sql("UPDATE subjects SET deleted_at = now() WHERE id = 883002");
        assertThat(counts.countsFor(ADMIN, true, false).block(WAIT).get("subjects")).isEqualTo(before - 1);
    }

    private void statement(long id, Long classId, Long subjectId, boolean needsReview, boolean deleted) {
        sql("INSERT INTO statements (id, exam_type, title, visible, needs_review, class_id, subject_id, institution_id, "
                + "created_at, deleted_at) VALUES (" + id + ", 'P1', 'Prova " + id + "', false, " + needsReview + ", "
                + classId + ", " + subjectId + ", 883001, now(), " + (deleted ? "now()" : "null") + ")");
    }

    private static long count(Flux<?> list) {
        return list.count().block(WAIT);
    }

    private void sql(String statement) {
        database.sql(statement).then().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
