package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.dto.statement.StatementCatalogFilter;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementCatalogService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * GET /api/v1/statements/catalog over HTTP with the real security chain. The staff
 * {@link StatementController} is loaded too, so the test also proves {@code /catalog}
 * is not taken for a statement id by {@code GET /{id}}.
 */
@WebFluxTest(controllers = {StatementCatalogController.class, StatementController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class StatementCatalogControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StatementCatalogService catalogService;

    @MockBean
    private StatementService statementService;

    @MockBean
    private ManualStatementService manualStatementService;

    @Test
    void aStudentGetsAPageOfStatementSummaries() {
        when(catalogService.catalog(any(), eq(42L)))
                .thenReturn(Mono.just(PageResponse.of(List.of(statement()), 0, 20, 21)));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/catalog")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].id").isEqualTo(7)
                .jsonPath("$.content[0].title").isEqualTo("Exame de Matemática")
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(20)
                .jsonPath("$.totalElements").isEqualTo(21)
                .jsonPath("$.totalPages").isEqualTo(2);

        verify(statementService, never()).findById(any());
    }

    @Test
    void everyQueryParameterReachesTheService() {
        when(catalogService.catalog(any(), eq(42L)))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 1, 5, 0)));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/catalog?institutionId=4&courseId=40&classId=3&grade=12"
                        + "&subjectId=5&schoolYearId=2024&termId=1&examType=Exame Final&q=funções"
                        + "&scope=all&sort=recent&page=1&size=5")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<StatementCatalogFilter> captor = ArgumentCaptor.forClass(StatementCatalogFilter.class);
        verify(catalogService).catalog(captor.capture(), eq(42L));
        assertThat(captor.getValue()).isEqualTo(new StatementCatalogFilter(
                4L, 40L, 3L, 12, 5L, 2024L, 1L, "Exame Final", "funções", "all", "recent", 1, 5));
    }

    @Test
    void withoutParametersTheFirstPageOfTheDefaultSizeIsAsked() {
        when(catalogService.catalog(any(), eq(42L)))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0, 20, 0)));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/catalog")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<StatementCatalogFilter> captor = ArgumentCaptor.forClass(StatementCatalogFilter.class);
        verify(catalogService).catalog(captor.capture(), eq(42L));
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(StatementCatalogFilter.DEFAULT_SIZE);
    }

    @Test
    void aRejectedFilterAnswersBadRequest() {
        when(catalogService.catalog(any(), eq(42L)))
                .thenReturn(Mono.error(ApiException.badRequest("Size must be between 1 and 100")));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/catalog?size=500")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Size must be between 1 and 100");
    }

    @Test
    void anonymousCannotBrowseTheCatalog() {
        client.get()
                .uri("/api/v1/statements/catalog")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static Statement statement() {
        Statement statement = new Statement("Exame Final", "Exame de Matemática");
        statement.setId(7L);
        statement.setVisible(true);
        return statement;
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
