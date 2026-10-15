package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.academic.dto.classe.ClassRequest;
import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectRequest;
import ao.creativemode.kixi.academic.dto.courses.CourseRequest;
import ao.creativemode.kixi.academic.service.ClassService;
import ao.creativemode.kixi.academic.service.CourseService;
import ao.creativemode.kixi.academic.service.CurriculumService;
import ao.creativemode.kixi.academic.service.SubjectService;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentRequest;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse;
import ao.creativemode.kixi.institutions.dto.structure.SubjectOfferingsResponse;
import ao.creativemode.kixi.institutions.dto.structure.TeacherOverviewResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
 * The academic structure against a migrated database (issue #253): V50 migrates and backfills, a
 * course code is unique per school and not across them, the curriculum is unique, and the whole
 * flow school → course → class → curriculum → teacher in two schools produces the tree and the
 * overview that were asked for.
 *
 * <p>Ids from 990001 and school year 2290-2291, apart from the other Postgres tests' blocks.</p>
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
class AcademicStructurePostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long SCHOOL_A = 990001L;
    private static final long SCHOOL_B = 990002L;
    private static final long SCHOOL_C = 990003L;
    private static final long TEACHER = 990011L;
    private static final long MAT = 990021L;
    private static final long FIS = 990022L;
    private static final long YEAR = 990031L;

    @Autowired CourseService courses;
    @Autowired ClassService classes;
    @Autowired SubjectService subjects;
    @Autowired CurriculumService curriculum;
    @Autowired TeachingAssignmentService assignments;
    @Autowired InstitutionMembershipService membership;
    @Autowired InstitutionService institutions;
    @Autowired AcademicOverviewService overview;
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
        clean();
        sql("INSERT INTO school_years (id, start_year, end_year) VALUES (" + YEAR + ", 2290, 2291)");
        for (long school : new long[] {SCHOOL_A, SCHOOL_B, SCHOOL_C}) {
            sql("INSERT INTO institutions (id, code, name) VALUES (" + school + ", 'T253-" + school
                    + "', 'Escola " + school + "')");
        }
        sql("INSERT INTO subjects (id, code, name) VALUES (" + MAT + ", 'T253MAT', 'Matemática 253')");
        sql("INSERT INTO subjects (id, code, name) VALUES (" + FIS + ", 'T253FIS', 'Física 253')");
        sql("INSERT INTO teachers (id, first_name, last_name, email) VALUES (" + TEACHER
                + ", 'Rita', 'Prof', 'rita253@example.com')");
    }

    @AfterEach
    void clean() {
        String schools = "BETWEEN 990001 AND 990009";
        sql("DELETE FROM teaching_assignments WHERE teacher_id = " + TEACHER);
        sql("DELETE FROM classes WHERE institution_id " + schools);
        sql("DELETE FROM course_subjects WHERE course_id IN (SELECT id FROM courses WHERE institution_id " + schools + ")");
        sql("DELETE FROM courses WHERE institution_id " + schools);
        sql("DELETE FROM institution_subjects WHERE institution_id " + schools);
        sql("DELETE FROM institution_teachers WHERE institution_id " + schools);
        sql("DELETE FROM institutions WHERE id " + schools);
        sql("DELETE FROM teachers WHERE id = " + TEACHER);
        sql("DELETE FROM subjects WHERE id IN (" + MAT + ", " + FIS + ")");
        sql("DELETE FROM school_years WHERE id = " + YEAR);
    }

    // ── V50 ─────────────────────────────────────────────────────────────────

    @Test
    void theBackfillGivesEachActiveCourseTheSubjectsItsSchoolAlreadyHadAndRunsTwiceHarmlessly() throws IOException {
        sql("INSERT INTO courses (code, name, institution_id) VALUES ('OLD', 'Curso antigo', " + SCHOOL_C + ")");
        sql("INSERT INTO institution_subjects (institution_id, subject_id) VALUES (" + SCHOOL_C + ", " + MAT + ")");
        sql("INSERT INTO institution_subjects (institution_id, subject_id, deleted_at) VALUES ("
                + SCHOOL_C + ", " + FIS + ", now())");

        String migration = new String(getClass().getResourceAsStream("/db/migration/V50__add_course_curriculum.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        String backfill = migration.substring(migration.indexOf("INSERT INTO course_subjects"));
        sql(backfill);
        sql(backfill);

        List<String> rows = database.sql("SELECT s.code || ':' || COALESCE(cs.grade::text, 'all') AS entry "
                        + "FROM course_subjects cs JOIN courses c ON c.id = cs.course_id "
                        + "JOIN subjects s ON s.id = cs.subject_id WHERE c.institution_id = " + SCHOOL_C)
                .map(row -> row.get("entry", String.class)).all().collectList().block(WAIT);
        assertThat(rows).as("the active catalog subject only, for every grade, once").containsExactly("T253MAT:all");
    }

    // ── Course codes per school ─────────────────────────────────────────────

    @Test
    void theSameCourseCodeLivesInTwoSchoolsButNotTwiceInOne() {
        courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT);
        courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_B)).block(WAIT);

        assertThatThrownBy(() -> courses.create(new CourseRequest("INF", "Outra", null, SCHOOL_A)).block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(409));
        assertThatThrownBy(() -> sql("INSERT INTO courses (code, name, institution_id) VALUES ('INF', 'x', " + SCHOOL_A + ")"))
                .as("the database refuses it too, not only the service").hasMessageContaining("uk_courses_institution_code");
    }

    @Test
    void aRemovedCourseReleasesItsCodeAndRestoringItAfterTheCodeWasReusedIsAConflict() {
        Long first = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT).id();
        courses.softDelete(first).block(WAIT);
        courses.create(new CourseRequest("INF", "Informática nova", null, SCHOOL_A)).block(WAIT);

        assertThatThrownBy(() -> courses.restore(first).block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(409));
    }

    // ── Curriculum ──────────────────────────────────────────────────────────

    @Test
    void theCurriculumIsUniquePerCourseSubjectAndGradeAndPutsTheSubjectInTheSchoolCatalog() {
        Long course = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT).id();

        curriculum.add(course, new CourseSubjectRequest(MAT, 12)).block(WAIT);
        assertThat(count("SELECT count(*) FROM institution_subjects WHERE deleted_at IS NULL AND institution_id = "
                + SCHOOL_A + " AND subject_id = " + MAT)).as("the catalog got it").isEqualTo(1);

        assertThatThrownBy(() -> curriculum.add(course, new CourseSubjectRequest(MAT, 12)).block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(409));
        assertThatThrownBy(() -> sql("INSERT INTO course_subjects (course_id, subject_id, grade) VALUES ("
                + course + ", " + MAT + ", 12)"))
                .as("and so does the index").hasMessageContaining("uk_course_subjects_active");

        Long link = curriculum.listForCourse(course).collectList().block(WAIT).get(0).id();
        curriculum.remove(course, link).block(WAIT);
        assertThat(count("SELECT count(*) FROM institution_subjects WHERE deleted_at IS NULL AND institution_id = "
                + SCHOOL_A + " AND subject_id = " + MAT)).as("leaving the curriculum never leaves the catalog").isEqualTo(1);
    }

    // ── The whole flow ──────────────────────────────────────────────────────

    @Test
    void oneTeacherInTwoSchoolsWithTheSameCourseCodeShowsUpInTheTreeAndInTheOverview() {
        Long courseA = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT).id();
        Long courseB = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_B)).block(WAIT).id();
        Long classA = classes.create(new ClassRequest("12A", 12, courseA, YEAR)).block(WAIT).id();
        Long classB = classes.create(new ClassRequest("12B", 12, courseB, YEAR)).block(WAIT).id();
        curriculum.add(courseA, new CourseSubjectRequest(MAT, null)).block(WAIT);
        curriculum.add(courseA, new CourseSubjectRequest(FIS, 12)).block(WAIT);
        curriculum.add(courseB, new CourseSubjectRequest(MAT, null)).block(WAIT);

        membership.addTeacher(SCHOOL_A, TEACHER).block(WAIT);
        membership.addTeacher(SCHOOL_B, TEACHER).block(WAIT);
        membership.addTeacher(SCHOOL_C, TEACHER).block(WAIT);
        Long inA = assignments.create(new TeachingAssignmentRequest(TEACHER, classA, MAT, YEAR, null)).block(WAIT).id();
        assignments.create(new TeachingAssignmentRequest(TEACHER, classB, MAT, YEAR, null)).block(WAIT);
        assignments.create(new TeachingAssignmentRequest(TEACHER, classA, FIS, YEAR, null)).block(WAIT);

        InstitutionStructureResponse tree = overview.structure(SCHOOL_A, null).block(WAIT);
        assertThat(tree.schoolYear().id()).isEqualTo(YEAR);
        assertThat(tree.courses()).hasSize(1);
        assertThat(tree.courses().get(0).curriculum()).extracting(InstitutionStructureResponse.CurriculumEntry::code)
                .containsExactlyInAnyOrder("T253FIS", "T253MAT");
        var klass = tree.courses().get(0).classes().get(0);
        assertThat(klass.code()).isEqualTo("12A");
        assertThat(klass.subjects()).extracting(s -> s.code()).containsExactly("T253FIS", "T253MAT");
        assertThat(klass.subjects()).allSatisfy(s -> assertThat(s.teachers()).extracting(t -> t.id()).containsExactly(TEACHER));
        assertThat(tree.teachers()).hasSize(1);
        assertThat(tree.teachers().get(0).assignments()).isEqualTo(2);
        assertThat(tree.counts().courses()).isEqualTo(1);
        assertThat(tree.counts().classes()).isEqualTo(1);
        assertThat(tree.counts().subjects()).isEqualTo(2);
        assertThat(tree.counts().teachers()).isEqualTo(1);
        assertThat(tree.warnings()).isEmpty();

        InstitutionStructureResponse treeB = overview.structure(SCHOOL_B, null).block(WAIT);
        assertThat(treeB.courses().get(0).classes().get(0).subjects()).extracting(s -> s.code()).containsExactly("T253MAT");

        TeacherOverviewResponse mine = overview.teacherOverview(TEACHER).block(WAIT);
        assertThat(mine.institutions()).extracting(TeacherOverviewResponse.InstitutionNode::id)
                .as("both schools with assignments, and the third one the teacher belongs to without any")
                .containsExactlyInAnyOrder(SCHOOL_A, SCHOOL_B, SCHOOL_C);
        assertThat(mine.institutions().stream().filter(i -> i.id() == SCHOOL_A).findFirst().orElseThrow().assignments()).hasSize(2);
        assertThat(mine.institutions().stream().filter(i -> i.id() == SCHOOL_B).findFirst().orElseThrow().assignments()).hasSize(1);
        assertThat(mine.institutions().stream().filter(i -> i.id() == SCHOOL_C).findFirst().orElseThrow().assignments()).isEmpty();

        SubjectOfferingsResponse offerings = overview.subjectOfferings("T253MAT").block(WAIT);
        assertThat(offerings.institutions()).extracting(SubjectOfferingsResponse.InstitutionNode::id)
                .containsExactlyInAnyOrder(SCHOOL_A, SCHOOL_B);
        assertThat(overview.subjectOfferings("T253FIS").block(WAIT).institutions()).hasSize(1);

        assertThat(institutions.findAllActiveWithClassesInYear(YEAR).collectList().block(WAIT))
                .extracting(i -> i.id()).containsExactlyInAnyOrder(SCHOOL_A, SCHOOL_B);

        // Deleting what has dependents is refused, and says so.
        assertConflict(() -> courses.softDelete(courseA).block(WAIT));
        assertConflict(() -> classes.softDelete(classA).block(WAIT));
        assertConflict(() -> subjects.softDelete("T253MAT").block(WAIT));
        assertConflict(() -> institutions.softDelete(SCHOOL_A).block(WAIT));
        Long matLink = curriculum.listForCourse(courseA).collectList().block(WAIT).stream()
                .filter(l -> l.subjectId() == MAT).findFirst().orElseThrow().id();
        assertConflict(() -> curriculum.remove(courseA, matLink).block(WAIT));
        assertConflict(() -> membership.removeSubject(SCHOOL_A, MAT).block(WAIT));

        // And once the assignment is gone the entry can go.
        assignments.softDelete(inA).block(WAIT);
        curriculum.remove(courseA, matLink).block(WAIT);
    }

    @Test
    void anAssignmentNeedsAnAffiliatedTeacherAndASubjectOfTheCurriculum() {
        Long courseA = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT).id();
        Long classA = classes.create(new ClassRequest("12A", 12, courseA, YEAR)).block(WAIT).id();
        curriculum.add(courseA, new CourseSubjectRequest(MAT, null)).block(WAIT);

        assertUnprocessable(() -> assignments
                .create(new TeachingAssignmentRequest(TEACHER, classA, MAT, YEAR, null)).block(WAIT));

        membership.addTeacher(SCHOOL_A, TEACHER).block(WAIT);
        assertUnprocessable(() -> assignments
                .create(new TeachingAssignmentRequest(TEACHER, classA, FIS, YEAR, null)).block(WAIT));

        assertThat(assignments.create(new TeachingAssignmentRequest(TEACHER, classA, MAT, YEAR, null)).block(WAIT).id())
                .isNotNull();
        assertThat(overview.classSubjects(classA, false).block(WAIT)).extracting(s -> s.code()).containsExactly("T253MAT");
        assertThat(overview.classSubjects(classA, false).block(WAIT).get(0).teachers()).as("students do not see who teaches").isEmpty();
        assertThat(overview.classSubjects(classA, true).block(WAIT).get(0).teachers()).hasSize(1);
    }

    @Test
    void aClassWithPeopleCannotMoveToAnotherCourse() {
        Long courseA = courses.create(new CourseRequest("INF", "Informática", null, SCHOOL_A)).block(WAIT).id();
        Long other = courses.create(new CourseRequest("ELE", "Electrónica", null, SCHOOL_A)).block(WAIT).id();
        Long classA = classes.create(new ClassRequest("12A", 12, courseA, YEAR)).block(WAIT).id();
        curriculum.add(courseA, new CourseSubjectRequest(MAT, null)).block(WAIT);
        membership.addTeacher(SCHOOL_A, TEACHER).block(WAIT);
        assignments.create(new TeachingAssignmentRequest(TEACHER, classA, MAT, YEAR, null)).block(WAIT);

        assertConflict(() -> classes.update(classA, new ClassRequest("12A", 12, other, YEAR)).block(WAIT));
        assertThat(classes.update(classA, new ClassRequest("12A-bis", 12, courseA, YEAR)).block(WAIT).code())
                .as("code and grade can still change").isEqualTo("12A-bis");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(409));
    }

    private static void assertUnprocessable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(422));
    }

    private long count(String query) {
        return database.sql(query).map(row -> row.get(0, Long.class)).one().block(WAIT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
