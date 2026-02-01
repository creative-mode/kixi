package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.classe.ClassRequest;
import ao.creativemode.kixi.dto.classe.ClassResponse;
import ao.creativemode.kixi.dto.courses.CourseResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
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

    public Flux<ClassResponse> findAllActive(){
        return repository.findAllByDeletedAtIsNull().flatMap(this::toResponse);
    }

    public Flux<ClassResponse> findAllDeteted(){
        return repository.findAllByDeletedAtIsNotNull().flatMap(this::toResponse);
    }

    public Mono<ClassResponse> findByCodeActive(String code){
        return repository.findByCodeAndDeletedAtIsNull(code)
                .switchIfEmpty(Mono.error(ApiException.notFound("class not found")))
                .flatMap(this::toResponse);
    }

    public Mono<ClassResponse> create(ClassRequest data){
        Class entity = new Class();
        entity.setCode(data.code());
        entity.setGrade(data.grade());
        entity.setCourseId(data.courseId());
        entity.setSchoolYearId(data.schoolYearId());
        entity.setNewRecord(true);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .flatMap(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e->ApiException.conflict("class is already exist"));

    }

    public Mono<ClassResponse> update(String code, ClassRequest data){
        return repository.findByCodeAndDeletedAtIsNull(code)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this code not found")))
                .flatMap(entity->{
                    entity.setGrade(data.grade());
                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e->ApiException.conflict("Another class already exist with this grade"));
                }).flatMap(this::toResponse);
    }

    public Mono<Void> softDelete(String code){
        return repository.findByCodeAndDeletedAtIsNull(code)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this code not found")))
                .flatMap(entity->{
                    entity.markAsDeleted();
                    return repository.save(entity);
                }).then();
    }

    public Mono<Void> restore(String code){
        return repository.findByCodeAndDeletedAtIsNotNull(code)
                .switchIfEmpty(Mono.error(ApiException.notFound("class with this code not found")))
                .flatMap(entity->{
                    entity.restore();
                    return repository.save(entity);
                }).then();
    }

    public Mono<Void> hardDelete(String code){
        return repository.findByCodeAndDeletedAtIsNotNull(code)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted class can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }


    private Mono<ClassResponse> toResponse(Class entity) {
        // Buscamos as dependências em paralelo
        Mono<Course> courseMono = courseRepository.findById(entity.getCourseId())
                .switchIfEmpty(Mono.just(new Course())); // Evita erro se não encontrar

        Mono<SchoolYear> schoolYearMono = schoolYearRepository.findById(entity.getSchoolYearId())
                .switchIfEmpty(Mono.just(new SchoolYear()));

        // Combinamos os Monos
        return Mono.zip(courseMono, schoolYearMono)
                .map(tuple -> {
                    Course courseObj = tuple.getT1();
                    SchoolYear schoolYearObj = tuple.getT2();

                    // Aqui chamamos o seu formato ajustado
                    return new ClassResponse(
                            entity.getCode(),
                            entity.getGrade(),
                            entity.getCourseId(),
                            entity.getSchoolYearId(),
                            courseObj,      // Course completo
                            schoolYearObj,  // SchoolYear completo
                            entity.getCreatedAt(),
                            entity.getUpdatedAt(),
                            entity.getDeletedAt()
                    );
                });
    }
}
