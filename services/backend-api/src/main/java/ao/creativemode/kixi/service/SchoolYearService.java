package ao.creativemode.kixi.service;

import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.repository.SchoolYearRepository;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.schoolyears.SchoolYearRequest;
import ao.creativemode.kixi.shared.exception.ApiException;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;

@Service
public class SchoolYearService {

    private final SchoolYearRepository repository;

    public SchoolYearService(SchoolYearRepository repository) {
        this.repository = repository;
    }

    public Flux<SchoolYearResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .map(this::toResponse);
    }

    public Flux<SchoolYearResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .map(this::toResponse);
    }

    public Mono<SchoolYearResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .map(this::toResponse);
    }

    public Mono<SchoolYearResponse> create(SchoolYearRequest dto) {
        if (dto.startYear() >= dto.endYear()) {
            return Mono.error(ApiException.badRequest("Start year must be less than end year"));
        }

        SchoolYear entity = new SchoolYear();
        entity.setStartYear(dto.startYear());
        entity.setEndYear(dto.endYear());
        entity.setDeletedAt(null);

        return repository.save(entity)
                .map(this::toResponse)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("A school year with start year " + dto.startYear() +
                                " and end year " + dto.endYear() + " already exists."));
    }

    public Mono<SchoolYearResponse> update(Long id, SchoolYearRequest dto) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .flatMap(entity -> {
                    Integer newStart = dto.startYear() != null ? dto.startYear() : entity.getStartYear();
                    Integer newEnd = dto.endYear() != null ? dto.endYear() : entity.getEndYear();

                    if (newStart >= newEnd) {
                        return Mono.error(ApiException.badRequest("Start year must be less than end year"));
                    }

                    entity.setStartYear(newStart);
                    entity.setEndYear(newEnd);
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("Another school year already exists with start year " +
                                            newStart + " and end year " + newEnd + "."));
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .flatMap(entity -> {
                    entity.markAsDeleted();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("School year is not deleted")))
                .flatMap(entity -> {
                    entity.restore();
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                        Mono.error(ApiException.badRequest("Only deleted school years can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    private SchoolYearResponse toResponse(SchoolYear entity) {
        return new SchoolYearResponse(
                entity.getId(),
                entity.getStartYear(),
                entity.getEndYear(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }
}
