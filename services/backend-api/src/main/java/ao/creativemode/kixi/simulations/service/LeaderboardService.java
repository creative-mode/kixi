package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader.Seat;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardResponse;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.DisplayName;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.GroupScore;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The relative leaderboard of issue #117.
 *
 * <p>Five rules shape it:</p>
 * <ol>
 *   <li><b>The caller decides the cohort.</b> {@code scope=class} and {@code school}
 *       resolve from the seats the caller actually holds, never from a parameter, so
 *       nobody can ask for a group they do not belong to.</li>
 *   <li><b>Membership is a seat, in one school year.</b> Not a profile, and not a link to
 *       the institution: a cancelled row is not a seat, and neither is last year's. The
 *       year is carried into the queries, because a student who moved keeps their old row
 *       ACTIVE forever and would otherwise go on being ranked where they no longer are.</li>
 *   <li><b>The cache holds the cohort, the answer does not.</b> A ranking is cached per
 *       cohort because every member reads the same rows; the window that comes back is
 *       built per request. Caching the response instead would hand one caller somebody
 *       else's neighbourhood.</li>
 *   <li><b>A small group publishes no podium.</b> Under ten students a full ranking would
 *       name who is last, which is the exposure the issue asks to avoid. The caller
 *       still sees their own position and immediate neighbours.</li>
 *   <li><b>Protection follows the ranked rows.</b> The floor is drawn from how many
 *       students actually have a score, not from how many are enrolled: otherwise a class
 *       of eight with five scores would still show fifth, and {@code rankedStudents}
 *       would tell the reader it was the last.</li>
 * </ol>
 *
 * <p>A colleague appears as a masked label and no identifier: a stable account id in a
 * response meant to hide who somebody is is a correlation handle waiting to be used
 * against the endpoints that do name people.</p>
 *
 * <p>{@code scope=friends} is not implemented here: friendships are issue #121, and the
 * ranking is what that issue integrates into, not what it waits for.</p>
 */
@Service
public class LeaderboardService {

    /** Short enough to stay honest, long enough to absorb a burst of requests. */
    static final Duration CACHE_TTL = Duration.ofSeconds(45);

    /** How many colleagues on each side of the caller travel with them. */
    static final int NEIGHBOURS = 2;

    /** The top places published once a group is big enough to have a podium. */
    static final int PODIUM_SIZE = 5;

    /** Under this many students a group is too small to publish a podium. */
    static final int SMALL_GROUP_LIMIT = 10;

    /** Bound on the cache, so it cannot grow with whatever the caller varies. */
    private static final int MAX_CACHED_COHORTS = 500;

    private final EnrollmentSeatReader seats;
    private final LeaderboardRepository repository;

    private final Map<CacheKey, Entry> cache = new ConcurrentHashMap<>();

    public LeaderboardService(EnrollmentSeatReader seats, LeaderboardRepository repository) {
        this.seats = seats;
        this.repository = repository;
    }

    public Mono<LeaderboardResponse> leaderboard(
            Long accountId, String scope, Long statementId, String period) {
        return Mono.defer(() -> {
            if ("friends".equalsIgnoreCase(scope)) {
                return Mono.error(ApiException.notFound(
                        "The friends ranking is not available yet; it arrives with friendships (issue #121)"));
            }

            Scope resolved;
            Period window;
            try {
                resolved = Scope.of(scope);
                window = Period.of(period);
            } catch (IllegalArgumentException e) {
                return Mono.error(ApiException.badRequest(e.getMessage()));
            }

            return cohortOf(accountId, resolved)
                    .flatMap(cohort -> checkStatementExists(statementId)
                            .then(ranking(resolved, cohort.id(), cohort.schoolYearId(),
                                    statementId, window)
                                    .flatMap(ranked -> windowAround(
                                            accountId, ranked, resolved, cohort.id(),
                                            statementId, window))));
        });
    }

    /**
     * The group to rank and the school year it is counted over.
     *
     * <p>Taken from the caller's newest ACTIVE seat: the most recent one is the school
     * they are at now. Two seats in two schools are a legal state — one per school year —
     * so the choice is a rule, never the order the rows arrived in.</p>
     */
    private Mono<Cohort> cohortOf(Long accountId, Scope scope) {
        return seats.activeSeatsOf(accountId)
                .filter(seat -> scope != Scope.SCHOOL || seat.institutionId() != null)
                .next()
                .switchIfEmpty(Mono.error(ApiException.forbidden(switch (scope) {
                    case CLASS -> "A class ranking needs an active enrollment in a class";
                    case SCHOOL -> "A school ranking needs an active enrollment in a school";
                })))
                .map(seat -> new Cohort(
                        scope == Scope.CLASS ? seat.classId() : seat.institutionId(),
                        seat.schoolYearId()));
    }

    /**
     * The statement is looked up before it becomes part of a cache key.
     *
     * <p>Without this a caller could vary {@code statementId} over ids that do not exist
     * and take a cache slot each — bounded, but the bound is shared with everybody, and a
     * caller varying the id can push real cohorts out of it. An id that names no statement
     * is also an empty ranking, which is not worth remembering for 45 seconds.</p>
     */
    private Mono<Void> checkStatementExists(Long statementId) {
        if (statementId == null) {
            return Mono.empty();
        }
        return repository.statementExists(statementId)
                .flatMap(exists -> exists
                        ? Mono.<Void>empty()
                        : Mono.error(ApiException.notFound("No statement with id " + statementId)));
    }

    /**
     * The whole cohort, ordered. Every member reads the same rows, which is exactly why
     * it — and not the response — is what gets cached.
     */
    private Mono<Ranking> ranking(
            Scope scope, Long groupId, Long schoolYearId, Long statementId, Period period) {
        CacheKey key = new CacheKey(scope, groupId, schoolYearId, statementId, period);

        Entry running = cache.get(key);
        if (running != null && !running.expired()) {
            return running.ranking();
        }

        Mono<Ranking> computed = Mono.defer(() -> Mono.zip(
                        repository.findAverages(scope, groupId, schoolYearId, statementId,
                                period.from(), period.to())
                                .collectList(),
                        repository.countMembers(scope, groupId, schoolYearId))
                        .map(tuples -> rank(tuples.getT1(), tuples.getT2())));

        prune();
        if (cache.size() >= MAX_CACHED_COHORTS) {
            // Over the bound: this request is served without taking a slot. A caller that
            // varies statementId can make their own lookups uncached, but cannot push
            // anybody else's ranking out — which is what clearing the map would let them.
            return computed;
        }

        // The failure has to drop this key and not whatever replaced it meanwhile, so the
        // removal is by identity rather than by key alone.
        AtomicReference<Entry> mine = new AtomicReference<>();
        Mono<Ranking> fresh = computed
                // cache() holds the error as well as the value, and a database that blipped
                // would then answer the whole cohort with the same failure for the whole
                // TTL. Dropping the key on the way out leaves the next caller to retry.
                .doOnError(error -> {
                    Entry current = mine.get();
                    if (current != null) {
                        cache.remove(key, current);
                    }
                })
                .cache(CACHE_TTL);

        Entry entry = new Entry(fresh, System.nanoTime() + CACHE_TTL.toNanos());
        mine.set(entry);
        Entry raced = cache.putIfAbsent(key, entry);
        return raced != null && !raced.expired() ? raced.ranking() : fresh;
    }

    /** Forgets cohorts whose time is up, one key at a time. */
    private void prune() {
        cache.entrySet().removeIf(candidate -> candidate.getValue().expired());
    }

    /**
     * Orders the averages and gives ties the same place, the way a race does: 1, 2, 2, 4.
     * Averages arrive already sorted by SQL, but they are sorted again here so the rule
     * holds whatever the repository returns.
     */
    private static Ranking rank(List<GroupScore> scores, long totalStudents) {
        List<GroupScore> ordered = new ArrayList<>(scores);
        ordered.sort(Comparator
                .comparingDouble((GroupScore score) -> score.average() == null ? Double.NEGATIVE_INFINITY : score.average())
                .reversed()
                .thenComparing(GroupScore::accountId));

        List<Ranked> ranked = new ArrayList<>(ordered.size());
        int position = 1;
        Double previous = null;
        for (int i = 0; i < ordered.size(); i++) {
            GroupScore score = ordered.get(i);
            if (previous == null || Double.compare(score.average(), previous) != 0) {
                position = i + 1;
                previous = score.average();
            }
            ranked.add(new Ranked(position, score.accountId(), score.average()));
        }
        return new Ranking(ranked, (int) totalStudents);
    }

    /** The caller's neighbourhood, plus the podium when the group is big enough for one. */
    private Mono<LeaderboardResponse> windowAround(
            Long accountId,
            Ranking ranking,
            Scope scope,
            Long groupId,
            Long statementId,
            Period period) {
        List<Ranked> ranked = ranking.ranked();
        // Both numbers matter. Too few enrolled, or too few with a score to rank, and the
        // table is short enough that naming its bottom says something about real people.
        boolean smallGroup = Math.min(ranking.totalStudents(), ranked.size()) < SMALL_GROUP_LIMIT;

        int mine = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).accountId().equals(accountId)) {
                mine = i;
                break;
            }
        }

        Set<Integer> indexes = new LinkedHashSet<>();
        if (mine >= 0) {
            int last = Math.min(ranked.size() - 1, mine + NEIGHBOURS);
            for (int i = Math.max(0, mine - NEIGHBOURS); i <= last; i++) {
                indexes.add(i);
            }
            // The window walks into the bottom of the table otherwise: sixth of eight would
            // show seventh and eighth. Those places are withheld from the other rows.
            // The caller's own place stays: they already know where they stand, and
            // learning it from somebody else's ranking is the part that hurts.
            if (smallGroup) {
                // Drawn from the rows actually ranked, not from the enrolment count: with
                // five of eight marked, a floor of six protects nothing.
                int floor = ranked.size() - NEIGHBOURS;
                int myPosition = mine;
                indexes.removeIf(index ->
                        ranked.get(index).position() > floor && index != myPosition);
            }
        }
        if (!smallGroup) {
            for (int i = 0; i < Math.min(PODIUM_SIZE, ranked.size()); i++) {
                indexes.add(i);
            }
        }

        List<Ranked> visible = indexes.stream().sorted().map(ranked::get).toList();
        Set<Long> accounts = new LinkedHashSet<>();
        visible.forEach(entry -> accounts.add(entry.accountId()));

        int myIndex = mine;
        return repository.findDisplayNames(accounts)
                .collectList()
                .map(names -> response(
                        accountId, scope, groupId, statementId, period, ranking, ranked, myIndex, visible, names, smallGroup));
    }

    private static LeaderboardResponse response(
            Long accountId,
            Scope scope,
            Long groupId,
            Long statementId,
            Period period,
            Ranking ranking,
            List<Ranked> ranked,
            int mine,
            List<Ranked> visible,
            List<DisplayName> names,
            boolean smallGroup) {
        Map<Long, String> labels = new LinkedHashMap<>();
        names.forEach(name -> labels.put(name.accountId(), mask(name.firstName(), name.lastName())));

        List<LeaderboardResponse.Entry> entries = visible.stream()
                .map(entry -> {
                    boolean caller = entry.accountId().equals(accountId);
                    return new LeaderboardResponse.Entry(
                            entry.position(),
                            // The caller's own id is their own. A colleague's would turn a
                            // masked label back into a person, since the same id answers
                            // half a dozen other endpoints.
                            caller ? accountId : null,
                            labels.getOrDefault(entry.accountId(), "Aluno"),
                            entry.average(),
                            caller);
                })
                .toList();

        return new LeaderboardResponse(
                scope.name().toLowerCase(Locale.ROOT),
                groupId,
                statementId,
                period.label(),
                ranking.totalStudents(),
                ranked.size(),
                smallGroup,
                mine >= 0 ? ranked.get(mine).position() : null,
                mine >= 0 ? ranked.get(mine).average() : null,
                entries);
    }

    /**
     * A ranking that named classmates whole would republish data the student never
     * agreed to share, so a colleague is a first name and an initial.
     */
    static String mask(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String initial = last.isEmpty() ? "" : last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        String masked = (first + " " + initial).trim();
        return masked.isEmpty() ? "Aluno" : masked;
    }

    /**
     * The group being ranked and the year it is counted over.
     *
     * <p>The year is part of the cohort, not a filter on it: both the seats and the
     * papers have to be from the same school year, or the average belongs to another
     * cohort altogether.</p>
     */
    record Cohort(Long id, Long schoolYearId) { }

    /** The time window a ranking covers. */
    enum Period {
        ALL("all"), MONTH("month");

        private final String label;

        Period(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        LocalDateTime from() {
            return this == MONTH ? LocalDateTime.now().minusMonths(1) : null;
        }

        LocalDateTime to() {
            return this == MONTH ? LocalDateTime.now() : null;
        }

        static Period of(String value) {
            for (Period period : values()) {
                if (period.label.equalsIgnoreCase(value)) {
                    return period;
                }
            }
            throw new IllegalArgumentException("Unsupported period: " + value + " (expected all or month)");
        }
    }

    /** One member of the cohort. */
    record Ranked(int position, Long accountId, Double average) { }

    /** Everything every member of a cohort is allowed to see. */
    record Ranking(List<Ranked> ranked, int totalStudents) { }

    /** What identifies a cached ranking, and nothing that could serve another's. */
    record CacheKey(Scope scope, Long groupId, Long schoolYearId, Long statementId, Period period) { }

    /**
     * A cached ranking and when it stops being worth reading. The timestamp is kept here
     * rather than inside the Mono so expiry is per key and can be pruned, instead of
     * needing a single act of eviction for the whole cache.
     */
    record Entry(Mono<Ranking> ranking, long expiresAtNanos) {

        boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }
}