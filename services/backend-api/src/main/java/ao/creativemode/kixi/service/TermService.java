package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.term.TermRequest;
import ao.creativemode.kixi.dto.term.TermResponse;
import ao.creativemode.kixi.model.Term;
import ao.creativemode.kixi.repository.TermRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Service
public class TermService {

    private final TermRepository repository;

    public TermService(TermRepository repository) {
        this.repository = repository;
    }

    public Flux<TermResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<TermResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<TermResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Term not found")))
                .map(this::toResponse);
    }

    public Mono<TermResponse> create(TermRequest dto) {
        Term entity = new Term();
        entity.setName(dto.name());
        entity.setNumber(dto.number());
        return repository.save(entity).map(this::toResponse);
    }

    public Mono<TermResponse> update(Long id, TermRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Term not found")))
                .flatMap(entity -> {
                    entity.setName(dto.name());
                    entity.setNumber(dto.number());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Term not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                }).then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Term is not in trash")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                }).then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only trashed terms can be purged")))
                .flatMap(repository::delete);
    }

    private TermResponse toResponse(Term entity) {
        return new TermResponse(entity.getId(), entity.getNumber(), entity.getName(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getDeletedAt());
    }
}