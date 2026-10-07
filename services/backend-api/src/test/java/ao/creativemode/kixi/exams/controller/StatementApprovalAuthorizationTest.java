package ao.creativemode.kixi.exams.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
        StatementService.class, InstitutionAccessService.class, TeachingAssignmentService.class})
class StatementApprovalAuthorizationTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long TEACHER_ACCOUNT_ID = 7L;
    private static final Long TEACHER_ID = 5L;
    private static final Long INSTITUTION_ID = 4L;
    private static final Long CLASS_ID = 3L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long SCHOOL_YEAR_ID = 2024L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

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
        givenTheClass();
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

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenTheInstitutionTeachesTheSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(INSTITUTION_ID))
                .thenReturn(Mono.just(new Institution()));
        when(institutionSubjects.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(
                INSTITUTION_ID, SUBJECT_ID)).thenReturn(Mono.just(true));
    }

    private void givenTheTeacherIsAffiliated() {
        when(teachers.findByAccountIdAndDeletedAtIsNull(TEACHER_ACCOUNT_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID)));
        when(institutionTeachers.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(
                INSTITUTION_ID, TEACHER_ID)).thenReturn(Mono.just(true));
        givenTheClass();
    }

    private void givenTheClass() {
        Class klass = new Class();
        klass.setId(CLASS_ID);
        klass.setGrade(12);
        klass.setSchoolYearId(SCHOOL_YEAR_ID);
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.just(klass));
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
