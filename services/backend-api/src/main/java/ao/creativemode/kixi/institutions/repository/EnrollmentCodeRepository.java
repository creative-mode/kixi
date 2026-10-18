package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.EnrollmentCode;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EnrollmentCodeRepository extends ReactiveCrudRepository<EnrollmentCode, Long> {

    Mono<Boolean> existsByCode(String code);

    Mono<EnrollmentCode> findByCode(String code);

    /** Newest first, for the ADMIN screen that manages one class's codes. */
    Flux<EnrollmentCode> findAllByClassIdOrderByIdDesc(Long classId);
}
