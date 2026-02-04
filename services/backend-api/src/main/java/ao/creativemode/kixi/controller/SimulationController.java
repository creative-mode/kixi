package ao.creativemode.kixi.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import jakarta.validation.Valid;
import ao.creativemode.kixi.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.service.SimulationService;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

    private final SimulationService service;

    public SimulationController(SimulationService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SimulationResponse>>> findAll() {
        return service.findAllActive()
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
        return service.findById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> create(@Valid @RequestBody SimulationRequest dto) {
        return service.create(dto)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SimulationRequest dto) {
        return service.update(id, dto)
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
}
