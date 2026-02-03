package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.term.TermRequest;
import ao.creativemode.kixi.dto.term.TermResponse;
import ao.creativemode.kixi.service.TermService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/terms")
public class TermController {

    private final TermService service;

    public TermController(TermService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<List<TermResponse>>> listAllActive() {
        return service.findAllActive().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<TermResponse>>> listTrashed() {
        return service.findAllDeleted().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TermResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<TermResponse>> create(@Valid @RequestBody TermRequest request) {
        return service.create(request).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<TermResponse>> update(@PathVariable Long id, @Valid @RequestBody TermRequest request) {
        return service.update(id, request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id).thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id).thenReturn(ResponseEntity.ok().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id).thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}