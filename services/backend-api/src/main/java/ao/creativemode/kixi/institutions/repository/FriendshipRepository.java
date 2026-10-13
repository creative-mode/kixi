package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.institutions.model.Friendship;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FriendshipRepository extends ReactiveCrudRepository<Friendship, Long> {

    Mono<Friendship> findByIdAndDeletedAtIsNull(Long id);

    /** Any state (live or removed), so a removed request is restored instead of duplicated. */
    @Query("""
        SELECT * FROM friendships
         WHERE account_low = :low AND account_high = :high
         LIMIT 1
        """)
    Mono<Friendship> findBetween(Long low, Long high);

    /** The people whose marks the caller may see. This is the ranking's cohort. */
    @Query("""
        SELECT * FROM friendships
         WHERE deleted_at IS NULL
           AND status = 'ACCEPTED'
           AND (account_low = :accountId OR account_high = :accountId)
         ORDER BY account_low, account_high
        """)
    Flux<Friendship> findAcceptedBy(Long accountId);

    /**
     * Requests addressed to the caller that nobody has answered yet.
     *
     * <p>Only the addressee. A request the caller sent is not an inbox item, and
     * returning their own sent requests here would put their own id in a list of people
     * waiting for them.</p>
     */
    @Query("""
        SELECT * FROM friendships
         WHERE deleted_at IS NULL
           AND status = 'PENDING'
           AND account_high = :accountId
         ORDER BY updated_at DESC, id DESC
        """)
    Flux<Friendship> findPendingFor(Long accountId);

    /**
     * Everyone in the caller's own school year who is not already a friend.
     *
     * <p>Returns the whole {@code users} row rather than a record of the two name columns:
     * entity mapping is what every other repository here relies on, and a projection has
     * no precedent to copy.
     *
     * <p>Same school and same school year, and both are needed. The school year is a
     * global row that every school shares, so on its own it is not a filter at all; the
     * institution comes through the class, because an enrollment has none of its own. A
     * student who moved keeps the old enrollment ACTIVE, which is what the year stops.
     * Excluding both directions of the pair, because a person already requested is not a
     * colleague to discover.</p>
     */
    @Query("""
        SELECT DISTINCT u.*
          FROM enrollments e
          JOIN accounts a ON a.id = e.account_id
                        AND a.deleted_at IS NULL AND a.active = TRUE
          JOIN users u ON u.account_id = a.id AND u.deleted_at IS NULL
          JOIN classes fc ON fc.id = e.class_id AND fc.deleted_at IS NULL
         WHERE e.deleted_at IS NULL
           AND e.status = 'ACTIVE'
           AND e.school_year_id = :schoolYearId
           AND fc.institution_id = :institutionId
           AND e.account_id <> :accountId
           AND (:term IS NULL OR (u.first_name || ' ' || u.last_name) ILIKE '%' || :term || '%')
           AND NOT EXISTS (SELECT 1 FROM friendships f
                            WHERE f.deleted_at IS NULL
                              AND f.status IN ('PENDING', 'ACCEPTED')
                              AND ((f.account_low = :accountId AND f.account_high = e.account_id)
                                OR (f.account_high = :accountId AND f.account_low = e.account_id)))
         ORDER BY u.first_name, u.last_name
         LIMIT :limit
        """)
    Flux<User> searchColleagues(
            Long accountId, Long schoolYearId, Long institutionId, String term, int limit);
}