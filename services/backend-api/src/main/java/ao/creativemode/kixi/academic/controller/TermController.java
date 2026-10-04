package ao.creativemode.kixi.academic.controller;

import ao.creativemode.kixi.academic.dto.term.TermRequest;
import ao.creativemode.kixi.academic.dto.term.TermResponse;
import ao.creativemode.kixi.academic.service.TermService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
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
    public Mono<ResponseEntity<TermResponse>> create(
            @Valid @RequestBody TermRequest request,
            UriComponentsBuilder uriBuilder
    ) {
        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/terms/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
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
        return service.restore(id).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id).thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}