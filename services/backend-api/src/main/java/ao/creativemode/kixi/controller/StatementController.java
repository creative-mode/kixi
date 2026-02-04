package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.statement.StatementRequest;
import ao.creativemode.kixi.dto.statement.StatementResponse;
import ao.creativemode.kixi.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;


@RestController
@RequestMapping("/statements")
public class StatementController {

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }


    @GetMapping
    public Mono<ResponseEntity<List<StatementResponse>>> listAllActive() {
        return service.listAllActive()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trashed")
    public Mono<ResponseEntity<List<StatementResponse>>> listTrashed() {
        return service.listTrashed()
                .map(ResponseEntity::ok);
    }

 
    @GetMapping("/{id}")
    public Mono<ResponseEntity<StatementResponse>> getById(@PathVariable Long id) {
        return service.getById(id)
                .map(ResponseEntity::ok);
    }


    @PostMapping
    public Mono<ResponseEntity<StatementResponse>> create(@Valid @RequestBody StatementRequest request) {
        return service.create(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<StatementResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody StatementRequest request) {
        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PatchMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @DeleteMapping("/{id}/hard")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}