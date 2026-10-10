package ao.creativemode.kixi.simulations.controller;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerGradeRequest;
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
     * Creates a new simulation answer.
     */
    @PostMapping
    public Mono<ResponseEntity<SimulationAnswerResponse>> create(
        @Valid @RequestBody SimulationAnswerRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN", "TEACHER"))
            .flatMap(tuple -> authorizeSimulation(request.simulationId()).then(tuple.getT2()
                ? service.create(request)
                : service.createForAccount(request, tuple.getT1())))
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
            .flatMap(tuple -> authorize(id).then(tuple.getT2()
                ? service.update(id, request)
                : service.updateForAccount(id, request, tuple.getT1())))
            .map(ResponseEntity::ok);
    }

    @PatchMapping("/{id}/grade")
    public Mono<ResponseEntity<SimulationAnswerResponse>> grade(
        @PathVariable Long id,
        @Valid @RequestBody SimulationAnswerGradeRequest request
    ) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .flatMap(tuple -> submissionService.grade(id, request.scoreObtained(), tuple.getT1(), tuple.getT2()))
            .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a simulation answer (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return staffLifecycleContext(id, false).then(Mono.defer(() -> service.softDelete(id)))
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted simulation answer from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return staffLifecycleContext(id, true).then(Mono.defer(() -> service.restore(id)))
            .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Permanently deletes a simulation answer (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return staffLifecycleContext(id, true).then(Mono.defer(() -> service.hardDelete(id)))
            .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    private Mono<Void> staffLifecycleContext(Long answerId, boolean includeDeleted) {
        return currentAccountService.requiredAccountId()
            .zipWith(currentAccountService.hasAnyRole("ADMIN"))
            .zipWith(currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> {
                boolean admin = tuple.getT1().getT2();
                boolean teacher = tuple.getT2();
                if (!admin && !teacher) {
                    return Mono.error(ao.creativemode.kixi.shared.exception.ApiException.forbidden(
                        "Only ADMIN or an assigned teacher can manage simulation answers"));
                }
                return service.authorizeAnswer(answerId, tuple.getT1().getT1(), admin, teacher, includeDeleted);
            });
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
