package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.institutions.dto.institution.InstitutionRequest;
import ao.creativemode.kixi.institutions.dto.institution.InstitutionResponse;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class InstitutionService {

    private final InstitutionRepository repository;
    private final InstitutionSubjectRepository subjectLinks;
    private final InstitutionTeacherRepository teacherLinks;
    private final InstitutionStudentRepository studentLinks;

    public InstitutionService(
        InstitutionRepository repository,
        InstitutionSubjectRepository subjectLinks,
        InstitutionTeacherRepository teacherLinks,
        InstitutionStudentRepository studentLinks
    ) {
        this.repository = repository;
        this.subjectLinks = subjectLinks;
        this.teacherLinks = teacherLinks;
        this.studentLinks = studentLinks;
    }

    public Flux<InstitutionResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(InstitutionService::toResponse);
    }

    public Flux<InstitutionResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(InstitutionService::toResponse);
    }

    public Mono<InstitutionResponse> findByIdActive(Long id) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .map(InstitutionService::toResponse);
    }

    public Mono<InstitutionResponse> create(InstitutionRequest data) {
        Institution institution = new Institution();
        apply(institution, data);
        institution.setDeletedAt(null);

        return repository
            .save(institution)
            .map(InstitutionService::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict("Institution with code " + data.code() + " already exists.")
            );
    }

    public Mono<InstitutionResponse> update(Long id, InstitutionRequest data) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> {
                apply(institution, data);
                return repository
                    .save(institution)
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict(
                            "Another institution with this code already exists, please choose a different code."
                        )
                    );
            })
            .map(InstitutionService::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> {
                institution.markAsDeleted();
                return repository.save(institution);
            })
            .then();
    }

    public Mono<Void> restore(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> {
                institution.restore();
                return repository.save(institution);
            })
            .then();
    }

    /**
     * Permanently removes a trashed institution together with its links.
     * Statements authored for the institution are real records and block the
     * purge (the whole operation is rolled back).
     */
    @Transactional
    public Mono<Void> hardDelete(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Only deleted institution can be permanently removed"))
            )
            .flatMap(institution ->
                subjectLinks
                    .deleteAllByInstitutionId(id)
                    .then(teacherLinks.deleteAllByInstitutionId(id))
                    .then(studentLinks.deleteAllByInstitutionId(id))
                    .then(repository.delete(institution))
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict("Institution still has statements and cannot be removed")
                    )
            )
            .then();
    }

    private void apply(Institution institution, InstitutionRequest data) {
        institution.setCode(data.code().trim());
        institution.setName(data.name().trim());
        institution.setShortName(data.shortName() == null || data.shortName().isBlank() ? null : data.shortName().trim());
        institution.setLogo(data.logo() == null || data.logo().isBlank() ? null : data.logo());
    }

    public static InstitutionResponse toResponse(Institution entity) {
        return new InstitutionResponse(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getShortName(),
            entity.getLogo(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }
}
