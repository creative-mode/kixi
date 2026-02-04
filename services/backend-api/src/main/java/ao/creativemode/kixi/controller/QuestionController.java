package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.question.QuestionRequest;
import ao.creativemode.kixi.dto.question.QuestionResponse;
import ao.creativemode.kixi.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionService service;

    public QuestionController(QuestionService service) {
        this.service = service;
    }

    /**
     * Retrieves all active (non-deleted) questions.
     */
    @GetMapping
    public Mono<ResponseEntity<List<QuestionResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) questions.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<QuestionResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active question by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<QuestionResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new question.
     */
    @PostMapping
    public Mono<ResponseEntity<QuestionResponse>> create(
            @Valid @RequestBody QuestionRequest request,
            UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/questions/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active question.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<QuestionResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody QuestionRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a question (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted question from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes a question (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}