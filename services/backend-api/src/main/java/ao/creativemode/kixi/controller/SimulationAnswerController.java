package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.schoolyears.SchoolYearRequest;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.dto.simulationanswer.SimulationAnswerResponse;
import ao.creativemode.kixi.service.SimulationAnswerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/simulation-answers")
public class SimulationAnswerController {

    private final SimulationAnswerService service;

    public SimulationAnswerController(SimulationAnswerService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<SimulationAnswerResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SimulationAnswerResponse>>> listTrashed() {
        return service.findAllTrashed()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SimulationAnswerResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<SimulationAnswerResponse>> create(@Valid @RequestBody SimulationAnswerRequest request, UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/simulation-answers/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }


    @PutMapping("/{id}")
    public Mono<ResponseEntity<SimulationAnswerResponse>> update(@PathVariable Long id, @Valid @RequestBody SimulationAnswerRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }



}
