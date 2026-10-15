package ao.creativemode.kixi.institutions.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.academic.controller.ClassController;
import ao.creativemode.kixi.academic.controller.CourseController;
import ao.creativemode.kixi.academic.controller.CourseCurriculumController;
import ao.creativemode.kixi.academic.controller.SchoolYearController;
import ao.creativemode.kixi.academic.controller.SubjectController;
import ao.creativemode.kixi.academic.controller.TermController;
import ao.creativemode.kixi.academic.service.ClassService;
import ao.creativemode.kixi.academic.service.CourseService;
import ao.creativemode.kixi.academic.service.CurriculumService;
import ao.creativemode.kixi.academic.service.SchoolYearService;
import ao.creativemode.kixi.academic.service.SubjectService;
import ao.creativemode.kixi.academic.service.TermService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.identity.controller.TeacherController;
import ao.creativemode.kixi.identity.service.TeacherService;
import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.ClassSubject;
import ao.creativemode.kixi.institutions.dto.structure.TeacherOverviewResponse;
import ao.creativemode.kixi.institutions.service.AcademicOverviewService;
import ao.creativemode.kixi.institutions.service.InstitutionMembershipService;
import ao.creativemode.kixi.institutions.service.InstitutionService;
import ao.creativemode.kixi.shared.exception.GlobalExceptionHandler;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Who may write and who may read the academic structure (issue #253): the writes are for the
 * administrator only, a teacher still reads, the new read models have their own audience.
 */
@WebFluxTest(controllers = {
        CourseController.class, ClassController.class, SubjectController.class, SchoolYearController.class,
        TermController.class, CourseCurriculumController.class, AcademicOverviewController.class,
        InstitutionController.class, TeacherController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class,
        GlobalExceptionHandler.class})
class AcademicStructureSecurityTest {

    @Autowired
    private WebTestClient client;

    @MockBean private JwtService jwtService;
    @MockBean private CourseService courseService;
    @MockBean private ClassService classService;
    @MockBean private SubjectService subjectService;
    @MockBean private SchoolYearService schoolYearService;
    @MockBean private TermService termService;
    @MockBean private CurriculumService curriculumService;
    @MockBean private AcademicOverviewService overviewService;
    @MockBean private InstitutionService institutionService;
    @MockBean private InstitutionMembershipService membershipService;
    @MockBean private TeacherService teacherService;

    private static final String BODY = "{\"code\":\"X\",\"name\":\"Xyz\"}";

    @Test
    void aTeacherCannotWriteAnyPartOfTheStructure() {
        for (String path : List.of("/api/v1/courses", "/api/v1/classes", "/api/v1/subjects",
                "/api/v1/school-years", "/api/v1/terms", "/api/v1/courses/1/subjects")) {
            client.mutateWith(as("TEACHER")).post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                    .exchange().expectStatus().isForbidden();
        }
        client.mutateWith(as("TEACHER")).put().uri("/api/v1/courses/1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange().expectStatus().isForbidden();
        client.mutateWith(as("TEACHER")).delete().uri("/api/v1/classes/1").exchange().expectStatus().isForbidden();
        client.mutateWith(as("TEACHER")).delete().uri("/api/v1/courses/1/subjects/2").exchange().expectStatus().isForbidden();
    }

    @Test
    void anAdministratorCanReachTheWrites() {
        when(curriculumService.remove(any(), any())).thenReturn(Mono.empty());

        client.mutateWith(as("ADMIN")).delete().uri("/api/v1/courses/1/subjects/2")
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void aTeacherStillReadsTheStructure() {
        when(courseService.findAllActive(any())).thenReturn(Flux.empty());
        when(curriculumService.listForCourse(1L)).thenReturn(Flux.empty());

        client.mutateWith(as("TEACHER")).get().uri("/api/v1/courses").exchange().expectStatus().isOk();
        client.mutateWith(as("TEACHER")).get().uri("/api/v1/courses/1/subjects").exchange().expectStatus().isOk();
    }

    @Test
    void aStudentSeesTheSubjectsOfAClassButTheyComeWithoutTeachers() {
        when(overviewService.classSubjects(3L, false)).thenReturn(Mono.just(List.<ClassSubject>of()));

        client.mutateWith(as("STUDENT")).get().uri("/api/v1/classes/3/subjects").exchange().expectStatus().isOk();
        org.mockito.Mockito.verify(overviewService).classSubjects(3L, false);
    }

    @Test
    void aTeacherGetsTheTeachersOfTheSubjectsOfAClass() {
        when(overviewService.classSubjects(3L, true)).thenReturn(Mono.just(List.<ClassSubject>of()));

        client.mutateWith(as("TEACHER")).get().uri("/api/v1/classes/3/subjects").exchange().expectStatus().isOk();
        org.mockito.Mockito.verify(overviewService).classSubjects(3L, true);
    }

    @Test
    void theOfferingsOfASubjectAreForTheAdministrator() {
        client.mutateWith(as("TEACHER")).get().uri("/api/v1/subjects/MAT/offerings").exchange().expectStatus().isForbidden();
        client.mutateWith(as("STUDENT")).get().uri("/api/v1/subjects/MAT/offerings").exchange().expectStatus().isForbidden();
    }

    @Test
    void theTreeOfASchoolIsForTheAdministrator() {
        client.mutateWith(as("TEACHER")).get().uri("/api/v1/institutions/1/structure").exchange().expectStatus().isForbidden();
        client.mutateWith(as("STUDENT")).get().uri("/api/v1/institutions/1/structure").exchange().expectStatus().isForbidden();
    }

    @Test
    void aTeacherSeesTheirOwnOverviewButNotTheOneOfAnotherTeacher() {
        when(overviewService.teacherOverviewForAccount(any())).thenReturn(Mono.just(
                new TeacherOverviewResponse(new TeacherOverviewResponse.TeacherRef(1L, "A", "B"), List.of())));

        client.mutateWith(as("TEACHER")).get().uri("/api/v1/teachers/me/overview").exchange().expectStatus().isOk();
        client.mutateWith(as("TEACHER")).get().uri("/api/v1/teachers/1/overview").exchange().expectStatus().isForbidden();
        client.mutateWith(as("STUDENT")).get().uri("/api/v1/teachers/me/overview").exchange().expectStatus().isForbidden();
    }

    @Test
    void theListOfSchoolsCanBeNarrowedToTheOnesWithClassesInAYear() {
        when(institutionService.findAllActiveWithClassesInYear(2L)).thenReturn(Flux.empty());

        client.mutateWith(as("STUDENT")).get().uri("/api/v1/institutions?onlyWithClassesInYear=2")
                .exchange().expectStatus().isOk();
        org.mockito.Mockito.verify(institutionService).findAllActiveWithClassesInYear(2L);
    }

    private static WebTestClientConfigurer as(String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
