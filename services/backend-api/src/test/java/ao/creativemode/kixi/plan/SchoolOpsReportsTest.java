package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.plan.TermPlanService.Access;
import ao.creativemode.kixi.plan.TermPlanService.Assessment;
import ao.creativemode.kixi.plan.TermPlanService.Plan;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * A exportação da pauta em PDF (kixi#622): o Kixi pede ao motor uma ligação nova, abre-a e devolve os bytes. O motor é um
 * MockWebServer — a CI nunca precisa do motor a correr.
 */
class SchoolOpsReportsTest {

    private static final byte[] PDF = "%PDF-1.4 pauta".getBytes();

    private MockWebServer motor;
    private SchoolOpsProperties properties;
    private TermPlanService plans;
    private AuditoriaRelatorios auditoria;
    private SchoolOpsReports reports;

    @BeforeEach
    void setUp() throws IOException {
        motor = new MockWebServer();
        motor.start();
        properties = new SchoolOpsProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(motor.url("/").toString());
        properties.setToken("token-do-kixi");
        plans = mock(TermPlanService.class);
        auditoria = mock(AuditoriaRelatorios.class);
        when(auditoria.registar(any(), any(), any(), any())).thenReturn(Mono.empty());
        reports = new SchoolOpsReports(properties, plans, auditoria, WebClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        motor.shutdown();
    }

    private static Access admin() {
        return new Access(7L, true, null, Set.of());
    }

    private static Assessment avaliacao(long id, Long statementId) {
        return new Assessment(id, "P1", 1, "P1", LocalDate.parse("2026-11-10"), statementId, null, "GRADED", null, 0);
    }

    private static Plan plano(String pautaRef, Assessment... avaliacoes) {
        return new Plan(1L, 10L, "M", 20L, "TLP", 30L, 2, 40L, true, true, true, pautaRef != null, pautaRef, List.of(avaliacoes));
    }

    private SchoolOpsReports comPlano(Plan plan) {
        when(plans.access()).thenReturn(Mono.just(admin()));
        when(plans.get(any(), eq(1L))).thenReturn(Mono.just(plan));
        return reports;
    }

    private void ligacao(String url) {
        motor.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"url\":\"" + url + "\",\"expiraEm\":\"2026-11-17T10:05:00Z\"}"));
    }

    private void pdf() {
        motor.enqueue(new MockResponse().setHeader("Content-Type", "application/pdf").setBody(new String(PDF)));
    }

    @Test
    void aMiniPautaPedeUmaLigacaoNovaEDevolveOPdf() throws Exception {
        ligacao(motor.url("/relatorios/mini-pauta.pdf?exp=1&sig=abc").toString());
        pdf();

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectNextMatches(bytes -> new String(bytes).equals(new String(PDF)))
                .verifyComplete();

        RecordedRequest pedido = motor.takeRequest();
        assertThat(pedido.getPath()).isEqualTo("/v1/relatorios");
        assertThat(pedido.getHeader("Authorization")).isEqualTo("Bearer token-do-kixi");
        assertThat(pedido.getBody().readUtf8()).contains("\"pautaId\":\"PT-1\"").contains("\"tipo\":\"MINI_PAUTA\"");
        RecordedRequest descarga = motor.takeRequest();
        assertThat(descarga.getPath()).isEqualTo("/relatorios/mini-pauta.pdf?exp=1&sig=abc");
        verify(auditoria).registar(7L, 1L, "MINI_PAUTA", null);
    }

    @Test
    void oResultadoDaProvaLevaOEnunciadoDaAvaliacaoEMarcaAAvaliacaoNaAuditoria() throws Exception {
        ligacao(motor.url("/relatorios/resultado.pdf?exp=1&sig=abc").toString());
        pdf();

        StepVerifier.create(comPlano(plano("PT-1", avaliacao(5L, 99L))).resultado(1L, 5L))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(motor.takeRequest().getBody().readUtf8())
                .contains("\"tipo\":\"RESULTADO_PROVA\"").contains("\"provaId\":\"99\"");
        verify(auditoria).registar(7L, 1L, "RESULTADO_PROVA", 5L);
    }

    @Test
    void umaAvaliacaoDeOutroPlanoNaoExiste() {
        StepVerifier.create(comPlano(plano("PT-1", avaliacao(5L, 99L))).resultado(1L, 6L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void umaAvaliacaoSemEnunciadoDiz409() {
        StepVerifier.create(comPlano(plano("PT-1", avaliacao(5L, null))).resultado(1L, 5L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
        verify(auditoria, never()).registar(any(), any(), any(), any());
    }

    @Test
    void umPlanoSemPautaNoMotorNaoChegaAPedirLigacao() {
        StepVerifier.create(comPlano(plano(null)).miniPauta(1L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .verify();
        assertThat(motor.getRequestCount()).isZero();
    }

    @Test
    void oMotorSemPdfGeradoDiz409() {
        motor.enqueue(new MockResponse().setResponseCode(409).setHeader("Content-Type", "application/problem+json")
                .setBody("{\"detail\":\"O PDF ainda não foi gerado.\"}"));

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("homologated");
                })
                .verify();
    }

    @Test
    void oMotorEmBaixoDiz503() {
        motor.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
    }

    @Test
    void umaLigacaoParaOutroAnfitriaoERecusada() {
        ligacao("https://exemplo-malicioso.test/x.pdf");
        pdf();

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .verify();
        assertThat(motor.getRequestCount()).isOne();
    }

    @Test
    void umaLigacaoExpiradaDiz502() {
        ligacao(motor.url("/relatorios/x.pdf?exp=1&sig=abc").toString());
        motor.enqueue(new MockResponse().setResponseCode(403));

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).contains("no longer valid");
                })
                .verify();
    }

    @Test
    void umaRespostaQueNaoEPdfDiz502() {
        ligacao(motor.url("/relatorios/x.pdf?exp=1&sig=abc").toString());
        motor.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>erro</html>"));

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .verify();
    }

    @Test
    void semMotorConfiguradoDiz503() {
        properties.setEnabled(false);

        StepVerifier.create(comPlano(plano("PT-1")).miniPauta(1L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .verify();
        assertThat(motor.getRequestCount()).isZero();
    }
}
