package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.Enrollment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EnrollmentRepository extends ReactiveCrudRepository<Enrollment, Long> {

    Flux<Enrollment> findAllByAccountIdAndDeletedAtIsNull(Long accountId);

    @Query("SELECT * FROM enrollments WHERE account_id = :accountId "
            + "AND deleted_at IS NULL AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    Mono<Enrollment> findLatestByAccountIdAndDeletedAtIsNull(Long accountId);

    /** Every live enrollment, for the administrator's listing (#146). */
    Flux<Enrollment> findAllByDeletedAtIsNullOrderByIdAsc();

    Flux<Enrollment> findAllByClassIdAndDeletedAtIsNull(Long classId);

    Mono<Enrollment> findFirstByAccountIdAndSchoolYearIdAndDeletedAtIsNull(Long accountId, Long schoolYearId);

    /** Any state (active or cancelled), so a cancelled enrollment is restored instead of duplicated. */
    Mono<Enrollment> findFirstByAccountIdAndSchoolYearId(Long accountId, Long schoolYearId);

    Mono<Boolean> existsByAccountIdAndSchoolYearIdAndDeletedAtIsNull(Long accountId, Long schoolYearId);
}
