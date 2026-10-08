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

    /** The constraint that fails when the course names a school that does not exist. */
    private static final String FK_INSTITUTION = "fk_courses_institution";

    /** The composite constraint tying a class to the school of its course. */
    private static final String FK_CLASSES_COURSE_INSTITUTION = "fk_classes_course_institution";

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
                        e -> integrityFailure(e, code, "A course with code " + code + " already exists"));
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
                        e -> integrityFailure(e, code,
                                "Another course with code " + code + " already exists"));
    }

    /**
     * A violated foreign key and a duplicated code are both integrity violations, and
     * telling them apart is what keeps the message honest: `academic` cannot verify the
     * school exists (it must not depend on the `institutions` module), so the only thing
     * that separates the two is which constraint the database reported.
     */
    private ApiException integrityFailure(DataIntegrityViolationException e, String code, String duplicateCode) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        if (detail.contains(FK_INSTITUTION)) {
            return ApiException.unprocessableEntity(
                    "No institution exists with that id");
        }
        if (detail.contains(FK_CLASSES_COURSE_INSTITUTION)) {
            return ApiException.conflict(
                    "This course already has classes in its current school. "
                            + "Move the classes to the other school before changing it.");
        }
        return ApiException.conflict(duplicateCode);
    }

    /**
     * A class carries the school of its course, enforced by a composite foreign key. So
     * moving a course that already has classes to another school would either fail on
     * that constraint or, worse, be reported as a duplicate code. Say what is actually
     * wrong instead: the classes have to move first.
     *
     * <p>Trashed classes count too. Soft delete only stamps {@code deleted_at}, so the
     * row stays in {@code classes} and stays held by the composite key; a course whose
     * classes are all in the trash would pass this check and then fail on the save.
     */
    private Mono<Void> requireNoClassesInTheOldSchool(Course course) {
        return classRepository.findByCourseId(course.getId())
                .hasElements()
                .flatMap(hasClasses -> hasClasses
                        ? Mono.error(ApiException.conflict(
                                "This course already has classes in its current school, "
                                        + "including deleted ones. "
                                        + "Move or purge the classes before changing it."))
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
