package ao.creativemode.kixi.service;

import ao.creativemode.kixi.dto.subject.SubjectRequest;
import ao.creativemode.kixi.dto.subject.SubjectResponse;
import ao.creativemode.kixi.model.Subject;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.repository.SubjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;



@Service
public class SubjectService {

    private final SubjectRepository repository;

    public SubjectService(SubjectRepository repository){
        this.repository = repository;
    }

    public Flux<SubjectResponse> findAllActive(){
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<SubjectResponse> findAllDeleted(){
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<SubjectResponse>  findByCodeActive(Long id){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
                .map(this::toResponse);
    }

    public Mono<SubjectResponse> create(SubjectRequest data){
        Subject newSubject = new Subject();
        newSubject.setCode(data.code());
        newSubject.setName(data.name());
        newSubject.setShortName(data.shortName());
        newSubject.setDeletedAt(null);

        return repository.save(newSubject)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e-> ApiException.conflict("Subject with code " + data.code() + " already exists."));
    }

    public Mono<SubjectResponse> update(Long id, SubjectRequest data){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
                .flatMap(subject -> {
                    String newCode = data.code();
                    String newName = data.name();
                    String newShortName = data.shortName();


                    subject.setCode(newCode);
                    subject.setName(newName);
                    subject.setShortName(newShortName);
                    return repository.save(subject)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Another subject with this name already exists, please choose a different name."));
                }).map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id){
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
                .flatMap(subject -> {
                    subject.markAsDeleted();
                    return repository.save(subject);
                }).then();
    }

    public Mono<Void> restore(Long id){
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Subject not found")))
                .flatMap(subject -> {
                    subject.restore();
                    return repository.save(subject);
                }).then();
    }

    public Mono<Void> hardDelete(Long id){
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Only deleted subject can be permanently removed")))
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
                entity.getDeletedAt());
    }
}
