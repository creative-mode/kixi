package ao.creativemode.kixi.register;

import ao.creativemode.kixi.shared.exception.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * O registo escolar para o motor SchoolOps (kixi#501). Só {@code ADMIN} e a conta {@code INTEGRATION} escrevem e leem
 * aqui; a leitura de um professor ou de um aluno é do ecrã de pautas e não passa por estas rotas. Todas as escritas são
 * idempotentes: o motor repete-as quando o Camunda reentrega um job.
 */
@RestController
@RequestMapping("/api/v1/school-register")
public class SchoolRegisterController {

    private final SchoolRegisterService service;

    public SchoolRegisterController(SchoolRegisterService service) {
        this.service = service;
    }

    @PutMapping("/exams/{statementId}/grades")
    public Mono<ResponseEntity<Void>> replaceGrades(@PathVariable Long statementId, @RequestBody Map<String, Object> body) {
        return service.replaceExamGrades(statementId, list(body.get("grades"))).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/exams/{statementId}/grades")
    public Mono<ResponseEntity<List<Map<String, Object>>>> grades(@PathVariable Long statementId) {
        return service.examGrades(statementId).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/exams/{statementId}/appeals/{appealId}")
    public Mono<ResponseEntity<Map<String, Object>>> openAppeal(@PathVariable Long statementId, @PathVariable String appealId,
            @RequestBody Map<String, Object> appeal) {
        return service.openAppeal(statementId, appealId, appeal).map(ResponseEntity::ok);
    }

    @GetMapping("/exams/{statementId}/appeals/pending-count")
    public Mono<ResponseEntity<Map<String, Object>>> pendingAppeals(@PathVariable Long statementId) {
        return service.pendingAppeals(statementId).map(count -> ResponseEntity.ok(Map.<String, Object>of("count", count)));
    }

    @PostMapping("/exams/{statementId}/appeals/{appealId}/resolve")
    public Mono<ResponseEntity<Void>> resolve(@PathVariable Long statementId, @PathVariable String appealId) {
        return service.resolveAppeal(statementId, appealId).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/exams/{statementId}/appeals/{appealId}/rectify")
    public Mono<ResponseEntity<Void>> rectify(@PathVariable Long statementId, @PathVariable String appealId,
            @RequestBody Map<String, Object> body) {
        return service.rectify(statementId, appealId, Long.valueOf(String.valueOf(body.get("alunoId"))),
                        decimal(body, "pontosActuais"), decimal(body, "pontosFinais"), String.valueOf(body.getOrDefault("decisao", "")))
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PutMapping("/term-sheets/{sheetId}")
    public Mono<ResponseEntity<Void>> registerTermSheet(@PathVariable String sheetId, @RequestBody Map<String, Object> body) {
        return service.registerTermSheet(sheetId, map(body.get("contexto")), map(body.get("estatisticas")), list(body.get("entradas")))
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PutMapping("/term-sheets/{sheetId}/components/{component}")
    public Mono<ResponseEntity<Void>> registerComponent(@PathVariable String sheetId, @PathVariable String component,
            @RequestBody Map<String, Object> body) {
        return service.registerComponent(sheetId, component, list(body.get("entradas"))).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/term-sheets/{sheetId}/close")
    public Mono<ResponseEntity<Void>> close(@PathVariable String sheetId) {
        return service.setSheetStatus(sheetId, "FECHADA").thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/term-sheets/{sheetId}/annul")
    public Mono<ResponseEntity<Void>> annul(@PathVariable String sheetId) {
        return service.setSheetStatus(sheetId, "ANULADA").thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/term-sheets/{sheetId}")
    public Mono<ResponseEntity<Map<String, Object>>> termSheet(@PathVariable String sheetId) {
        return service.termSheet(sheetId).map(ResponseEntity::ok);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw ApiException.badRequest("A list was expected");
        }
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        try {
            return new BigDecimal(String.valueOf(body.get(key)));
        } catch (NumberFormatException | NullPointerException error) {
            throw ApiException.badRequest(key + " must be a number");
        }
    }
}
