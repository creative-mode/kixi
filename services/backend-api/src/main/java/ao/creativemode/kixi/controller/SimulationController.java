package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.simulation.SimulationRequest;
import ao.creativemode.kixi.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.dto.simulation.SimulationUpdateRequest;
import ao.creativemode.kixi.model.Simulation;
import ao.creativemode.kixi.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {

    public final SimulationService service;

    public SimulationController(SimulationService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SimulationResponse>>> listAllActive(){
        return service.findAllActive().collectList().map(ResponseEntity::ok);

    }

    @GetMapping("/trached")
    public Mono<ResponseEntity<List<SimulationResponse>>> listTrached(){
        return service.findAllActive().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Simulation>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(simulation -> ResponseEntity.ok(simulation)).switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationResponse>> create(
            @RequestBody @Valid SimulationRequest request) {

        return service.create(request)
                .map(simulation -> ResponseEntity.status(HttpStatus.CREATED).body(simulation));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationResponse>> update(
            @PathVariable Long id,
            @RequestBody @Valid SimulationUpdateRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @DeleteMapping("/{id}/hard")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.noContent().build());
    }




}
