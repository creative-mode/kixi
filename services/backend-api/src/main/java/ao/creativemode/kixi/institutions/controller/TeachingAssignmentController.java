package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentRequest;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentResponse;
import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * REST controller for teaching assignments (ADMIN, plus GET /me for TEACHER,
 * enforced in the security config).
 *
 * Base path: /api/v1/teaching-assignments
 */
@RestController
@RequestMapping("/api/v1/teaching-assignments")
public class TeachingAssignmentController {

    private final TeachingAssignmentService service;
    private final CurrentAccountService currentAccountService;

    public TeachingAssignmentController(
        TeachingAssignmentService service,
        CurrentAccountService currentAccountService
    ) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    @GetMapping
    public Mono<ResponseEntity<List<TeachingAssignmentResponse>>> listAllActive() {
        return service.findAllActive().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<TeachingAssignmentResponse>>> listTrashed() {
        return service.findAllDeleted().collectList().map(ResponseEntity::ok);
    }

    /** The classes and subjects the signed-in account teaches. */
    @GetMapping("/me")
    public Mono<ResponseEntity<List<TeachingAssignmentResponse>>> listMine() {
        return currentAccountService
            .requiredAccountId()
            .flatMapMany(service::findMineForAccount)
            .collectList()
            .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TeachingAssignmentResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<TeachingAssignmentResponse>> create(
        @Valid @RequestBody TeachingAssignmentRequest request,
        UriComponentsBuilder uriBuilder
    ) {
        return service.create(request)
            .map(assignment -> {
                URI location = uriBuilder
                    .path("/api/v1/teaching-assignments/{id}")
                    .buildAndExpand(assignment.id())
                    .toUri();
                return ResponseEntity.created(location).body(assignment);
            });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<TeachingAssignmentResponse>> update(
        @PathVariable Long id,
        @Valid @RequestBody TeachingAssignmentRequest request
    ) {
        return service.update(id, request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id).thenReturn(ResponseEntity.noContent().build());
    }
}
