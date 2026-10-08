package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.TermRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementLinkValidationService;
import ao.creativemode.kixi.exams.service.StatementWriteAccessService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

/**
 * Approving a statement over HTTP with the real services in place.
 *
 * <p>{@link ao.creativemode.kixi.identity.config.AuthorizationIntegrationTest} covers the
 * route rules, but it mocks {@link StatementService}, so it can only prove which
 * arguments the controller passes. It cannot catch the service refusing the
 * caller, which is exactly what it takes to lock an administrator out of their
 * own school: {@code requireTeaches} used to resolve the teacher's profile before
 * it knew the caller was an administrator and answer 403 to an administrator who
 * never taught. Only this test, with the real chain and mocked repositories
 * only, fails on that.
 */
@WebFluxTest(controllers = StatementController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class,
        StatementService.class, InstitutionAccessService.class, TeachingAssignmentService.class,
        StatementLinkValidationService.class, StatementWriteAccessService.class})
class StatementApprovalAuthorizationTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long TEACHER_ACCOUNT_ID = 7L;
    private static final Long TEACHER_ID = 5L;
    private static final Long INSTITUTION_ID = 4L;
    private static final Long CLASS_ID = 3L;
    private static final Long OTHER_CLASS_ID = 30L;
    private static final Long COURSE_ID = 40L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long SCHOOL_YEAR_ID = 2024L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ManualStatementService manualStatementService;

    @MockBean
    private StatementRepository statements;

    @MockBean
    private QuestionRepository questions;

    @MockBean
    private QuestionOptionRepository options;

    @MockBean
    private InstitutionRepository institutions;

    @MockBean
    private InstitutionSubjectRepository institutionSubjects;

    @MockBean
    private InstitutionTeacherRepository institutionTeachers;

    @MockBean
    private TeacherRepository teachers;

    @MockBean
    private TeachingAssignmentRepository assignments;

    @MockBean
    private ClassRepository classes;

    @MockBean
    private SubjectRepository subjects;

    @MockBean
    private SchoolYearRepository schoolYears;

    @MockBean
    private TermRepository terms;

    @MockBean
    private CourseRepository courses;

    private Statement statement;

    @BeforeEach
    void setUp() {
        statement = new Statement("Teste", "Prova de Matemática");
        statement.setId(1L);
        statement.setInstitutionId(INSTITUTION_ID);
        statement.setClassId(CLASS_ID);
        statement.setSubjectId(SUBJECT_ID);
        statement.setNeedsReview(true);
        statement.setVisible(false);
    }

    @Test
    void anAdministratorWhoNeverTaughtApprovesAStatementOfTheirSchool() {
        givenTheInstitutionTeachesTheSubject();
        // No teacher profile for the administrator at all.
        when(teachers.findByAccountIdAndDeletedAtIsNull(ADMIN_ID)).thenReturn(Mono.empty());
        givenTheStatementIsReadable();

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.needsReview").isEqualTo(false);
    }

    @Test
    void anAdministratorWithATeacherProfileAndNoAssignmentAlsoApproves() {
        givenTheInstitutionTeachesTheSubject();
        when(teachers.findByAccountIdAndDeletedAtIsNull(ADMIN_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID)));
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));
        givenTheStatementIsReadable();

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aTeacherAssignedToTheClassApproves() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aTeacherAssignedToAnotherClassIsForbidden() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isForbidden();

        org.mockito.Mockito.verify(statements, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Statement.class));
    }

    @Test
    void aStudentCannotApprove() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isForbidden();
    }

    // ── Creating and editing a statement ─────────────────────────────────

    @Test
    void aStudentCannotCreateAStatement() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/statements")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void aStudentCannotEditAStatement() {
        client.mutateWith(studentJwt())
                .put()
                .uri("/api/v1/statements/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void anonymousCannotCreateOrEditAStatement() {
        client.post()
                .uri("/api/v1/statements")
                .exchange()
                .expectStatus().isUnauthorized();

        client.put()
                .uri("/api/v1/statements/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aTeacherAssignedToTheClassEditsTheMetadata() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", INSTITUTION_ID,
                        "subjectId", SUBJECT_ID,
                        "classId", CLASS_ID,
                        "examType", "P1",
                        "title", "Prova corrigida"))
                .exchange()
                .expectStatus().isOk();

        assertThat(statement.getTitle()).isEqualTo("Prova corrigida");
    }

    @Test
    void aTeacherAssignedToAnotherClassCannotEdit() {
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        givenTheClass(OTHER_CLASS_ID);
        // Allowed on the statement as it stands, refused on the class it is
        // being moved to: a teacher must not hand a paper to a class they do
        // not teach, and the second check is what stops them.
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, OTHER_CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", INSTITUTION_ID,
                        "subjectId", SUBJECT_ID,
                        "classId", OTHER_CLASS_ID,
                        "examType", "P1",
                        "title", "Prova roubada"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void anAdministratorEditsTheMetadataOfAStatementTheOcrProduced() {
        // The OCR leaves institution_id empty, so this is the route by which an
        // OCR statement joins a school: the first authorization check has nothing
        // to weigh it and the one on the written metadata is what admits it.
        statement.setInstitutionId(null);
        statement.setClassId(null);
        statement.setSource("ocr");
        givenTheInstitutionTeachesTheSubject();
        givenTheClass(CLASS_ID);
        givenTheStatementIsReadable();

        client.mutateWith(adminJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", INSTITUTION_ID,
                        "subjectId", SUBJECT_ID,
                        "classId", CLASS_ID,
                        "examType", "Exame",
                        "title", "Prova do OCR corrigida"))
                .exchange()
                .expectStatus().isOk();

        assertThat(statement.getInstitutionId()).isEqualTo(INSTITUTION_ID);
        assertThat(statement.getSource()).isEqualTo("ocr");
    }

    @Test
    void aTeacherCannotTakeOverAStatementFromTheOcrInAClassTheyDoNotTeach() {
        // The carve-out this PR closes, over HTTP with the real chain: the OCR
        // leaves institution_id empty, and the statement is sitting in a class
        // this teacher does not teach. Before, requireCanEdit returned straight
        // away on the empty institution and nothing was weighed at all — the
        // teacher could move the statement into their own class, or purge it.
        statement.setInstitutionId(null);
        statement.setClassId(OTHER_CLASS_ID);
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(OTHER_CLASS_ID);
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, OTHER_CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", INSTITUTION_ID,
                        "subjectId", SUBJECT_ID,
                        "classId", CLASS_ID,
                        "examType", "P1",
                        "title", "Prova roubada"))
                .exchange()
                .expectStatus().isForbidden();

        org.mockito.Mockito.verify(statements, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Statement.class));
    }

    @Test
    void aTeacherAssignedToTheClassMayEditAStatementFromTheOcr() {
        statement.setInstitutionId(null);
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", INSTITUTION_ID,
                        "subjectId", SUBJECT_ID,
                        "classId", CLASS_ID,
                        "examType", "P1",
                        "title", "Prova corrigida"))
                .exchange()
                .expectStatus().isOk();

        assertThat(statement.getInstitutionId()).isEqualTo(INSTITUTION_ID);
    }

    @Test
    void anUnknownInstitutionIsNotFoundBeforeAnythingElseIsChecked() {
        // The institution is checked first, so an unknown one answers 404 even
        // to a caller who is also not entitled to it. The ordering is observable
        // and worth pinning.
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION_ID)).thenReturn(Mono.empty());
        givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadataFor(INSTITUTION_ID, CLASS_ID))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void metadataThatDoesNotHangTogetherIsUnprocessableOverHttp() {
        // "Validação de vínculo com a estrutura académica" is a named task of the
        // issue and until now every assertion of it sat at service level, so the
        // wiring from the route down to the validator was unproven.
        givenTheInstitutionTeachesTheSubject();
        givenTheTeacherIsAffiliated();
        givenTheClass(CLASS_ID);
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));
givenTheStatementIsReadable();

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadataFor(INSTITUTION_ID, CLASS_ID, SCHOOL_YEAR_ID + 1))
                .exchange()
                .expectStatus().isEqualTo(422);

        org.mockito.Mockito.verify(statements, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Statement.class));
    }

    private Map<String, Object> metadataFor(Long institutionId, Long classId) {
        return metadataFor(institutionId, classId, null);
    }

    private Map<String, Object> metadataFor(Long institutionId, Long classId, Long schoolYearId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("institutionId", institutionId);
        body.put("subjectId", SUBJECT_ID);
        body.put("classId", classId);
        body.put("examType", "P1");
        body.put("title", "Prova de Matemática");
        if (schoolYearId != null) {
            body.put("schoolYearId", schoolYearId);
        }
        return body;
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenTheInstitutionTeachesTheSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION_ID))
                .thenReturn(Mono.just(new Institution()));
        when(institutionSubjects.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(
                INSTITUTION_ID, SUBJECT_ID)).thenReturn(Mono.just(true));
        // The link validator runs for real, so the academic repositories behind
        // it have to answer too.
        Subject subject = new Subject();
        subject.setId(SUBJECT_ID);
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.just(subject));
        SchoolYear year = new SchoolYear();
        year.setId(SCHOOL_YEAR_ID);
        when(schoolYears.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.just(year));
    }

    private void givenTheTeacherIsAffiliated() {
        when(teachers.findByAccountIdAndDeletedAtIsNull(TEACHER_ACCOUNT_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID)));
        when(institutionTeachers.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(
                INSTITUTION_ID, TEACHER_ID)).thenReturn(Mono.just(true));
    }

    private void givenTheClass(Long classId) {
        when(classes.findByIdAndDeletedAtIsNull(classId))
                .thenReturn(Mono.just(classOfYear(classId, SCHOOL_YEAR_ID)));
    }

    private Class classOfYear(Long classId, Long schoolYearId) {
        Class klass = new Class();
        klass.setId(classId);
        klass.setGrade(12);
        klass.setSchoolYearId(schoolYearId);
        // classes.course_id is NOT NULL in V10; leaving it null would NPE the
        // validator's course branch as soon as a payload carried a courseId.
        klass.setCourseId(COURSE_ID);
        return klass;
    }

    private void givenTheStatementIsReadable() {
        when(statements.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(statement));
        when(statements.save(statement)).thenReturn(Mono.just(statement));
    }

    private Teacher teacher(Long id) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setFirstName("Ana");
        teacher.setLastName("Costa");
        return teacher;
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(ADMIN_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ACCOUNT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
