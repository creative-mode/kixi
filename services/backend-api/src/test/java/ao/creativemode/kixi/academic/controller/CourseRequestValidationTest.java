package ao.creativemode.kixi.academic.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hamcrest.Matcher;
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
 *
 * <p>Every assertion here checks a plain string under {@code properties}, because that
 * is the contract the clients rely on. All three read the problem detail with
 * {@code Object.values(j.properties).filter(v => typeof v === 'string')}: a nested
 * object in that map is dropped silently and the field reads as having no error at all.
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
    void rejectsAnEmptyCode() {
        post(payload("", "Técnico", null))
                .jsonPath("$.properties.code").value(contains("Code is required"));

        verifyNoInteractions(courseService);
    }

    @Test
    void joinsEveryConstraintAnEmptyCodeBreaksIntoOneReadableString() {
        // An empty string fails @NotBlank and @Size at once, and the order the
        // validator reports them is not stable. Both messages are joined with " · " so
        // the field reads the same whichever came first.
        post(payload("", "Técnico", null))
                .jsonPath("$.properties.code").value(org.hamcrest.Matchers.allOf(
                        contains("Code is required"),
                        contains("Code must be between 2 and 50 characters")));

        verifyNoInteractions(courseService);
    }

    @Test
    void everyFieldErrorIsAStringSoTheClientsCanReadIt() {
        // The exact shape the frontends depend on: they keep only the string values of
        // `properties` and join them with the detail. `properties` also carries
        // requestId and instance, so the check is on the two fields that broke.
        client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload("", "ab", null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.properties.code").value(instanceOf(String.class))
                .jsonPath("$.properties.name").value(instanceOf(String.class));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsACodeOfOnlySpaces() {
        post(payload("   ", "Técnico", null));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsACodeLongerThanTheColumn() {
        post(payload("T".repeat(51), "Técnico", null))
                .jsonPath("$.properties.code").value(
                        contains("Code must be between 2 and 50 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsANameShorterThanThree() {
        post(payload("TISM", "ab", null))
                .jsonPath("$.properties.name").value(
                        contains("Name must be between 3 and 255 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsADescriptionLongerThanFiveThousand() {
        post(payload("TISM", "Técnico", "d".repeat(5001)))
                .jsonPath("$.properties.description").value(
                        contains("Description cannot exceed 5000 characters"));

        verifyNoInteractions(courseService);
    }

    @Test
    void rejectsAMissingSchool() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TISM");
        body.put("name", "Técnico");
        body.put("description", null);
        // no institutionId

        post(body)
                .jsonPath("$.properties.institutionId").value(contains("Institution is required"));

        verifyNoInteractions(courseService);
    }

    private WebTestClient.BodyContentSpec post(Map<String, Object> body) {
        return client.mutateWith(adminJwt())
                .post().uri("/api/v1/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody();
    }

    private static Matcher<String> contains(String substring) {
        return org.hamcrest.Matchers.containsString(substring);
    }

    private static Matcher<Object> instanceOf(Class<?> type) {
        return (Matcher<Object>) (Matcher<?>) org.hamcrest.Matchers.instanceOf(type);
    }

    private static Map<String, Object> payload(String code, String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
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