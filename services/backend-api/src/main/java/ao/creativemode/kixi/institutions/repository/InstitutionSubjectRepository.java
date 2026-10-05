package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.InstitutionSubject;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionSubjectRepository extends ReactiveCrudRepository<InstitutionSubject, Long> {

    Flux<InstitutionSubject> findAllByInstitutionIdAndDeletedAtIsNull(Long institutionId);

    Flux<InstitutionSubject> findAllBySubjectIdAndDeletedAtIsNull(Long subjectId);

    /** Any state (active or trashed), so a removed link can be restored instead of duplicated. */
    Mono<InstitutionSubject> findFirstByInstitutionIdAndSubjectId(Long institutionId, Long subjectId);

    Mono<Boolean> existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(Long institutionId, Long subjectId);

    /** Hard delete every link of an institution, active or trashed (needed before purging it). */
    Mono<Void> deleteAllByInstitutionId(Long institutionId);
}
