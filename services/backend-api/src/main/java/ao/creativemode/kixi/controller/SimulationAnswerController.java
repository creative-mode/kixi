package ao.creativemode.kixi.controller;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.service.SimulationAnswerService;
import ao.creativemode.kixi.service.CurrentAccountService;
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

    public SimulationAnswerController(
        SimulationAnswerService service,
        CurrentAccountService currentAccountService
    ) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Retrieves all active (non-deleted) simulation answers.
     */
    @GetMapping
    public Mono<
        ResponseEntity<List<SimulationAnswerResponse>>
    > listAllActive() {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
            .flatMapMany(tuple -> tuple.getT2()
                ? service.findAllActive()
                : service.findAllActiveForAccount(tuple.getT1()))
            .collectList()
            .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) simulation answers.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SimulationAnswerResponse>>> listTrashed() {
        return service.findAllDeleted().collectList().map(ResponseEntity::ok);
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
            .flatMap(tuple -> tuple.getT2()
                ? service.findByIdActive(id)
                : service.findByIdActiveForAccount(id, tuple.getT1()))
            .map(ResponseEntity::ok);
    }

    /**
     * Creates a new simulation answer.
     */
    @PostMapping
    public Mono<ResponseEntity<SimulationAnswerResponse>> create(
        @Valid @RequestBody SimulationAnswerRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
            .flatMap(tuple -> tuple.getT2()
                ? service.create(request)
                : service.createForAccount(request, tuple.getT1()))
            .map(created -> {
                URI location = uriBuilder
                    .path("/api/v1/simulation-answers/{id}")
                    .buildAndExpand(created.id())
                    .toUri();

                return ResponseEntity.created(location).body(created);
            });
    }

    /**
     * Updates an existing active simulation answer.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationAnswerResponse>> update(
        @PathVariable Long id,
        @Valid @RequestBody SimulationAnswerRequest request
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
            .flatMap(tuple -> tuple.getT2()
                ? service.update(id, request)
                : service.updateForAccount(id, request, tuple.getT1()))
            .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a simulation answer (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service
            .softDelete(id)
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted simulation answer from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id).thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes a simulation answer (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service
            .hardDelete(id)
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
