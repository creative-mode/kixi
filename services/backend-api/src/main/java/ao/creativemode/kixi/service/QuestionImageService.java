package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.questionimage.QuestionImageRequest;
import ao.creativemode.kixi.dto.questionimage.QuestionImageResponse;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class QuestionImageService {

    private final QuestionImageRepository repository;
    
    /**
     * Physical path pointing to the static resources folder for Maven projects
     */
    private final Path root = Paths.get("services/backend-api/src/main/resources/static/uploads/questions");

    public QuestionImageService(QuestionImageRepository repository) {
        this.repository = repository;
        try {
            // Ensure the physical directory exists on service startup
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize folder for upload!");
        }
    }

    /**
     * Retrieves all active (non-deleted) question images
     */
    public Flux<QuestionImageResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    /**
     * Retrieves all soft-deleted question images
     */
    public Flux<QuestionImageResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    /**
     * Finds all images associated with a specific question that have not been deleted
     */
    public Flux<QuestionImageResponse> findByQuestionId(Long questionId) {
        return repository.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(questionId)
                .map(this::toResponse);
    }

    /**
     * Retrieves a single active question image by its ID
     */
    public Mono<QuestionImageResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .map(this::toResponse);
    }

    /**
     * Creates a new QuestionImage by saving the physical file to the resources folder 
     * and generating its public URL
     */
    public Mono<QuestionImageResponse> createWithFile(QuestionImageRequest dto, Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> {
            // Generate a unique filename to prevent overwriting
            String filename = UUID.randomUUID() + "-" + filePart.filename();
            Path targetPath = this.root.resolve(filename);

            // Transfer the incoming file bytes to the physical target path
            return filePart.transferTo(targetPath)
                    .then(Mono.defer(() -> {
                        QuestionImage entity = new QuestionImage();
                        entity.setQuestionId(dto.questionId());
                        
                        // Set the public URL path (mapped via WebFlux static resources)
                        entity.setImageUrl("/uploads/questions/" + filename);
                        entity.setCaption(dto.caption());
                        entity.setOrderIndex(dto.orderIndex() != null ? dto.orderIndex() : 0);
                        entity.setDeletedAt(null);

                        return repository.save(entity);
                    }));
        }).map(this::toResponse);
    }

    /**
     * Updates metadata (caption, order) for an existing active question image
     */
    public Mono<QuestionImageResponse> update(Long id, QuestionImageRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .flatMap(entity -> {
                    entity.setCaption(dto.caption() != null ? dto.caption() : entity.getCaption());
                    entity.setOrderIndex(dto.orderIndex() != null ? dto.orderIndex() : entity.getOrderIndex());
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    /**
     * Marks a question image as deleted (Soft Delete)
     */
    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    /**
     * Restores a previously soft-deleted question image
     */
    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Question image is not deleted")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    /**
     * Permanently removes a question image from the database
     */
    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted images can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    /**
     * Converts the internal Entity to a Response DTO
     */
    private QuestionImageResponse toResponse(QuestionImage entity) {
        return new QuestionImageResponse(
                entity.getId(),
                entity.getQuestionId(),
                entity.getImageUrl(),
                entity.getCaption(),
                entity.getOrderIndex(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }
}