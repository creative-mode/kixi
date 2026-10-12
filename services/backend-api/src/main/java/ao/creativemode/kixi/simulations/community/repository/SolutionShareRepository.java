package ao.creativemode.kixi.simulations.community.repository;

import ao.creativemode.kixi.simulations.community.model.SolutionShare;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Active sharing consents. Every read here filters {@code deletedAt IS NULL}:
 * a revoked share must disappear from the community as if it never existed.
 *
 * <p>The listings join the simulation and the account so the page and its
 * total always agree: a share whose simulation was deleted, left
 * {@code FINISHED}, or belongs to a deleted account never reaches the page in
 * the first place, instead of being dropped after paging. Ordering is newest
 * first with the id as tie-break, so equal timestamps can neither repeat nor
 * skip a row between pages.
 */
@Repository
public interface SolutionShareRepository extends R2dbcRepository<SolutionShare, Long> {

    Mono<SolutionShare> findBySimulationIdAndDeletedAtIsNull(Long simulationId);

    @Query("""
        SELECT sh.* FROM solution_shares sh
        JOIN simulations s ON s.id = sh.simulation_id
        JOIN accounts a ON a.id = sh.account_id
        WHERE sh.statement_id = :statementId AND sh.deleted_at IS NULL
          AND s.deleted_at IS NULL AND s.status = 'FINISHED'
          AND a.deleted_at IS NULL
        ORDER BY sh.created_at DESC, sh.id DESC
        LIMIT :limit OFFSET :offset
        """)
    Flux<SolutionShare> findPageByStatement(Long statementId, int limit, long offset);

    @Query("""
        SELECT COUNT(*) FROM solution_shares sh
        JOIN simulations s ON s.id = sh.simulation_id
        JOIN accounts a ON a.id = sh.account_id
        WHERE sh.statement_id = :statementId AND sh.deleted_at IS NULL
          AND s.deleted_at IS NULL AND s.status = 'FINISHED'
          AND a.deleted_at IS NULL
        """)
    Mono<Long> countPageByStatement(Long statementId);

    @Query("""
        SELECT sh.* FROM solution_shares sh
        JOIN simulations s ON s.id = sh.simulation_id
        JOIN accounts a ON a.id = sh.account_id
        WHERE sh.statement_id = :statementId AND sh.institution_id = :institutionId
          AND sh.deleted_at IS NULL
          AND s.deleted_at IS NULL AND s.status = 'FINISHED'
          AND a.deleted_at IS NULL
        ORDER BY sh.created_at DESC, sh.id DESC
        LIMIT :limit OFFSET :offset
        """)
    Flux<SolutionShare> findPageByStatementAndInstitution(
            Long statementId, Long institutionId, int limit, long offset);

    @Query("""
        SELECT COUNT(*) FROM solution_shares sh
        JOIN simulations s ON s.id = sh.simulation_id
        JOIN accounts a ON a.id = sh.account_id
        WHERE sh.statement_id = :statementId AND sh.institution_id = :institutionId
          AND sh.deleted_at IS NULL
          AND s.deleted_at IS NULL AND s.status = 'FINISHED'
          AND a.deleted_at IS NULL
        """)
    Mono<Long> countPageByStatementAndInstitution(Long statementId, Long institutionId);
}
