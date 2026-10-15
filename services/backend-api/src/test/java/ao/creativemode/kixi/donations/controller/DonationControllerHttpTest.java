package ao.creativemode.kixi.donations.controller;

import static ao.creativemode.kixi.donations.service.DonationFixtures.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.donations.config.DonationProperties;
import ao.creativemode.kixi.donations.dto.DonationCreatedResponse;
import ao.creativemode.kixi.donations.dto.DonationDetail;
import ao.creativemode.kixi.donations.dto.DonationPage;
import ao.creativemode.kixi.donations.dto.DonationSubmission;
import ao.creativemode.kixi.donations.service.DonationRateLimiter;
import ao.creativemode.kixi.donations.service.DonationService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.exception.DonationRateLimitException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = DonationController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000",
        "app.donations.max-total-bytes=100000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class,
        JwtAuthenticationFilter.class, RequestIdWebFilter.class, DonationProperties.class,
        TrustedProxyProperties.class})
class DonationControllerHttpTest {

    private static final String BOUNDARY = "kixiBoundary7MA4YWxkTrZu0gW";

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private DonationService service;

    @MockBean
    private DonationRateLimiter rateLimiter;

    /** A multipart body built by hand, so the request carries a Content-Length like a browser's does. */
    private static byte[] multipart(Map<String, String> fields, byte[]... files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fields.forEach((name, value) -> write(out, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n"));
        int i = 0;
        for (byte[] file : files) {
            write(out, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"p" + (i++)
                    + ".png\"\r\nContent-Type: image/png\r\n\r\n");
            out.writeBytes(file);
            write(out, "\r\n");
        }
        write(out, "--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> fields() {
        return Map.of("institutionName", "Instituto de Telecomunicações", "subjectName", "Matemática",
                "examType", "Exame", "consent", "true", "website", "", "formStartedAt", "1760000000000");
    }

    private WebTestClient.RequestHeadersSpec<?> donate(byte[] body) {
        return client.post().uri("/api/v1/donations")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .contentLength(body.length)
                .bodyValue(body);
    }

    @Test
    void anyoneCanDonateWithoutAToken() {
        when(rateLimiter.checkSubmission(anyString())).thenReturn(Mono.empty());
        when(service.clientHash(anyString())).thenReturn("hash");
        when(service.submit(any(DonationSubmission.class), eq("hash")))
                .thenReturn(Mono.just(new DonationCreatedResponse("KD-ABCD-2345")));

        donate(multipart(fields(), png(20, 20, 1))).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.reference").isEqualTo("KD-ABCD-2345");

        ArgumentCaptor<DonationSubmission> sent = ArgumentCaptor.forClass(DonationSubmission.class);
        verify(service).submit(sent.capture(), eq("hash"));
        assertThat(sent.getValue().institutionName()).isEqualTo("Instituto de Telecomunicações");
        assertThat(sent.getValue().consent()).isTrue();
        assertThat(sent.getValue().files()).hasSize(1);
        assertThat(sent.getValue().formStartedAt()).isEqualTo(1760000000000L);
    }

    @Test
    void theBudgetIsSpentBeforeTheBodyIsParsedAndAnswers429WithRetryAfter() {
        when(service.clientHash(anyString())).thenReturn("hash");
        when(rateLimiter.checkSubmission("hash")).thenReturn(Mono.error(new DonationRateLimitException(1800)));

        donate(multipart(fields(), png(20, 20, 1))).exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1800");

        verify(service, never()).submit(any(), anyString());
    }

    @Test
    void aBodyWithoutALengthIs411() {
        client.post().uri("/api/v1/donations")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .exchange().expectStatus().isEqualTo(411);

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void aDeclaredSizeBeyondTheCapIs413BeforeAnythingIsRead() {
        byte[] huge = new byte[100_000 + 256 * 1024 + 1];

        client.post().uri("/api/v1/donations")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .contentLength(huge.length)
                .bodyValue(huge)
                .exchange().expectStatus().isEqualTo(413);

        verifyNoInteractions(rateLimiter, service);
    }

    @Test
    void onlyMultipartIsAccepted() {
        client.post().uri("/api/v1/donations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"institutionName\":\"x\"}").exchange().expectStatus().isEqualTo(415);
    }

    @Test
    void everyOtherDonationRouteNeedsAnAdministrator() {
        for (String uri : List.of("/api/v1/donations", "/api/v1/donations/1", "/api/v1/donations/1/files/2")) {
            client.get().uri(uri).exchange().expectStatus().isUnauthorized();
            client.mutateWith(jwt("42", "ROLE_STUDENT")).get().uri(uri).exchange().expectStatus().isForbidden();
            client.mutateWith(jwt("7", "ROLE_TEACHER")).get().uri(uri).exchange().expectStatus().isForbidden();
        }
        client.patch().uri("/api/v1/donations/1/review").exchange().expectStatus().isUnauthorized();
        client.mutateWith(jwt("7", "ROLE_TEACHER")).post().uri("/api/v1/donations/1/extract")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(jwt("7", "ROLE_TEACHER")).post().uri("/api/v1/donations/1/link-statement")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"statementId\":1}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }

    @Test
    void anAdministratorListsAndReads() {
        when(service.list(null, 1, 20)).thenReturn(Mono.just(new DonationPage(List.of(), 0, 1, 20, Map.of())));

        client.mutateWith(jwt("1", "ROLE_ADMIN")).get().uri("/api/v1/donations")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(0);
    }

    @Test
    void aFileIsServedAsAnAttachmentOfItsRealTypeAndNeverSniffed() {
        when(service.file(5L, 2L)).thenReturn(Mono.just(
                new DonationService.StoredFile("prova.png", "image/png", png(10, 10, 4))));

        client.mutateWith(jwt("1", "ROLE_ADMIN")).get().uri("/api/v1/donations/5/files/2")
                .exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value).startsWith("attachment"));
    }

    private static WebTestClientConfigurer jwt(String account, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
