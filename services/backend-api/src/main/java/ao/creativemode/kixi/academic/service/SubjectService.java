package ao.creativemode.kixi.academic.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.AcademicDependencyReader;
import ao.creativemode.kixi.academic.dto.subject.SubjectRequest;
import ao.creativemode.kixi.academic.dto.subject.SubjectResponse;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.CourseSubjectRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class SubjectService {

    private final SubjectRepository repository;
    private final CourseSubjectRepository curriculum;
    private final AcademicDependencyReader dependencies;

    public SubjectService(
        SubjectRepository repository,
        CourseSubjectRepository curriculum,
        AcademicDependencyReader dependencies
    ) {
        this.repository = repository;
        this.curriculum = curriculum;
        this.dependencies = dependencies;
    }

    public Flux<SubjectResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<SubjectResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<SubjectResponse> findByCodeActive(String code) {
        return repository
            .findByCodeAndDeletedAtIsNull(code)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Subject not found"))
            )
            .map(this::toResponse);
    }

    public Mono<SubjectResponse> create(SubjectRequest data) {
        Subject newSubject = new Subject();
        newSubject.setCode(data.code());
        newSubject.setName(data.name());
        newSubject.setShortName(data.shortName());
        newSubject.setDeletedAt(null);

        return repository
            .save(newSubject)
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict(
                    "Subject with code " + data.code() + " already exists."
                )
            );
    }

    public Mono<SubjectResponse> update(String code, SubjectRequest data) {
        return repository
            .findByCodeAndDeletedAtIsNull(code)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Subject not found"))
            )
            .flatMap(subject -> {
                String newCode = data.code();
                String newName = data.name();
                String newShortName = data.shortName();

                subject.setCode(newCode);
                subject.setName(newName);
                subject.setShortName(newShortName);
                return repository
                    .save(subject)
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict(
                            "Another subject with this code already exists, please choose a different code."
                        )
                    );
            })
            .map(this::toResponse);
    }

    public Mono<Void> softDelete(String code) {
        return repository
            .findByCodeAndDeletedAtIsNull(code)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Subject not found"))
            )
            // Issue #253: a subject taught somewhere is not removed from under the courses and the
            // teachers that use it. Nothing cascades; the message says what blocks it.
            .flatMap(subject -> Mono.zip(
                    curriculum.countActiveBySubject(subject.getId()),
                    dependencies.activeAssignmentsOfSubject(subject.getId()))
                .flatMap(counts -> counts.getT1() + counts.getT2() == 0
                    ? Mono.just(subject)
                    : Mono.<Subject>error(ApiException.conflict(
                        "This subject is still in the curriculum of " + counts.getT1()
                            + " active course(s) and has " + counts.getT2()
                            + " active teaching assignment(s): remove them first"))))
            .flatMap(subject -> {
                subject.markAsDeleted();
                return repository.save(subject);
            })
            .then();
    }

    public Mono<Void> restore(String code) {
        return repository
            .findByCodeAndDeletedAtIsNotNull(code)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Subject not found"))
            )
            .flatMap(subject -> {
                subject.restore();
                return repository.save(subject);
            })
            .then();
    }

    public Mono<Void> hardDelete(String code) {
        return repository
            .findByCodeAndDeletedAtIsNotNull(code)
            .switchIfEmpty(
                Mono.error(
                    ApiException.notFound(
                        "Only deleted subject can be permanently removed"
                    )
                )
            )
            .flatMap(repository::delete)
            .then();
    }

    private SubjectResponse toResponse(Subject entity) {
        return new SubjectResponse(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getShortName(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }
}
