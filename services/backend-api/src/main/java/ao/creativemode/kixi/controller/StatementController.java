package ao.creativemode.kixi.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ao.creativemode.kixi.dto.statement.StatementRequest;
import ao.creativemode.kixi.dto.statement.StatementResponse;
import ao.creativemode.kixi.service.StatementService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/api/statements")
public class StatementController {

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }


    @GetMapping
    public Mono<ResponseEntity<List<StatementResponse>>> listAllActive() {
        return service.listAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/trashed")
    public Mono<ResponseEntity<List<StatementResponse>>> listTrashed() {
        return service.listTrashed()
                .collectList()
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