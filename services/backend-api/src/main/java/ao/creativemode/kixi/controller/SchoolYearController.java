package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.schoolyears.SchoolYearRequest;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.service.SchoolYearService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/school-years")
public class SchoolYearController {

    private final SchoolYearService service;

    public SchoolYearController(SchoolYearService service) {
        this.service = service;
    }

    /**
     * Retrieves all active (non-deleted) school years.
     */
    @GetMapping
    public Mono<ResponseEntity<List<SchoolYearResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) school years.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<SchoolYearResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active school year by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<SchoolYearResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new school year.
     */
    @PostMapping
    public Mono<ResponseEntity<SchoolYearResponse>> create(
            @Valid @RequestBody SchoolYearRequest request,
            UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/school-years/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active school year.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<SchoolYearResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SchoolYearRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a school year (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted school year from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes a school year (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}