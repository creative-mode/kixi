package ao.creativemode.kixi.simulations.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ao.creativemode.kixi.simulations.dto.simulation.SimulationRequest;
import jakarta.validation.Valid;
import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.simulations.dto.simulationresult.SimulationResultResponse;
import ao.creativemode.kixi.simulations.service.SimulationResultService;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import ao.creativemode.kixi.simulations.service.QuestionFeedbackService;
import ao.creativemode.kixi.simulations.service.SimulationAuditService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping({"/api/v1/simulations", "/api/simulations"})
public class SimulationController {

    private final SimulationService service;
    private final SimulationResultService resultService;
    private final CurrentAccountService currentAccountService;
    private final SimulationSubmissionService submissionService;
    private final QuestionFeedbackService feedbackService;

    public SimulationController(
            SimulationService service,
            SimulationResultService resultService,
            CurrentAccountService currentAccountService,
            SimulationSubmissionService submissionService,
            QuestionFeedbackService feedbackService
    ) {
        this.service = service;
        this.resultService = resultService;
        this.currentAccountService = currentAccountService;
        this.submissionService = submissionService;
        this.feedbackService = feedbackService;
    }

    /**
     * Optional filters ({@code statementId}, {@code status}) and paging ({@code page} from 0, {@code size} up
     * to 200). Without {@code size} the whole list is returned, as before; {@code X-Total-Count} carries how
     * many match for the caller.
     */
    @GetMapping
    public Mono<ResponseEntity<List<SimulationResponse>>> findAll(
            @RequestParam(required = false) Long statementId,
            @RequestParam(required = false) SimulationStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return authorizedContext().flatMap(context -> context.admin() || context.teacher()
                ? service.findAllAuthorized(context.accountId(), context.admin(), context.teacher(),
                        statementId, status, page, size)
                : service.findAllActiveForAccount(context.accountId())
                        .filter(item -> (statementId == null || (item.statement() != null
                                && statementId.equals(item.statement().id())))
                                && (status == null || status == item.status()))
                        .collectList()
                        .map(items -> new SimulationService.SimulationPage(items.size(), items)))
                .map(result -> ResponseEntity.ok()
                        .header("X-Total-Count", String.valueOf(result.total()))
                        .body(result.items()));
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SimulationResponse>>> findAllTrashed() {
        return authorizedContext().flatMapMany(context -> service.findAllTrashedAuthorized(
                context.accountId(), context.admin(), context.teacher()))
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> findById(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(service.findById(id)))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.<SimulationResponse>notFound().build());
    }

    /**
     * The result of a simulation, with the answer key.
     *
     * <p>The only route that shows a student which option was right. The key used
     * to reach them through {@code GET /statements/{id}/full}; #106 closed that
     * and this is where it went instead.
     *
     * <p>Before the simulation is {@code FINISHED} this answers 409. The student
     * is allowed to have a result, they have not finished earning it, and the
     * status says so in words rather than leaving them to guess whether the 409
     * meant that or that the simulation is gone.
     */
    @GetMapping("/{id}/result")
    public Mono<ResponseEntity<SimulationResultResponse>> findResult(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(resultService.findResult(id, context.accountId(), context.admin() || context.teacher())))
                .map(ResponseEntity::ok);
    }

    /** Correção de uma questão no treino (kixi#729): só simulações livres do próprio aluno, ainda em curso. */
    @PostMapping("/{id}/questions/{questionId}/check")
    public Mono<ResponseEntity<QuestionFeedbackService.Feedback>> check(
            @PathVariable Long id,
            @PathVariable Long questionId,
            @RequestBody(required = false) java.util.Map<String, Long> body) {
        Long selected = body == null ? null : body.get("selectedOptionId");
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(feedbackService.check(id, questionId, selected, context.accountId())))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/submit")
    public Mono<ResponseEntity<SimulationResultResponse>> submit(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(submissionService.submit(id, context.accountId(), context.admin() || context.teacher())
                        .then(resultService.findResult(id, context.accountId(), context.admin() || context.teacher()))))
                .map(ResponseEntity::ok);
    }

    /**
     * Cria uma simulação. Sempre para o dono (kixi#596): ninguém, nem ADMIN nem professor, abre a simulação de um aluno em
     * nome dele; a conta INTEGRATION do motor SchoolOps (no IntegrationScope) abre a do aluno que indica, e fica no trilho.
     */
    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> create(@Valid @RequestBody SimulationRequest dto) {
        return currentAccountService.requiredAccountId()
                .zipWith(role("INTEGRATION"))
                .flatMap(tuple -> tuple.getT2()
                        ? service.createForIntegration(dto, tuple.getT1())
                        : service.createForAccount(dto, tuple.getT1()))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SimulationRequest dto) {
        return authorizedContext().flatMap(context -> context.integration()
                ? service.updateForIntegration(id, dto, context.accountId())
                : authorized(id, context)
                        .then(Mono.defer(() -> service.updateForAccount(id, dto, context.accountId()))))
                .map(ResponseEntity::ok);
    }

    /**
     * Apagar, restaurar e purgar uma simulação mudam o que o recálculo das notas vê (kixi#596): só o administrador, com
     * motivo, e o acto fica no trilho. O purge é em duas etapas: já estar na lixeira e repetir o id em {@code confirm}.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return audited(id, false, reason, (actor, why) -> service.softDelete(id, actor, why))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return audited(id, true, reason, (actor, why) -> service.restore(id, actor, why))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/{id}/permanent")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id, @RequestParam(required = false) String reason,
            @RequestParam(required = false) String confirm) {
        SimulationAuditService.requirePurgeConfirmation(id, confirm);
        return audited(id, true, reason, (actor, why) -> service.hardDelete(id, actor, why))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private Mono<Void> audited(Long id, boolean includeDeleted, String reason,
            java.util.function.BiFunction<Long, String, Mono<Void>> mutationAndAudit) {
        return Mono.defer(() -> {
            String why = SimulationAuditService.requireReason(reason);
            return authorizedContext().flatMap(context -> {
                if (!context.admin()) {
                    return Mono.<Void>error(ApiException.forbidden("Only ADMIN can manage simulations"));
                }
                return service.authorize(id, context.accountId(), true, false, includeDeleted)
                        .then(Mono.defer(() -> mutationAndAudit.apply(context.accountId(), why)));
            });
        });
    }

    private Mono<Context> authorizedContext() {
        return currentAccountService.requiredAccountId()
                .zipWith(role("ADMIN"))
                .zipWith(role("TEACHER"))
                .zipWith(role("INTEGRATION"))
                .map(tuple -> new Context(tuple.getT1().getT1().getT1(), tuple.getT1().getT1().getT2(),
                        tuple.getT1().getT2(), tuple.getT2()));
    }

    private Mono<Boolean> role(String name) {
        Mono<Boolean> result = currentAccountService.hasAnyRole(name);
        return result == null ? Mono.just(false) : result.defaultIfEmpty(false);
    }

    private Mono<Void> authorized(Long id, Context context) {
        return authorized(id, context, false);
    }

    private Mono<Void> authorized(Long id, Context context, boolean includeDeleted) {
        Mono<?> result = service.authorize(id, context.accountId(), context.admin(), context.teacher(), includeDeleted);
        return (result == null ? Mono.empty() : result).then();
    }

    private record Context(Long accountId, boolean admin, boolean teacher, boolean integration) {}
}
