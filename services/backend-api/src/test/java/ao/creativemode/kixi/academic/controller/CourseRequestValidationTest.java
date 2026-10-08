package ao.creativemode.kixi.academic.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

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
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import org.springframework.test.web.reactive.server.WebTestClient;

import ao.creativemode.kixi.academic.service.CourseService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.GlobalExceptionHandler;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

/**
 * The constraints on the course payload, enforced before the service is reached.
 *
 * <p>These were dropped when the school was added to the course and replaced by
 * {@code @NotNull}: an empty code went through, and a code longer than the column
 * reached the database and came back as a 409 "already exists" that was simply false.
 * A blank code and an over-long code have to be refused at the edge with a message
 * that names the field.
 */
@WebFluxTest(controllers = CourseController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class,
        GlobalExceptionHandler.class})
class CourseRequestValidationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private CourseService courseService;

@Test
    void anEmptyCodeIsRejectedAndReportsEveryConstraintItBreaks() {
        // An empty string fails @NotBlank and @Size at once. Which of the two the
        // validator reports first is not guaranteed — it varied between runs — so
        // keeping only one made the answer depend on ordering. Both are returned
        // under "messages", with the first also under "message" so the common
        // single-error shape is unchanged for existing callers.
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("", "Técnico", null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.properties.code.messages.length()").isEqualTo(2);

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsACodeOfOnlySpaces() {
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("   ", "Técnico", null))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsACodeLongerThanTheColumn() {
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("T".repeat(51), "Técnico", null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                               .jsonPath("$.properties.code.message").value(
                        org.hamcrest.Matchers.containsString("Code must be between 2 and 50 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsANameShorterThanThree() {
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("TISM", "ab", null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.properties.name.message").value(
                        org.hamcrest.Matchers.containsString("Name must be between 3 and 255 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsADescriptionLongerThanFiveThousand() {
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("TISM", "Técnico", "d".repeat(5001)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.properties.description.message").value(
                        org.hamcrest.Matchers.containsString("Description cannot exceed 5000 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsAMissingSchool() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", "TISM");
        body.put("name", "Técnico");
        body.put("description", null);
        // no institutionId

        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.properties.institutionId").value(
                        org.hamcrest.Matchers.containsString("Institution is required"));

        verifyNoInteractions(courseService);
    }

    private static Map<String, Object> payload(String code, String name, String description) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("description", description);
        body.put("institutionId", 7L);
        return body;
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}