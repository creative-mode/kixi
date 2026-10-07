package ao.creativemode.kixi.academic.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.academic.dto.courses.CourseRequest;
import ao.creativemode.kixi.academic.dto.courses.CourseResponse;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class CourseService {

    private final CourseRepository repository;
    private final ClassRepository classRepository;

    public CourseService(CourseRepository repository, ClassRepository classRepository) {
        this.repository = repository;
        this.classRepository = classRepository;
    }

    public Flux<CourseResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    /**
     * Courses of one school, for the student picking where they study. No filter means
     * every active course, which is what the admin list uses.
     */
    public Flux<CourseResponse> findAllActive(Long institutionId) {
        Flux<Course> rows = institutionId == null
                ? repository.findAllByDeletedAtIsNull()
                : repository.findAllByInstitutionIdAndDeletedAtIsNull(institutionId);
        return rows.map(this::toResponse);
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

        if (request.institutionId() == null) {
            return Mono.error(ApiException.badRequest("Institution is required"));
        }

        Course entity = new Course();
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(description);
        entity.setInstitutionId(request.institutionId());
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

        if (request.institutionId() == null) {
            return Mono.error(ApiException.badRequest("Institution is required"));
        }

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Course not found")))
                .flatMap(entity -> {
                    boolean schoolChanged = !java.util.Objects.equals(
                            entity.getInstitutionId(), request.institutionId());

                    return (schoolChanged
                            ? requireNoClassesInTheOldSchool(entity)
                            : Mono.<Void>empty())
                            .then(Mono.defer(() -> {
                                entity.setCode(code);
                                entity.setName(name);
                                entity.setDescription(description);
                                entity.setInstitutionId(request.institutionId());
                                entity.setUpdatedAt(LocalDateTime.now());

                                return repository.save(entity);
                            }));
                })
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Another course with code " + code + " already exists"));
    }

    /**
     * A class carries the school of its course, enforced by a composite foreign key. So
     * moving a course that already has classes to another school would either fail on
     * that constraint or, worse, be reported as a duplicate code. Say what is actually
     * wrong instead: the classes have to move first.
     */
    private Mono<Void> requireNoClassesInTheOldSchool(Course course) {
        return classRepository.findByCourseIdAndDeletedAtIsNull(course.getId())
                .hasElements()
                .flatMap(hasClasses -> hasClasses
                        ? Mono.error(ApiException.conflict(
                                "This course already has classes in its current school. "
                                        + "Move the classes to the other school before changing it."))
                        : Mono.empty());
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
                entity.getInstitutionId(),
                entity.getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
