package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.InstitutionVerificationRequest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionVerificationRequestRepository
        extends ReactiveCrudRepository<InstitutionVerificationRequest, Long> {

    Flux<InstitutionVerificationRequest> findAllByOrderByCreatedAtDesc();

    Flux<InstitutionVerificationRequest> findAllByStatusOrderByCreatedAtDesc(String status);

    Flux<InstitutionVerificationRequest> findAllByInstitutionIdOrderByCreatedAtDesc(Long institutionId);

    Mono<Boolean> existsByInstitutionIdAndStatus(Long institutionId, String status);
}
