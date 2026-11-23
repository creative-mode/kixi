package ao.creativemode.kixi.donations.repository;

import ao.creativemode.kixi.donations.model.DonationFile;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DonationFileRepository extends ReactiveCrudRepository<DonationFile, Long> {

    @Query("SELECT * FROM statement_donation_files WHERE donation_id = :donationId ORDER BY id ASC")
    Flux<DonationFile> findAllByDonation(Long donationId);

    @Query("SELECT * FROM statement_donation_files WHERE id = :id AND donation_id = :donationId")
    Mono<DonationFile> findByIdAndDonation(Long id, Long donationId);

    @Query("SELECT COUNT(*) FROM statement_donation_files WHERE donation_id = :donationId")
    Mono<Long> countByDonation(Long donationId);

    /**
     * File counts for a page of donations in one query, rather than one per row.
     *
     * <p>The column is {@code donation_id}, not an alias in camel case: Spring Data maps the record component
     * {@code donationId} through the naming strategy to {@code donation_id}, so any other spelling reads as null and every
     * donation of the list showed «Ficheiros 0».
     */
    @Query("""
            SELECT donation_id, COUNT(*) AS total
            FROM statement_donation_files
            WHERE donation_id IN (:donationIds)
            GROUP BY donation_id
            """)
    Flux<FileCount> countsForDonations(java.util.Collection<Long> donationIds);

    record FileCount(Long donationId, Long total) { }
}
