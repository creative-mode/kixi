package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    /**
     * Retrieves all active (non-deleted) courses.
     */
    @GetMapping
    public Mono<ResponseEntity<List<CourseResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) courses.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<CourseResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active course by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<CourseResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new course.
     */
    @PostMapping
    public Mono<ResponseEntity<CourseResponse>> create(
            @Valid @RequestBody CourseRequest request,
            UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/courses/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active course.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<CourseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody CourseRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a course (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted course from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes a soft-deleted course.
     * Only deleted courses can be permanently removed.
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
