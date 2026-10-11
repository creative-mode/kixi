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
import ao.creativemode.kixi.simulations.service.SimulationService;
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

    public SimulationController(
            SimulationService service,
            SimulationResultService resultService,
            CurrentAccountService currentAccountService,
            SimulationSubmissionService submissionService
    ) {
        this.service = service;
        this.resultService = resultService;
        this.currentAccountService = currentAccountService;
        this.submissionService = submissionService;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SimulationResponse>>> findAll() {
        return authorizedContext().flatMapMany(context -> context.admin() || context.teacher()
                ? service.findAllAuthorized(context.accountId(), context.admin(), context.teacher())
                : service.findAllActiveForAccount(context.accountId()))
                .collectList()
                .map(ResponseEntity::ok);
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

    @PostMapping("/{id}/submit")
    public Mono<ResponseEntity<SimulationResultResponse>> submit(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(submissionService.submit(id, context.accountId(), context.admin() || context.teacher())
                        .then(resultService.findResult(id, context.accountId(), context.admin() || context.teacher()))))
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> create(@Valid @RequestBody SimulationRequest dto) {
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN"))
                .zipWith(currentAccountService.hasAnyRole("TEACHER"))
                .flatMap(tuple -> tuple.getT1().getT2()
                        ? service.create(dto, tuple.getT1().getT1(), true)
                        : tuple.getT2()
                            ? service.create(dto, tuple.getT1().getT1(), false)
                            : service.createForAccount(dto, tuple.getT1().getT1()))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SimulationRequest dto) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(context.admin() || context.teacher() ? service.update(id, dto)
                        : service.updateForAccount(id, dto, context.accountId())))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context)
                .then(service.softDelete(id)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> authorized(id, context, true)
                .then(service.restore(id)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/{id}/permanent")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return authorizedContext().flatMap(context -> service.authorize(id, context.accountId(), context.admin(), context.teacher(), true)
                .then(service.hardDelete(id)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private Mono<Context> authorizedContext() {
        return currentAccountService.requiredAccountId()
                .zipWith(role("ADMIN"))
                .zipWith(role("TEACHER"))
                .map(tuple -> new Context(tuple.getT1().getT1(), tuple.getT1().getT2(), tuple.getT2()));
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

    private record Context(Long accountId, boolean admin, boolean teacher) {}
}
