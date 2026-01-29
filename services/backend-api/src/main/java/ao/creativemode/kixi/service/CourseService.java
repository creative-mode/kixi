package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.courses.CourseRequest;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.repository.CourseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class CourseService {

    private final CourseRepository repository;

    public CourseService(CourseRepository repository) {
        this.repository = repository;
    }

    public Flux<CourseResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<CourseResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Mono<CourseResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Course not found")))
                .map(this::toResponse);
    }

    public Mono<CourseResponse> create(CourseRequest request) {
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        String description = request.description() != null ? request.description().trim() : null;

        Course entity = new Course();
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(description);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("A course with code " + code + " already exists"));
    }

    public Mono<CourseResponse> update(Long id, CourseRequest request) {
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        String description = request.description() != null ? request.description().trim() : null;

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Course not found")))
                .flatMap(entity -> {
                    entity.setCode(code);
                    entity.setName(name);
                    entity.setDescription(description);
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Another course with code " + code + " already exists"));
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Course not found")))
                .flatMap(entity -> {
                    entity.setDeletedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Course is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null);
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                    Mono.error(ApiException.badRequest("Only deleted courses can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    private CourseResponse toResponse(Course entity) {
        return new CourseResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}