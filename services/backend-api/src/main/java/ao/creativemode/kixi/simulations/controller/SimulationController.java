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
        return accountScoped(
                service::findAllActive,
                service::findAllActiveForAccount
        )
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SimulationResponse>>> findAllTrashed() {
        return service.findAllTrashed()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> findById(@PathVariable Long id) {
        return accountScoped(
                () -> service.findById(id).flux(),
                accountId -> service.findByIdForAccount(id, accountId).flux()
        )
                .next()
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
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
                .flatMap(tuple -> resultService.findResult(id, tuple.getT1(), tuple.getT2()))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/submit")
    public Mono<ResponseEntity<SimulationResultResponse>> submit(@PathVariable Long id) {
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
                .flatMap(tuple -> submissionService.submit(id, tuple.getT1(), tuple.getT2())
                        .then(resultService.findResult(id, tuple.getT1(), tuple.getT2())))
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> create(@Valid @RequestBody SimulationRequest dto) {
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
                .flatMap(tuple -> tuple.getT2()
                        ? service.create(dto)
                        : service.createForAccount(dto, tuple.getT1()))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SimulationRequest dto) {
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
                .flatMap(tuple -> tuple.getT2()
                        ? service.update(id, dto)
                        : service.updateForAccount(id, dto, tuple.getT1()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/{id}/permanent")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private <T> Flux<T> accountScoped(
            java.util.function.Supplier<Flux<T>> staffQuery,
            java.util.function.Function<Long, Flux<T>> accountQuery
    ) {
        return currentAccountService.requiredAccountId()
                .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
                .flatMapMany(tuple -> tuple.getT2()
                        ? staffQuery.get()
                        : accountQuery.apply(tuple.getT1()));
    }
}
