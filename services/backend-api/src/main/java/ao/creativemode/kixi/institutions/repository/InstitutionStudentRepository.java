package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionStudentRepository extends ReactiveCrudRepository<InstitutionStudent, Long> {

    Flux<InstitutionStudent> findAllByInstitutionIdAndDeletedAtIsNull(Long institutionId);

    Flux<InstitutionStudent> findAllByUserIdAndDeletedAtIsNull(Long userId);

    /** Any state (active or trashed), so a removed link can be restored instead of duplicated. */
    Mono<InstitutionStudent> findFirstByInstitutionIdAndUserId(Long institutionId, Long userId);

    Mono<Boolean> existsByInstitutionIdAndUserIdAndDeletedAtIsNull(Long institutionId, Long userId);

    /** Hard delete every link of an institution, active or trashed (needed before purging it). */
    Mono<Void> deleteAllByInstitutionId(Long institutionId);
}
