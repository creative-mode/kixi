package ao.creativemode.kixi.simulations.controller;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerAiGradeRequest;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerGradeRequest;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.PlanRoomLookup;
import ao.creativemode.kixi.simulations.service.SimulationAuditService;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/simulation-answers")
public class SimulationAnswerController {

    private final SimulationAnswerService service;
    private final CurrentAccountService currentAccountService;
    private final SimulationSubmissionService submissionService;

    /** Opcional: sem ele (testes unitários) não há provas de plano a proteger. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanRoomLookup planRooms;

    public SimulationAnswerController(
        SimulationAnswerService service,
        CurrentAccountService currentAccountService,
        SimulationSubmissionService submissionService
    ) {
        this.service = service;
        this.currentAccountService = currentAccountService;
        this.submissionService = submissionService;
    }

    /**
     * Retrieves all active (non-deleted) simulation answers.
     */
    @GetMapping
    public Mono<
        ResponseEntity<List<SimulationAnswerResponse>>
    > listAllActive() {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMapMany(tuple -> tuple.getT1().getT2() || tuple.getT2()
                ? service.findAllActiveForStaff(tuple.getT1().getT1(), tuple.getT1().getT2())
                : service.findAllActiveForAccount(tuple.getT1().getT1()))
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) simulation answers.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SimulationAnswerResponse>>> listTrashed() {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMapMany(tuple -> tuple.getT1().getT2() || tuple.getT2()
                ? service.findAllDeletedForStaff(tuple.getT1().getT1(), tuple.getT1().getT2())
                : service.findAllDeletedForAccount(tuple.getT1().getT1()))
            .collectList().map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active simulation answer by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<SimulationAnswerResponse>> getById(
        @PathVariable Long id
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
            .flatMap(tuple -> authorize(id).then(tuple.getT2()
                ? service.findByIdActive(id)
                : service.findByIdActiveForAccount(id, tuple.getT1())))
            .map(ResponseEntity::ok);
    }

    /**
     * Creates a new simulation answer. Sempre em nome do dono da simulação (kixi#596): o staff não responde em nome do aluno;
     * quem não é o dono recebe 403, mesmo sendo ADMIN ou professor.
     */
    @PostMapping
    public Mono<ResponseEntity<SimulationAnswerResponse>> create(
        @Valid @RequestBody SimulationAnswerRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(role("INTEGRATION"))
            // O motor entrega a resposta de um aluno com a conta dele (INTEGRATION, no IntegrationScope); toda a outra conta,
            // ADMIN e professor incluídos, só responde na sua própria simulação (kixi#596).
            .flatMap(tuple -> tuple.getT2()
                ? service.createForIntegration(request, tuple.getT1())
                : authorizeSimulation(request.simulationId())
                    .then(Mono.defer(() -> service.createForAccount(request, tuple.getT1()))))
            .map(created -> {
                URI location = uriBuilder
                    .path("/api/v1/simulation-answers/{id}")
                    .buildAndExpand(created.id())
                    .toUri();

                return ResponseEntity.created(location).body(created);
            });
    }

    /**
     * Updates an existing active simulation answer, só pelo dono da simulação (kixi#596).
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationAnswerResponse>> update(
        @PathVariable Long id,
        @Valid @RequestBody SimulationAnswerRequest request
    ) {
        return currentAccountService.requiredAccountId()
            // Editar é sempre pelo dono (kixi#596). O motor não edita respostas (o IntegrationScope não o prevê): entrega-as
            // (POST) e corrige a nota por PATCH .../grade e PUT .../ai-grade.
            .flatMap(accountId -> authorize(id)
                .then(Mono.defer(() -> service.updateForAccount(id, request, accountId))))
            .map(ResponseEntity::ok);
    }

    @PatchMapping("/{id}/grade")
    public Mono<ResponseEntity<SimulationAnswerResponse>> grade(
        @PathVariable Long id,
        @Valid @RequestBody SimulationAnswerGradeRequest request,
        @RequestParam(defaultValue = "false") boolean rectify
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> requireNotCorrectedByTheFlow(id, rectify, tuple.getT2() && !tuple.getT1().getT2())
                .then(Mono.defer(() -> submissionService.grade(id, request.scoreObtained(),
                    tuple.getT1().getT1(), tuple.getT1().getT2(), rectify))))
            .map(ResponseEntity::ok);
    }

    /**
     * As respostas das provas de um plano do trimestre são corrigidas pela IA no fluxo do motor (kixi#542): o professor não as
     * corrige à mão. O administrador, que é também quem opera o motor (o do servidor ainda usa uma conta ADMIN), a conta de
     * integração e a rectificação de uma reclamação deferida continuam a poder gravar.
     */
    private Mono<Void> requireNotCorrectedByTheFlow(Long answerId, boolean rectify, boolean teacherOnly) {
        if (rectify || !teacherOnly || planRooms == null) return Mono.empty();
        return planRooms.isPlanRoomAnswer(answerId).flatMap(planned -> planned
            ? Mono.<Void>error(ApiException.conflict(
                "As respostas das provas do plano do trimestre são corrigidas pela IA no fluxo; a nota só muda por reclamação"))
            : Mono.<Void>empty());
    }

    /**
     * A correção que a IA fez no fluxo do motor SchoolOps (kixi#542). Só o administrador e a conta do motor (INTEGRATION),
     * por regra de segurança; repetir o mesmo corpo não altera nada.
     */
    @PutMapping("/{id}/ai-grade")
    public Mono<ResponseEntity<SimulationAnswerResponse>> aiGrade(
        @PathVariable Long id,
        @Valid @RequestBody SimulationAnswerAiGradeRequest request
    ) {
        return submissionService.aiGrade(id, request.scoreObtained(), request.feedback(), request.level(), request.confidence())
            .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a simulation answer (moves it to trash). Só o administrador, com motivo, e fica auditado (kixi#596).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return audited(id, false, reason, (actor, why) -> service.softDelete(id, actor, why))
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted simulation answer from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return audited(id, true, reason, (actor, why) -> service.restore(id, actor, why))
            .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Permanently deletes a simulation answer. Irreversível, por isso em duas etapas (kixi#596): a resposta tem de estar
     * antes na lixeira (um apagar auditado, à parte) e o pedido repete o id em {@code confirm}.
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id, @RequestParam(required = false) String reason,
            @RequestParam(required = false) String confirm) {
        SimulationAuditService.requirePurgeConfirmation(id, confirm);
        return audited(id, true, reason, (actor, why) -> service.hardDelete(id, actor, why))
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Apagar, restaurar e purgar mudam o que o recálculo das notas vê (kixi#596): só o administrador, com motivo, e o acto
     * fica no trilho. O professor corrige pela nota (PATCH .../grade), nunca mexendo nas respostas do aluno.
     */
    private Mono<Void> audited(Long answerId, boolean includeDeleted, String reason,
            java.util.function.BiFunction<Long, String, Mono<Void>> mutationAndAudit) {
        return Mono.defer(() -> {
            String why = SimulationAuditService.requireReason(reason);
            return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN"))
                .flatMap(tuple -> {
                    if (!tuple.getT2()) {
                        return Mono.<Void>error(ApiException.forbidden("Only ADMIN can manage simulation answers"));
                    }
                    Long actor = tuple.getT1();
                    // A mutação e o registo da auditoria correm na mesma transação do serviço: se o registo falhar, a
                    // mutação desfaz-se (nunca há resposta apagada ou purgada sem trilho).
                    return service.authorizeAnswer(answerId, actor, true, false, includeDeleted)
                        .then(Mono.defer(() -> mutationAndAudit.apply(actor, why)));
                });
        });
    }

    private Mono<Boolean> role(String name) {
        Mono<Boolean> result = currentAccountService.hasAnyRole(name);
        return result == null ? Mono.just(false) : result.defaultIfEmpty(false);
    }

    private Mono<Void> authorize(Long answerId) {
        return authorize(answerId, false);
    }

    private Mono<Void> authorize(Long answerId, boolean includeDeleted) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> service.authorizeAnswer(answerId, tuple.getT1().getT1(),
                    tuple.getT1().getT2(), tuple.getT2(), includeDeleted));
    }

    private Mono<Void> authorizeSimulation(Long simulationId) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> service.authorizeSimulation(simulationId, tuple.getT1().getT1(),
                    tuple.getT1().getT2(), tuple.getT2()));
    }
}
