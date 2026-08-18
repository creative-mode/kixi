package ao.creativemode.kixi.service;

import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

import javax.imageio.ImageIO;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.config.StorageProperties;
import ao.creativemode.kixi.dto.questionimage.QuestionImageRequest;
import ao.creativemode.kixi.dto.questionimage.QuestionImageResponse;
import ao.creativemode.kixi.model.QuestionImage;
import ao.creativemode.kixi.repository.QuestionImageRepository;
import ao.creativemode.kixi.service.storage.ImageStorage;
import ao.creativemode.kixi.service.storage.StoredObject;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class QuestionImageService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImageService.class);

    private final QuestionImageRepository repository;
    private final ImageStorage storage;
    private final StorageProperties storageProperties;

    public QuestionImageService(
            QuestionImageRepository repository,
            ImageStorage storage,
            StorageProperties storageProperties) {
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    public Flux<QuestionImageResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<QuestionImageResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Flux<QuestionImageResponse> findByQuestionId(Long questionId) {
        return repository.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(questionId)
                .map(this::toResponse);
    }

    public Mono<QuestionImageResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .map(this::toResponse);
    }

    /**
     * Stores a validated raster image through the configured storage adapter.
     * The client filename is intentionally not used as an object key.
     */
    public Mono<QuestionImageResponse> createWithFile(
            QuestionImageRequest dto,
            Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> {
            MediaType contentType = filePart.headers().getContentType();
            if (!isSupportedImage(contentType)) {
                return Mono.error(ApiException.badRequest(
                        "Only JPEG, PNG and WebP images are supported"));
            }

            long contentLength = filePart.headers().getContentLength();
            if (contentLength > storageProperties.getMaxObjectSizeBytes()) {
                return Mono.error(ApiException.badRequest("Image exceeds the configured size limit"));
            }

            String key = "questions/" + dto.questionId() + "/"
                    + UUID.randomUUID() + extension(contentType);

            return DataBufferUtils.join(filePart.content(), maxSize())
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return bytes;
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .onErrorMap(DataBufferLimitException.class,
                            error -> ApiException.badRequest("Image exceeds the configured size limit"))
                    .switchIfEmpty(Mono.error(ApiException.badRequest("Image content is required")))
                    .flatMap(bytes -> {
                        if (!isValidRaster(contentType, bytes)) {
                            return Mono.error(ApiException.badRequest("Image content does not match its media type"));
                        }
                        return storage.put(key, contentType, bytes)
                                .flatMap(stored -> saveEntity(dto, stored));
                    });
        }).map(this::toResponse);
    }

    private Mono<QuestionImage> saveEntity(QuestionImageRequest dto, StoredObject stored) {
        QuestionImage entity = new QuestionImage();
        entity.setQuestionId(dto.questionId());
        entity.setImageUrl(stored.publicUrl());
        entity.setStorageKey(stored.key());
        entity.setCaption(dto.caption());
        entity.setOrderIndex(dto.orderIndex() != null ? dto.orderIndex() : 0);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .onErrorResume(error -> storage.delete(stored.key())
                        .onErrorResume(cleanupError -> {
                            log.error("Image cleanup failed after database error: key={}, errorType={}",
                                    stored.key(), cleanupError.getClass().getSimpleName());
                            return Mono.empty();
                        })
                        .then(Mono.error(error)));
    }

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

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Question image not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Question image is not deleted")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted images can be permanently removed")))
                .flatMap(entity -> storage.delete(entity.getStorageKey())
                        .then(repository.delete(entity)))
                .then();
    }

    private boolean isSupportedImage(MediaType contentType) {
        if (contentType == null || !"image".equalsIgnoreCase(contentType.getType())) {
            return false;
        }
        String subtype = contentType.getSubtype();
        return "jpeg".equalsIgnoreCase(subtype)
                || "png".equalsIgnoreCase(subtype)
                || "webp".equalsIgnoreCase(subtype);
    }

    private boolean isValidRaster(MediaType contentType, byte[] bytes) {
        if ("png".equalsIgnoreCase(contentType.getSubtype())
                || "jpeg".equalsIgnoreCase(contentType.getSubtype())) {
            try {
                return ImageIO.read(new ByteArrayInputStream(bytes)) != null;
            } catch (IOException ex) {
                return false;
            }
        }
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private int maxSize() {
        long max = storageProperties.getMaxObjectSizeBytes();
        if (max < 1 || max > Integer.MAX_VALUE) {
            throw new IllegalStateException("storage.max-object-size-bytes must be between 1 and 2147483647");
        }
        return (int) max;
    }

    private String extension(MediaType contentType) {
        if ("png".equalsIgnoreCase(contentType.getSubtype())) {
            return ".png";
        }
        if ("webp".equalsIgnoreCase(contentType.getSubtype())) {
            return ".webp";
        }
        return ".jpg";
    }

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
