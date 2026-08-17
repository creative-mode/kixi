package ao.creativemode.kixi.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import jakarta.validation.Valid;
import ao.creativemode.kixi.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.service.SimulationService;
import ao.creativemode.kixi.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

    private final SimulationService service;
    private final CurrentAccountService currentAccountService;

    public SimulationController(
            SimulationService service,
            CurrentAccountService currentAccountService
    ) {
        this.service = service;
        this.currentAccountService = currentAccountService;
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
