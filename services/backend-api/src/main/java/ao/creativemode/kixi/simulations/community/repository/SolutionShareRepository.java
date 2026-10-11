package ao.creativemode.kixi.simulations.community.repository;

import ao.creativemode.kixi.simulations.community.model.SolutionShare;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Active sharing consents. Every read here filters {@code deletedAt IS NULL}:
 * a revoked share must disappear from the community as if it never existed.
 */
@Repository
public interface SolutionShareRepository extends R2dbcRepository<SolutionShare, Long> {

    Mono<SolutionShare> findBySimulationIdAndDeletedAtIsNull(Long simulationId);

    Flux<SolutionShare> findAllByStatementIdAndDeletedAtIsNull(Long statementId, Pageable pageable);

    Mono<Long> countByStatementIdAndDeletedAtIsNull(Long statementId);

    Flux<SolutionShare> findAllByStatementIdAndInstitutionIdAndDeletedAtIsNull(
            Long statementId, Long institutionId, Pageable pageable);

    Mono<Long> countByStatementIdAndInstitutionIdAndDeletedAtIsNull(Long statementId, Long institutionId);
}
