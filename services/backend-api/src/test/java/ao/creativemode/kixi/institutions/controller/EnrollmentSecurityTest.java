package ao.creativemode.kixi.institutions.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.util.List;
import java.util.Map;

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

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollmentResponse;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.service.EnrollmentService;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = {EnrollmentController.class, MeController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class EnrollmentSecurityTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private EnrollmentService enrollmentService;

    @MockBean
    private MeService meService;

    @Test
    void rejectsAnonymousEnrollmentReads() {
        client.get()
                .uri("http://localhost/api/v1/enrollments")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAnonymousMeReads() {
        client.get()
                .uri("http://localhost/api/v1/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void studentListsOwnEnrollments() {
        when(enrollmentService.findVisible(eq(42L), eq(false), any()))
            .thenReturn(Flux.just(new EnrollmentResponse(1L, 42L, 7L, 2024L, "ACTIVE")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/enrollments")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].accountId").isEqualTo(42);
    }

    @Test
    void studentEnrollsThemselves() {
        when(enrollmentService.enroll(eq(42L), eq(false), any()))
            .thenReturn(Mono.just(new EnrollmentResponse(1L, 42L, 7L, 2024L, "ACTIVE")));

        client.mutateWith(studentJwt())
                .post()
                .uri("http://localhost/api/v1/enrollments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("classId", 7, "schoolYearId", 2024))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACTIVE");
    }

    @Test
    void studentReadsOwnProfile() {
        when(meService.getMe(42L)).thenReturn(Mono.just(me()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accountId").isEqualTo(42)
                .jsonPath("$.roles[0]").isEqualTo("STUDENT");
    }

    @Test
    void studentUpdatesOwnProfile() {
        when(meService.updateMe(eq(42L), any())).thenReturn(Mono.just(me()));

        client.mutateWith(studentJwt())
                .put()
                .uri("http://localhost/api/v1/me")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("photo", "new.png"))
                .exchange()
                .expectStatus().isOk();
    }

    private static MeResponse me() {
        return new MeResponse(42L, "ada", "ada@kixi.ao", "Ada", "Lovelace", null,
                List.of("STUDENT"),
                new MeResponse.SchoolInfo(11L, "ITEL", "ITEL"),
                new MeResponse.CourseInfo(3L, "INFO", "Informática"),
                new MeResponse.ClassInfo(7L, "10A", 10, 2024L, "2024/2025"));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        ));
    }
}
