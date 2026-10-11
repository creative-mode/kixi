package ao.creativemode.kixi.identity.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.dto.teachers.TeacherResponse;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.identity.service.TeacherService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
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
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

/**
 * #176: {@code GET /api/v1/teachers/me}, so a teacher with no assignment yet can
 * learn their own teacherId. Only this route opens to TEACHER.
 */
@WebFluxTest(controllers = TeacherController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class,
        JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class TeacherMeTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TeacherService teacherService;

    @Test
    void aTeacherReadsTheirOwnRecord() {
        when(teacherService.findMine(7L)).thenReturn(Mono.just(new TeacherResponse(
                3L, 7L, true, "Ana", "Silva", "ana@kixi.ao", null, null, null, null, null, null)));

        client.mutateWith(as("7", "ROLE_TEACHER"))
                .get()
                .uri("/api/v1/teachers/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(3)
                .jsonPath("$.accountId").isEqualTo(7);
    }

    @Test
    void anAccountWithoutATeacherRecordGetsNotFound() {
        when(teacherService.findMine(1L))
                .thenReturn(Mono.error(ApiException.notFound("The account has no teacher record")));

        client.mutateWith(as("1", "ROLE_ADMIN"))
                .get()
                .uri("/api/v1/teachers/me")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aStudentIsRefused() {
        client.mutateWith(as("42", "ROLE_STUDENT"))
                .get()
                .uri("/api/v1/teachers/me")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teacherService);
    }

    @Test
    void theRestOfTheTeachersRoutesStayAdminOnly() {
        client.mutateWith(as("7", "ROLE_TEACHER"))
                .get()
                .uri("/api/v1/teachers")
                .exchange()
                .expectStatus().isForbidden();
        client.mutateWith(as("7", "ROLE_TEACHER"))
                .get()
                .uri("/api/v1/teachers/3")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teacherService);
    }

    private static WebTestClientConfigurer as(String accountId, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                accountId, null, List.of(new SimpleGrantedAuthority(role))));
    }
}
