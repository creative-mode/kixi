package ao.creativemode.kixi.academic.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import ao.creativemode.kixi.academic.dto.courses.CourseResponse;
import ao.creativemode.kixi.academic.dto.classe.ClassResponse;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.service.ClassService;
import ao.creativemode.kixi.academic.service.CourseService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;

/**
 * The student onboarding picker needs the academic lists narrowed by school, in the same
 * call the student makes: pick the school, then only its courses, then only the classes
 * of the course in that school. These pin the query parameters and the school each
 * response carries.
 */
@WebFluxTest(controllers = {CourseController.class, ClassController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class AcademicSchoolFilterTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private CourseService courseService;

    @MockBean
    private ClassService classService;

    private CourseResponse course(long id, long institutionId) {
        return new CourseResponse(id, institutionId, "TISM", "Técnico de Informática", null,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    private ClassResponse clazz(long id, long institutionId) {
        return new ClassResponse(id, "12B", 12, new Course(), new SchoolYear(), institutionId,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    void coursesCanBeNarrowedToOneSchool() {
        when(courseService.findAllActive(eq(7L))).thenReturn(Flux.just(course(1L, 7L)));

        client.mutateWith(authenticatedAsStudent())
                .get()
                .uri("http://localhost/api/v1/courses?institutionId=7")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].institutionId").isEqualTo(7);
    }

    @Test
    void coursesWithoutAFilterReturnEverything() {
        when(courseService.findAllActive(eq(null))).thenReturn(Flux.just(course(1L, 7L)));

        client.mutateWith(authenticatedAsStudent())
                .get()
                .uri("http://localhost/api/v1/courses")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(1);
    }

    @Test
    void classesCanBeNarrowedToSchoolAndCourse() {
        when(classService.findAllActive(eq(7L), eq(3L))).thenReturn(Flux.just(clazz(9L, 7L)));

        client.mutateWith(authenticatedAsStudent())
                .get()
                .uri("http://localhost/api/v1/classes?institutionId=7&courseId=3")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].institutionId").isEqualTo(7)
                .jsonPath("$[0].code").isEqualTo("12B");
    }

    @Test
    void classesCanBeNarrowedToASchoolOnly() {
        when(classService.findAllActive(eq(7L), eq(null))).thenReturn(Flux.just(clazz(9L, 7L)));

        client.mutateWith(authenticatedAsStudent())
                .get()
                .uri("http://localhost/api/v1/classes?institutionId=7")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ClassResponse.class).hasSize(1);
    }

    @Test
    void studentsMayReadTheAcademicLists() {
        when(courseService.findAllActive(eq(null))).thenReturn(Flux.empty());

        client.mutateWith(authenticatedAsStudent())
                .get()
                .uri("http://localhost/api/v1/courses")
                .exchange()
                .expectStatus().isOk();
    }

    private org.springframework.test.web.reactive.server.WebTestClientConfigurer authenticatedAsStudent() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
