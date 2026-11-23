package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/** A resposta da exportação: um PDF, inline, com o nome do documento. */
class TermPlanReportControllerTest {

    private final SchoolOpsReports reports = mock(SchoolOpsReports.class);
    private final TermPlanReportController controller = new TermPlanReportController(reports);

    @Test
    void aMiniPautaVoltaComoPdfInline() {
        when(reports.miniPauta(1L)).thenReturn(Mono.just("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> resposta = controller.miniPauta(1L).block();

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline; filename=\"mini-pauta-1.pdf\"");
        assertThat(new String(resposta.getBody(), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4");
    }

    @Test
    void oResultadoDaProvaVoltaComoPdfComOsDoisIdsNoNome() {
        when(reports.resultado(1L, 5L)).thenReturn(Mono.just("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> resposta = controller.resultado(1L, 5L).block();

        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline; filename=\"resultado-1-5.pdf\"");
        assertThat(resposta.getBody()).isNotEmpty();
    }

    @Test
    void aPautaDaTurmaVoltaComoPdfInlineESemCache() {
        when(reports.pautaDaTurma(1L)).thenReturn(Mono.just("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> resposta = controller.pautaDaTurma(1L).block();

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(resposta.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline; filename=\"pauta-turma-1.pdf\"");
        assertThat(resposta.getBody()).isNotEmpty();
    }
}
