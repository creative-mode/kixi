package ao.creativemode.kixi.service;

import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearRequest;
import ao.creativemode.kixi.common.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class SchoolYearService {

    private final SchoolYearRepository repository;

    public SchoolYearService(SchoolYearRepository repository) {
        this.repository = repository;
    }

    public Flux<SchoolYearResponse> findAllActive() {
        return repository.findAllByDeletedFalse()
                .map(this::toResponse);
    }

    public Flux<SchoolYearResponse> findAllDeleted() {
        return repository.findAllByDeletedTrue()
                .map(this::toResponse);
    }

    public Mono<SchoolYearResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedFalse(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .map(this::toResponse);
    }

    public Mono<SchoolYearResponse> create(SchoolYearRequest dto) {
        SchoolYear entity = new SchoolYear();
        entity.setStartYear(dto.startYear());
        entity.setEndYear(dto.endYear());
        entity.setDeleted(false);
        entity.setDeletedAt(null);

        return repository.save(entity)
                .map(this::toResponse);
    }


    public Mono<SchoolYearResponse> update(Long id, SchoolYearRequest dto) {
        return repository.findByIdAndDeletedFalse(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .flatMap(entity -> {
                    if (dto.startYear() != null) entity.setStartYear(dto.startYear());
                    if (dto.endYear() != null) entity.setEndYear(dto.endYear());
                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedFalse(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedTrue(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("School year is not deleted")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedTrue(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Only deleted school years can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    private SchoolYearResponse toResponse(SchoolYear entity) {
        return new SchoolYearResponse(
                entity.getId(),
                entity.getStartYear(),
                entity.getEndYear()
        );
    }
}