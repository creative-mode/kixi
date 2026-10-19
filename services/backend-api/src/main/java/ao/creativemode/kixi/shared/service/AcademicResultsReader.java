package ao.creativemode.kixi.shared.service;

import java.time.OffsetDateTime;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * An account's finished simulations in one school year, for the private academic history on the
 * profile. A port in {@code shared}, like {@link PublicStatsReader}: institutions asks and
 * simulations answers, so institutions never reads the simulation tables itself.
 *
 * <p><b>Which year a result belongs to</b>, in this order: the simulation's own school year, the
 * statement's school year, and, for old records with neither, the school year that was running
 * when it finished (the Angolan school year starts in September).</p>
 */
public interface AcademicResultsReader {

    Mono<List<Result>> finishedIn(Long accountId, Long schoolYearId);

    record Result(
            Long id, Long subjectId, String subject, String title, Double score, Double max,
            OffsetDateTime date, boolean inRoom) {

        /** The score as a percentage (0-100) of the maximum, or null when either is missing. */
        public Double percent() {
            return score == null || max == null || max <= 0 ? null : Math.round(score / max * 10000.0) / 100.0;
        }
    }
}
