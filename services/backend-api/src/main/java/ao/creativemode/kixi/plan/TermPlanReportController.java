package ao.creativemode.kixi.plan;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Exportação da pauta em PDF para o gestor (kixi#622): a pauta da disciplina e o resultado de uma prova, de cada vez,
 * com uma ligação nova pedida ao motor e sem guardar o PDF nem a ligação.
 *
 * <p>Quem entra é decidido por {@code SecurityConfig} (ADMIN, TEACHER, COORDINATOR) e pelo plano
 * ({@link TermPlanService#get}): o professor da turma e disciplina e o coordenador da instituição; o aluno não.
 */
@RestController
@RequestMapping("/api/v1/term-plans")
public class TermPlanReportController {

    private final SchoolOpsReports reports;

    public TermPlanReportController(SchoolOpsReports reports) {
        this.reports = reports;
    }

    @GetMapping(value = "/{id}/reports/mini-pauta", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> miniPauta(@PathVariable Long id) {
        return reports.miniPauta(id).map(pdf -> responder(pdf, "mini-pauta-" + id + ".pdf"));
    }

    @GetMapping(value = "/{id}/assessments/{assessmentId}/reports/result", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> resultado(@PathVariable Long id, @PathVariable Long assessmentId) {
        return reports.resultado(id, assessmentId).map(pdf -> responder(pdf, "resultado-" + id + "-" + assessmentId + ".pdf"));
    }

    private static ResponseEntity<byte[]> responder(byte[] pdf, String nome) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
                .body(pdf);
    }
}
