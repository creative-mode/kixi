package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.classe.ClassRequest;
import ao.creativemode.kixi.dto.classe.ClassResponse;
import ao.creativemode.kixi.model.Class;
import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.repository.ClassRepository;
import ao.creativemode.kixi.repository.CourseRepository;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ClassService {

    private final ClassRepository repository;
    private final CourseRepository courseRepository;
    private final SchoolYearRepository schoolYearRepository;


    public ClassService(ClassRepository repository,CourseRepository courseRepository,SchoolYearRepository schoolYearRepository){
        this.repository = repository;
        this.courseRepository = courseRepository;
        this.schoolYearRepository = schoolYearRepository;
    }


    // Retrieve all active classes
    public Flux<ClassResponse> findAllActive(){
        return repository.findAllByDeletedAtIsNull().flatMap(this::toResponse);
    }

    // Retrieve all soft-deleted classes
    public Flux<ClassResponse> findAllDeteted(){
        return repository.findAllByDeletedAtIsNotNull().flatMap(this::toResponse);
    }

    // Find a specific active class by ID
    public Mono<ClassResponse> findByIdActive(Long id){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class not found")))
                .flatMap(this::toResponse);
    }

    // Create a new class
    public Mono<ClassResponse> create(ClassRequest data){
        Class entity = new Class();
        entity.setCode(data.code());
        entity.setGrade(data.grade());
        entity.setCourseId(data.courseId());
        entity.setSchoolYearId(data.schoolYearId());
        entity.setDeletedAt(null);

        return repository.save(entity)
                .flatMap(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e->ApiException.conflict("class is already exist"));

    }

    //Update a class
    public Mono<ClassResponse> update(Long id, ClassRequest data){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this id not found")))
                .flatMap(entity->{
                    entity.setCode(data.code());
                    entity.setGrade(data.grade());
                    entity.setSchoolYearId(data.schoolYearId());
                    entity.setCourseId(data.courseId());
                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e->ApiException.conflict("Another class already exist with this grade"));
                }).flatMap(this::toResponse);
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
                            courseObj,      //  Complete Course
                            schoolYearObj,  // Complete SchoolYear
                            entity.getCreatedAt(),
                            entity.getUpdatedAt(),
                            entity.getDeletedAt()
                    );
                });
    }
}
