package ao.creativemode.kixi.donations.repository;

import ao.creativemode.kixi.donations.model.Donation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DonationRepository extends ReactiveCrudRepository<Donation, Long> {

    /**
     * One page of donations, newest first, optionally of a single status. {@code CAST(... AS VARCHAR)}
     * because Postgres cannot infer the type of a bare {@code :status IS NULL}.
     */
    @Query("""
            SELECT * FROM statement_donations
            WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Donation> findPage(String status, int limit, long offset);

    @Query("SELECT COUNT(*) FROM statement_donations WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)")
    Mono<Long> countMatching(String status);

    /** Counts per status, for the filter chips of the review screen. */
    @Query("SELECT status, COUNT(*) AS total FROM statement_donations GROUP BY status")
    Flux<StatusCount> countsByStatus();

    Mono<Boolean> existsByReference(String reference);

    record StatusCount(String status, Long total) { }
}
