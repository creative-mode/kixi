package ao.creativemode.kixi.issue122;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import ao.creativemode.kixi.identity.service.JwtService;

/** Focused issue #122 flows not covered by the existing critical exam test. */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@org.springframework.context.annotation.Import(EnrollmentExamRoomPostgresIntegrationTest.EventProbe.class)
class EnrollmentExamRoomPostgresIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class EventProbe {
        final java.util.Queue<Long> closed = new java.util.concurrent.ConcurrentLinkedQueue<>();

        @org.springframework.context.event.EventListener
        void onClosed(ao.creativemode.kixi.shared.event.ExamRoomClosed event) {
            closed.add(event.roomId());
        }
    }
    private static final long ADMIN = 122101L;
    private static final long OWNER = 122102L;
    private static final long OTHER_TEACHER = 122103L;
    private static final long STUDENT = 122104L;
    private static final long OTHER_STUDENT = 122105L;
    private static final long INSTITUTION = 122101L;
    private static final long YEAR = 122101L;
    private static final long COURSE = 122101L;
    private static final long CLASS = 122101L;
    private static final long STATEMENT = 122101L;
    private static final long OTHER_INSTITUTION = 122102L;
    private static final long OTHER_YEAR = 122102L;
    private static final long OTHER_COURSE = 122102L;
    private static final long OTHER_CLASS = 122102L;
    private static final long SUBJECT = 122101L;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private java.time.Clock clock;
    @Autowired
    private ao.creativemode.kixi.examrooms.service.ExamRoomExpirationJob roomExpirationJob;
    @Autowired
    private ao.creativemode.kixi.examrooms.service.ExamRoomService roomService;
    @Autowired
    private ao.creativemode.kixi.simulations.repository.SimulationRepository simulationRepository;
    @Autowired
    private ao.creativemode.kixi.simulations.service.SimulationDeadlineService simulationDeadlineService;
    @Autowired
    private EventProbe events;

    private WebTestClient client;
    private String adminToken;
    private String ownerToken;
    private String otherTeacherToken;
    private String studentToken;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("EnrollmentExamRoomPostgresIntegrationTest requires PostgreSQL");
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
    }

    @BeforeEach
    void fixture() {
        events.closed.clear();
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE account_id IN ("
                + ADMIN + "," + OWNER + "," + OTHER_TEACHER + "," + STUDENT + "," + OTHER_STUDENT + "))");
        sql("UPDATE exam_room_participants SET simulation_id = NULL WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM simulations WHERE account_id IN (" + ADMIN + "," + OWNER + "," + OTHER_TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM exam_room_participants WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM exam_rooms WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM enrollments WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        // Must run before the accounts delete below: enrollment_codes.created_by_account_id is
        // NOT NULL with ON DELETE SET NULL, so deleting an account that still has a code
        // referencing it as creator throws a NOT NULL violation instead of cascading.
        sql("DELETE FROM enrollment_codes WHERE class_id = " + CLASS);
        sql("DELETE FROM teaching_assignments WHERE class_id IN (" + CLASS + "," + OTHER_CLASS + ")");
        sql("DELETE FROM institution_teachers WHERE institution_id IN (" + INSTITUTION + "," + OTHER_INSTITUTION + ")");
        sql("DELETE FROM teachers WHERE account_id IN (" + OWNER + "," + OTHER_TEACHER + ")");
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + ADMIN + "," + OWNER + "," + OTHER_TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ADMIN + "," + OWNER + "," + OTHER_TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM classes WHERE id IN (" + CLASS + "," + OTHER_CLASS + ")");
        sql("DELETE FROM courses WHERE id IN (" + COURSE + "," + OTHER_COURSE + ")");
        sql("DELETE FROM school_years WHERE id IN (" + YEAR + "," + OTHER_YEAR + ")");
        sql("DELETE FROM institutions WHERE id IN (" + INSTITUTION + "," + OTHER_INSTITUTION + ")");
        sql("DELETE FROM subjects WHERE id = " + SUBJECT);

        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'I122', 'Issue 122', now(), now())");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + OTHER_INSTITUTION + ", 'I360B', 'Issue 360 B', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (" + YEAR + ", 2122, 2123, now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (" + OTHER_YEAR + ", 2324, 2325, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (" + COURSE + ", 'C122', 'Curso 122', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (" + OTHER_COURSE + ", 'C122B', 'Curso 122 B', " + OTHER_INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES (" + CLASS + ", '12A', 12, " + COURSE + ", " + YEAR + ", " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES (" + OTHER_CLASS + ", '12B', 12, " + OTHER_COURSE + ", " + OTHER_YEAR + ", " + OTHER_INSTITUTION + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (" + SUBJECT + ", 'SUB122', 'Subject 122', now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES "
                + "(" + ADMIN + ", 'admin122', 'admin122@kixi.test', 'x', true, true),"
                + "(" + OWNER + ", 'owner122', 'owner122@kixi.test', 'x', true, true),"
                + "(" + OTHER_TEACHER + ", 'teacher122', 'teacher122@kixi.test', 'x', true, true),"
                + "(" + STUDENT + ", 'student122', 'student122@kixi.test', 'x', true, true),"
                + "(" + OTHER_STUDENT + ", 'student-other122', 'student-other122@kixi.test', 'x', true, true)");
        role(ADMIN, "ADMIN");
        role(OWNER, "TEACHER");
        role(OTHER_TEACHER, "TEACHER");
        role(STUDENT, "STUDENT");
        role(OTHER_STUDENT, "STUDENT");
        role(OTHER_STUDENT, "INTEGRATION");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name) VALUES "
                + "(" + OWNER + ", " + OWNER + ", 'Owner', 'Teacher'),"
                + "(" + OTHER_TEACHER + ", " + OTHER_TEACHER + ", 'Other', 'Teacher')");
        sql("INSERT INTO institution_teachers (institution_id, teacher_id) VALUES "
                + "(" + INSTITUTION + ", " + OWNER + "), (" + OTHER_INSTITUTION + ", " + OTHER_TEACHER + ")");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id) VALUES "
                + "(" + OWNER + ", " + CLASS + ", " + SUBJECT + ", " + YEAR + "),"
                + "(" + OTHER_TEACHER + ", " + OTHER_CLASS + ", " + SUBJECT + ", " + OTHER_YEAR + ")");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, class_id, school_year_id, visible, needs_review, source) VALUES ("
                + STATEMENT + ", 'Issue 122', 'TEST', 60, 1, " + OWNER + ", " + CLASS + ", " + YEAR + ", false, false, 'manual')");

        adminToken = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        ownerToken = jwtService.generateToken(OWNER, List.of("TEACHER"));
        otherTeacherToken = jwtService.generateToken(OTHER_TEACHER, List.of("TEACHER"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
    }

    @Test
    void enrollmentApiEnforcesOwnershipCancellationAndRestoration() {
        // Since #255 a self-enroll needs a code; this one is good for two uses, enough
        // for the enroll below and the restore-after-cancel further down.
        String code = issueEnrollmentCode(CLASS, 2).code();

        AtomicLong ownEnrollment = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"" + code + "\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.accountId").isEqualTo((int) STUDENT)
                .jsonPath("$.classId").isEqualTo((int) CLASS).jsonPath("$.schoolYearId").isEqualTo((int) YEAR)
                .jsonPath("$.id").value(value -> ownEnrollment.set(((Number) value).longValue()));

        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + OTHER_STUDENT + ",\"code\":\"" + code + "\"}")
                .exchange().expectStatus().isForbidden();

        AtomicLong otherEnrollment = new AtomicLong();
        bearer(adminToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + OTHER_STUDENT + ",\"classId\":" + CLASS + ",\"schoolYearId\":" + YEAR + "}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> otherEnrollment.set(((Number) value).longValue()));

        bearer(studentToken).delete().uri("/api/v1/enrollments/" + ownEnrollment).exchange().expectStatus().isNoContent();
        assertThat(one("SELECT count(*) FROM enrollments WHERE id = " + ownEnrollment + " AND deleted_at IS NOT NULL")).isEqualTo(1L);

        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"" + code + "\"}")
                .exchange().expectStatus().isCreated();
        assertThat(one("SELECT count(*) FROM enrollments WHERE id = " + ownEnrollment + " AND deleted_at IS NULL AND status = 'ACTIVE'")).isEqualTo(1L);
        assertThat(one("SELECT count(*) FROM enrollments WHERE id = " + otherEnrollment + " AND deleted_at IS NULL")).isEqualTo(1L);
    }

    @Test
    void teacherEnrollmentAndVisibilityAreScopedToInstitutionAndClassAssignments() {
        bearer(ownerToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + OTHER_STUDENT + ",\"classId\":" + OTHER_CLASS
                        + ",\"schoolYearId\":" + OTHER_YEAR + "}")
                .exchange().expectStatus().isNotFound();
        assertThat(one("SELECT count(*) FROM enrollments WHERE account_id = " + OTHER_STUDENT)).isEqualTo(0L);

        AtomicLong permittedEnrollment = new AtomicLong();
        bearer(ownerToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + OTHER_STUDENT + ",\"classId\":" + CLASS
                        + ",\"schoolYearId\":" + YEAR + "}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> permittedEnrollment.set(((Number) value).longValue()));
        bearer(ownerToken).get().uri("/api/v1/enrollments?accountId=" + OTHER_STUDENT)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);

        bearer(ownerToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"classId\":" + CLASS
                        + ",\"schoolYearId\":" + YEAR + "}")
                .exchange().expectStatus().isCreated();
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id) VALUES ("
                + STUDENT + ", " + OTHER_CLASS + ", " + OTHER_YEAR + ")");
        bearer(ownerToken).get().uri("/api/v1/enrollments?accountId=" + STUDENT)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].classId").isEqualTo((int) CLASS);

        bearer(ownerToken).delete().uri("/api/v1/enrollments/" + one("SELECT id FROM enrollments WHERE account_id = "
                        + STUDENT + " AND class_id = " + OTHER_CLASS))
                .exchange().expectStatus().isNotFound();
        assertThat(one("SELECT count(*) FROM enrollments WHERE account_id = " + STUDENT
                + " AND class_id = " + OTHER_CLASS + " AND deleted_at IS NULL")).isEqualTo(1L);

        bearer(ownerToken).delete().uri("/api/v1/enrollments/" + permittedEnrollment.get())
                .exchange().expectStatus().isNoContent();
        assertThat(one("SELECT count(*) FROM enrollments WHERE id = " + permittedEnrollment.get()
                + " AND deleted_at IS NOT NULL")).isEqualTo(1L);
    }

    @Test
    void selfEnrollNowRequiresAValidCodeNotJustAClassId() {
        // This is the heart of #255: before the fix, this exact body enrolled the student.
        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"classId\":" + CLASS + ",\"schoolYearId\":" + YEAR + "}")
                .exchange().expectStatus().isBadRequest();
        assertThat(one("SELECT count(*) FROM enrollments WHERE account_id = " + STUDENT)).isEqualTo(0L);
    }

    @Test
    void anAlreadyUsedCodeCannotBeRedeemedByAnotherStudent() {
        String singleUseCode = issueEnrollmentCode(CLASS, 1).code();
        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"" + singleUseCode + "\"}")
                .exchange().expectStatus().isCreated();

        String otherStudentToken = jwtService.generateToken(OTHER_STUDENT, List.of("STUDENT"));
        bearer(otherStudentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"" + singleUseCode + "\"}")
                .exchange().expectStatus().isBadRequest();
        assertThat(one("SELECT count(*) FROM enrollments WHERE account_id = " + OTHER_STUDENT)).isEqualTo(0L);
    }

    @Test
    void aRevokedCodeCannotBeRedeemed() {
        EnrollmentCodeFixture fixture = issueEnrollmentCode(CLASS, 5);
        bearer(adminToken).delete().uri("/api/v1/enrollment-codes/" + fixture.id()).exchange().expectStatus().isOk();

        bearer(studentToken).post().uri("/api/v1/enrollments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"" + fixture.code() + "\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void onlyAdminCanIssueEnrollmentCodes() {
        bearer(ownerToken).post().uri("/api/v1/enrollment-codes").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"classId\":" + CLASS + "}")
                .exchange().expectStatus().isForbidden();
        bearer(studentToken).post().uri("/api/v1/enrollment-codes").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"classId\":" + CLASS + "}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void enrollmentUniquenessIsEnforcedByPostgresql() {
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id) VALUES (" + STUDENT + ", " + CLASS + ", " + YEAR + ")");
        assertThatThrownBy(() -> sql("INSERT INTO enrollments (account_id, class_id, school_year_id) VALUES ("
                + STUDENT + ", " + CLASS + ", " + YEAR + ")"))
                .hasMessageContaining("uq_enrollments_account_school_year_active");
    }

    @Test
    void ownerManagesRoomOtherTeacherAndStudentAreForbiddenAndAdminClosesIt() {
        // The student must be enrolled before the real invitation API can validate the class roll.
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id) VALUES (" + STUDENT + ", " + CLASS + ", " + YEAR + ")");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        AtomicLong roomId = new AtomicLong();
        bearer(ownerToken).post().uri("/api/v1/exam-rooms").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"statementId\":" + STATEMENT + ",\"classId\":" + CLASS
                        + ",\"startsAt\":\"" + now.minusMinutes(1) + "\",\"endsAt\":\"" + now.plusHours(1)
                        + "\",\"durationMinutes\":60}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> roomId.set(((Number) value).longValue()));
        long room = roomId.get();

        bearer(otherTeacherToken).post().uri("/api/v1/exam-rooms/" + room + "/participants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"accountId\":" + STUDENT + "}")
                .exchange().expectStatus().isForbidden();
        bearer(otherTeacherToken).post().uri("/api/v1/exam-rooms/" + room + "/open").exchange().expectStatus().isForbidden();
        bearer(otherTeacherToken).post().uri("/api/v1/exam-rooms/" + room + "/start").exchange().expectStatus().isForbidden();
        bearer(studentToken).post().uri("/api/v1/exam-rooms/" + room + "/open").exchange().expectStatus().isForbidden();

        bearer(ownerToken).post().uri("/api/v1/exam-rooms/" + room + "/participants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"accountId\":" + STUDENT + "}")
                .exchange().expectStatus().isOk();
        bearer(ownerToken).post().uri("/api/v1/exam-rooms/" + room + "/open").exchange().expectStatus().isOk();
        bearer(ownerToken).post().uri("/api/v1/exam-rooms/" + room + "/start").exchange().expectStatus().isOk();
        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/exam-rooms/" + room + "/join").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.simulationId").value(value -> simulationId.set(((Number) value).longValue()));

        sql("UPDATE exam_rooms SET starts_at = '" + now.minusHours(2) + "+01:00', ends_at = '" + now.minusHours(1)
                + "+01:00' WHERE id = " + room);
        roomExpirationJob.sweep().block(Duration.ofSeconds(20));
        assertThat(events.closed.stream().filter(id -> id.equals(room)).count()).isEqualTo(1);
        bearer(adminToken).post().uri("/api/v1/exam-rooms/" + room + "/close").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CLOSED").jsonPath("$.phase").isEqualTo("ENDED");
        bearer(jwtService.generateToken(OTHER_STUDENT, List.of("INTEGRATION"))).post()
                .uri("/api/v1/exam-rooms/" + room + "/close").exchange().expectStatus().isOk();
        assertThat(events.closed.stream().filter(id -> id.equals(room)).count()).isEqualTo(1);
        assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = " + room + " AND status = 'CLOSED'")).isEqualTo(1L);
        assertThat(one("SELECT count(*) FROM simulations WHERE id = " + simulationId + " AND status = 'FINISHED' AND deleted_at IS NULL")).isEqualTo(1L);
        assertThat(roomService.answerKeyVisible(room).block(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void expirationClosesPastRoomsButLeavesFutureRoomsOpen() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        sql("INSERT INTO exam_rooms (id, statement_id, class_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES "
                + "(592001, " + STATEMENT + ", " + CLASS + ", " + OWNER + ", '" + now.minusHours(2) + "+01:00', '" + now.minusHours(1) + "+01:00', 60, 'OPEN'),"
                + "(592002, " + STATEMENT + ", " + CLASS + ", " + OWNER + ", '" + now.minusMinutes(1) + "+01:00', '" + now.plusHours(1) + "+01:00', 60, 'RUNNING')");
        roomExpirationJob.sweep().block(Duration.ofSeconds(20));
        assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = 592001 AND status = 'CLOSED'" )).isEqualTo(1L);
        assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = 592002 AND status = 'RUNNING'" )).isEqualTo(1L);
    }

    @Test
    void expiredRoomSubmitsThreeHundredSimulationsInOneTransaction() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        sql("INSERT INTO exam_rooms (id, statement_id, class_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES "
                + "(592006, " + STATEMENT + ", " + CLASS + ", " + OWNER + ", '" + now.minusHours(2)
                + "+01:00', '" + now.minusHours(1) + "+01:00', 60, 'RUNNING')");
        try {
            sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) "
                    + "SELECT 592100000 + n, 'load592_' || n, 'load592_' || n || '@kixi.test', 'x', true, true FROM generate_series(1, 300) n");
            sql("INSERT INTO simulations (account_id, statement_id, exam_room_id, started_at, status) "
                    + "SELECT 592100000 + n, " + STATEMENT + ", 592006, CURRENT_TIMESTAMP - INTERVAL '2 hours', 'IN_PROGRESS' FROM generate_series(1, 300) n");
            roomExpirationJob.sweep().block(Duration.ofMinutes(3));
            assertThat(one("SELECT count(*) FROM simulations WHERE exam_room_id = 592006 AND status = 'FINISHED'")).isEqualTo(300);
            assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = 592006 AND status = 'CLOSED'")).isEqualTo(1);
            roomExpirationJob.sweep().block(Duration.ofSeconds(20));
            assertThat(one("SELECT count(*) FROM simulation_xp_awards WHERE account_id BETWEEN 592100001 AND 592100300")).isEqualTo(300);
        } finally {
            sql("DELETE FROM simulations WHERE exam_room_id = 592006");
            sql("DELETE FROM accounts WHERE id BETWEEN 592100001 AND 592100300");
        }
    }

    @Test
    void manualAndAutomaticCloseCanRaceWithoutConflict() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        sql("INSERT INTO exam_rooms (id, statement_id, class_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES "
                + "(592003, " + STATEMENT + ", " + CLASS + ", " + OWNER + ", '" + now.minusHours(2)
                + "+01:00', '" + now.minusHours(1) + "+01:00', 60, 'RUNNING')");
        reactor.core.publisher.Mono.when(
                roomService.closeIfExpired(592003L, now).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
                roomService.transition(592003L, OWNER, false, ao.creativemode.kixi.examrooms.model.ExamRoomStatus.CLOSED)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .block(Duration.ofSeconds(20));
        assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = 592003 AND status = 'CLOSED'" )).isEqualTo(1L);
    }

    @Test
    void aFutureInstantIsNotClosedWhenDatabaseRunsInUtc() {
        sql("INSERT INTO exam_rooms (id, statement_id, class_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES "
                + "(592004, " + STATEMENT + ", " + CLASS + ", " + OWNER
                + ", CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '10 minutes', 60, 'RUNNING')");
        roomExpirationJob.sweep().block(Duration.ofSeconds(20));
        assertThat(one("SELECT count(*) FROM exam_rooms WHERE id = 592004 AND status = 'RUNNING'" )).isEqualTo(1L);
    }

    @Test
    void existingSimulationStartedInUtcKeepsItsRemainingTime() {
        sql("INSERT INTO simulations (id, account_id, statement_id, started_at, status) VALUES "
                + "(592005, " + STUDENT + ", " + STATEMENT + ", CURRENT_TIMESTAMP - INTERVAL '10 minutes', 'IN_PROGRESS')");
        var simulation = simulationRepository.findById(592005L).block(Duration.ofSeconds(5));
        assertThat(simulationDeadlineService.acceptsAnswers(simulation, LocalDateTime.now(clock))
                .block(Duration.ofSeconds(5))).isTrue();
    }

    private EnrollmentCodeFixture issueEnrollmentCode(long classId, int maxUses) {
        AtomicReference<String> code = new AtomicReference<>();
        AtomicLong id = new AtomicLong();
        bearer(adminToken).post().uri("/api/v1/enrollment-codes").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"classId\":" + classId + ",\"maxUses\":" + maxUses + "}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.code").value(value -> code.set((String) value))
                .jsonPath("$.id").value(value -> id.set(((Number) value).longValue()));
        return new EnrollmentCodeFixture(id.get(), code.get());
    }

    private record EnrollmentCodeFixture(long id, String code) { }

    private void role(long accountId, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + accountId + ", id FROM roles WHERE name = '" + role + "'");
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private long one(String statement) {
        return database.sql(statement).map((row, metadata) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
