package ao.creativemode.kixi.academic.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.academic.dto.classe.ClassRequest;
import ao.creativemode.kixi.academic.dto.classe.ClassResponse;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ClassService {

    private final ClassRepository repository;
    private final CourseRepository courseRepository;
    private final SchoolYearRepository schoolYearRepository;

    public ClassService(
            ClassRepository repository,
            CourseRepository courseRepository,
            SchoolYearRepository schoolYearRepository) {
        this.repository = repository;
        this.courseRepository = courseRepository;
        this.schoolYearRepository = schoolYearRepository;
    }

    // Retrieve all active classes
    public Flux<ClassResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().flatMap(this::toResponse);
    }

    /**
     * Active classes, optionally narrowed to one school and one course. The onboarding
     * screen passes both: the student picks the school, then the course, then the class.
     * A class inherits its school from its course, so both filters go through the course.
     */
    public Flux<ClassResponse> findAllActive(Long institutionId, Long courseId) {
        Flux<Class> rows;
        if (institutionId != null && courseId != null) {
            rows = repository.findAllByCourseIdAndInstitutionIdAndDeletedAtIsNull(courseId, institutionId);
        } else if (institutionId != null) {
            rows = repository.findAllByInstitutionIdAndDeletedAtIsNull(institutionId);
        } else if (courseId != null) {
            rows = repository.findByCourseIdAndDeletedAtIsNull(courseId);
        } else {
            rows = repository.findAllByDeletedAtIsNull();
        }
        return rows.flatMap(this::toResponse);
    }

    // Retrieve all soft-deleted classes
    public Flux<ClassResponse> findAllDeteted() {
        return repository.findAllByDeletedAtIsNotNull().flatMap(this::toResponse);
    }

    // Find a specific active class by ID
    public Mono<ClassResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class not found")))
                .flatMap(this::toResponse);
    }

    // Create a new class
    public Mono<ClassResponse> create(ClassRequest data) {
        return requireCourseAndSchoolYear(data.courseId(), data.schoolYearId())
                .flatMap(institutionId -> {
                    Class entity = new Class();
                    entity.setCode(data.code());
                    entity.setGrade(data.grade());
                    entity.setCourseId(data.courseId());
                    entity.setSchoolYearId(data.schoolYearId());
                    entity.setInstitutionId(institutionId);
                    entity.setDeletedAt(null);

                    return repository.save(entity);
                })
                .flatMap(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Class violates a database constraint"));
    }

    //Update a class
    public Mono<ClassResponse> update(Long id, ClassRequest data) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this id not found")))
                .flatMap(entity -> requireCourseAndSchoolYear(data.courseId(), data.schoolYearId())
                        .flatMap(institutionId -> {
                            entity.setCode(data.code());
                            entity.setGrade(data.grade());
                            entity.setSchoolYearId(data.schoolYearId());
                            entity.setCourseId(data.courseId());
                            entity.setInstitutionId(institutionId);
                            return repository.save(entity);
                        }))
                .flatMap(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Class violates a database constraint"));
    }

    /**
     * Validates both references before anything is saved, and hands back the school of
     * the course: a class always sits in the school of its course, so the school is read
     * rather than asked for, and the database enforces the same rule.
     */
    private Mono<Long> requireCourseAndSchoolYear(Long courseId, Long schoolYearId) {
        return courseRepository.findById(courseId)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Course not found: " + courseId)))
                .flatMap(course -> schoolYearRepository.findById(schoolYearId)
                        .switchIfEmpty(Mono.error(ApiException.badRequest("School year not found: " + schoolYearId)))
                        // flatMap, not map: a course with no institution must empty the
                        // Mono so the switchIfEmpty below can reject it, and Reactor's map
                        // throws on a null result instead of completing empty.
                        .flatMap(ignored -> course.getInstitutionId() == null
                                ? Mono.empty()
                                : Mono.just(course.getInstitutionId())))
                .switchIfEmpty(Mono.error(ApiException.badRequest("Course has no institution: " + courseId)));
    }


    //Move a class for trash
    public Mono<Void> softDelete(Long id){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this id not found")))
                .flatMap(entity->{
                    entity.markAsDeleted();
                    return repository.save(entity);
                }).then();
    }

    //Restore a class
    public Mono<Void> restore(Long id){
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this id not found")))
                .flatMap(entity->{
                    entity.restore();
                    return repository.save(entity);
                }).then();
    }

    //Deteted permanently a class
    public Mono<Void> hardDelete(Long id){
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted class can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }


    /**
     * Resolves the course and the school year. The school travels as an opaque id, which
     * is all this module may know about it; a missing course or year yields an empty
     * object rather than failing the whole list.
     */
    private Mono<ClassResponse> toResponse(Class entity) {

        Mono<Course> courseMono = courseRepository.findById(entity.getCourseId())
                .switchIfEmpty(Mono.just(new Course()));

        Mono<SchoolYear> schoolYearMono = schoolYearRepository.findById(entity.getSchoolYearId())
                .switchIfEmpty(Mono.just(new SchoolYear()));

        return Mono.zip(courseMono, schoolYearMono)
                .map(tuple -> {
                    Course courseObj = tuple.getT1();
                    SchoolYear schoolYearObj = tuple.getT2();

                    return new ClassResponse(
                            entity.getId(),
                            entity.getCode(),
                            entity.getGrade(),
                            courseObj,          // Complete Course
                            schoolYearObj,      // Complete SchoolYear
                            entity.getInstitutionId(),
                            entity.getCreatedAt(),
                            entity.getUpdatedAt(),
                            entity.getDeletedAt()
                    );
                });
    }
}
