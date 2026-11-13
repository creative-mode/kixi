package ao.creativemode.kixi.moderation.controller;

import ao.creativemode.kixi.moderation.dto.AuditEntryResponse;
import ao.creativemode.kixi.moderation.dto.DecisionRequest;
import ao.creativemode.kixi.moderation.dto.ModerationReportResponse;
import ao.creativemode.kixi.moderation.service.ModerationDecisionService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A moderação do administrador (kixi#591): a fila de denúncias, a decisão e a auditoria. Toda a árvore
 * {@code /api/v1/moderation/**} é ADMIN em {@code SecurityConfig}; as denúncias que os alunos fazem continuam em
 * {@link ModerationController}.
 */
@RestController
@RequestMapping("/api/v1/moderation")
public class ModerationAdminController {
    private final ModerationDecisionService service;
    private final CurrentAccountService current;

    public ModerationAdminController(ModerationDecisionService service, CurrentAccountService current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping("/reports")
    public Flux<ModerationReportResponse> queue(@RequestParam(required = false) String status) {
        return service.queue(status);
    }

    @PostMapping("/reports/{id}/ignore")
    public Mono<ModerationReportResponse> ignore(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body) {
        return decide(id, ModerationDecisionService.IGNORED, body);
    }

    @PostMapping("/reports/{id}/hide")
    public Mono<ModerationReportResponse> hide(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body) {
        return decide(id, ModerationDecisionService.HIDDEN, body);
    }

    @PostMapping("/reports/{id}/suspend")
    public Mono<ModerationReportResponse> suspend(@PathVariable Long id, @RequestBody(required = false) DecisionRequest body) {
        return decide(id, ModerationDecisionService.SUSPENDED, body);
    }

    @GetMapping("/audit")
    public Flux<AuditEntryResponse> audit() {
        return service.audit();
    }

    private Mono<ModerationReportResponse> decide(Long id, String decision, DecisionRequest body) {
        return current.requiredAccountId()
                .flatMap(actor -> service.decide(actor, id, decision, body == null ? null : body.note()));
    }
}
