package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.questionimage.QuestionImageRequest;
import ao.creativemode.kixi.dto.questionimage.QuestionImageResponse;
import ao.creativemode.kixi.service.QuestionImageService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/question-images")
public class QuestionImageController {

    private final QuestionImageService service;

    public QuestionImageController(QuestionImageService service) {
        this.service = service;
    }

    /**
     * Retrieves all active question images.
     */
    @GetMapping
    public Mono<ResponseEntity<List<QuestionImageResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves images associated with a specific question.
     */
    @GetMapping("/question/{questionId}")
    public Mono<ResponseEntity<List<QuestionImageResponse>>> listByQuestion(@PathVariable Long questionId) {
        return service.findByQuestionId(questionId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted images.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<QuestionImageResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active image by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<QuestionImageResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new question image entry by uploading a file.
     * Consumes multipart/form-data to receive both metadata and the image file.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<QuestionImageResponse>> create(
            @RequestPart("data") @Valid QuestionImageRequest request,
            @RequestPart("file") Mono<FilePart> filePartMono,
            UriComponentsBuilder uriBuilder) {

        return service.createWithFile(request, filePartMono)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/question-images/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active image's metadata.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<QuestionImageResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody QuestionImageRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes an image.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted image.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Permanently deletes an image from the database and storage.
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}