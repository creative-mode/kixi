package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionTeacherRepository extends ReactiveCrudRepository<InstitutionTeacher, Long> {

    Flux<InstitutionTeacher> findAllByInstitutionIdAndDeletedAtIsNull(Long institutionId);

    Flux<InstitutionTeacher> findAllByTeacherIdAndDeletedAtIsNull(Long teacherId);

    /** Any state (active or trashed), so a removed link can be restored instead of duplicated. */
    Mono<InstitutionTeacher> findFirstByInstitutionIdAndTeacherId(Long institutionId, Long teacherId);

    Mono<Boolean> existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(Long institutionId, Long teacherId);

    /** Hard delete every link of an institution, active or trashed (needed before purging it). */
    Mono<Void> deleteAllByInstitutionId(Long institutionId);
}
