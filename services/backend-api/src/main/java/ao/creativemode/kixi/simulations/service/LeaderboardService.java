package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader.Seat;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardConsentResponse;
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
 * The relative leaderboard of issue #117, extended across institutions by #189.
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
 * <p>{@code scope=global} is the exception to the masking rule, and it is the whole of
 * what #189 adds. It does not rank more people — it ranks the same measure against other
 * schools, on three conditions taken in ADR-0013:</p>
 * <ul>
 *   <li><b>Consent.</b> The caller has to have opted in, and so has every student in the
 *       table. A student who has never been asked is not in it.</li>
 *   <li><b>Percentile, not percentage.</b> Students are ordered by where they stand inside
 *       their own institution. Raw percentages survive as a tie-break and as a field, but
 *       two schools' exam curves are not the same scale and must not decide a podium.</li>
 *   <li><b>No name at all.</b> A masked name plus a school is a person; a school plus a
 *       class plus a school year is a person without needing the name. So a peer is a
 *       label, their names are never read from the database, and the response carries no
 *       institution for anybody but the caller. What it does carry is how many students
 *       and how many schools the comparison drew from — an anonymised table that says
 *       where it came from is legible, and one that does not is just ominous.</li>
 * </ul>
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

    /**
     * What a peer is called in {@code scope=global}.
     *
     * <p>Not the masked name the other scopes use. Shortening a name still hands over the
     * name, and out here there is no class to read it against — so the rows are not read
     * at all and the label stands in their place.</p>
     */
    static final String OUTSIDE_LABEL = "Aluno de outra escola";

    /** For a row with no name behind it, whichever scope it came from. */
    private static final String FALLBACK_LABEL = "Aluno";

    /** Bound on the cache, so it cannot grow with whatever the caller varies. */
    private static final int MAX_CACHED_COHORTS = 500;

    private final EnrollmentSeatReader seats;
    private final LeaderboardRepository repository;
    private final ExamRoomAccess examRooms;

    private final Map<CacheKey, Entry> cache = new ConcurrentHashMap<>();

    public LeaderboardService(
            EnrollmentSeatReader seats, LeaderboardRepository repository, ExamRoomAccess examRooms) {
        this.seats = seats;
        this.repository = repository;
        this.examRooms = examRooms;
    }

    public Mono<LeaderboardResponse> leaderboard(
            Long accountId, String scope, Long statementId, String period, String metric) {
        return Mono.defer(() -> {
            Scope resolved;
            Period window;
            Metric by;
            try {
                resolved = Scope.of(scope);
                window = Period.of(period);
                by = Metric.of(metric);
            } catch (IllegalArgumentException e) {
                return Mono.error(ApiException.badRequest(e.getMessage()));
            }

            return consentedFor(resolved, accountId)
                    .then(cohortOf(accountId, resolved))
                    .flatMap(cohort -> checkStatementExists(statementId)
                            .then(requireNoOpenRoom(statementId))
                            .then(ranking(resolved, cohort.id(), cohort.schoolYearId(),
                                    cohort.institutionId(), statementId, window, by)
                                    .flatMap(ranked -> windowAround(
                                            accountId, ranked, resolved, cohort.id(),
                                            statementId, window, by))));
        });
    }

    /**
     * Reads, and changes, the caller's consent to be ranked against other schools.
     *
     * <p>The switch itself lives here rather than in a controller so the scope and the
     * thing that gates it cannot drift apart: there is exactly one caller of
     * {@code consentedToCrossInstitution} and it is the line below.</p>
     */
    public Mono<LeaderboardConsentResponse> consent(Long accountId, boolean crossInstitution) {
        return repository.setCrossInstitutionConsent(accountId, crossInstitution)
                .flatMap(written -> repository.consentedToCrossInstitution(accountId)
                        .map(current -> new LeaderboardConsentResponse(current)));
    }

    /**
     * A scope that reads a stranger's performance needs the student to have agreed to it.
     *
     * <p>Refused rather than served empty. An empty table cannot tell the client whether
     * the student declined or whether nobody sat anything, and a client that cannot tell
     * the two apart cannot offer the opt-in that would fix it. The other scopes publish
     * to peers, so they are not gated — see ADR-0013, decision 1.</p>
     */
    private Mono<Void> consentedFor(Scope scope, Long accountId) {
        if (scope != Scope.GLOBAL) {
            return Mono.empty();
        }
        return repository.consentedToCrossInstitution(accountId)
                .flatMap(consented -> consented ? Mono.<Void>empty()
                        : Mono.error(ApiException.forbidden("A cross-institution ranking needs "
                                + "consent: PUT /api/v1/leaderboard/consent")));
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
                // A school is what a ranking is isolated by, and so is a cross-institution
                // one: school_year_id is global — one row serves every school — so a seat
                // with no institution is not enough to rank against.
                .filter(seat -> scope == Scope.CLASS || seat.institutionId() != null)
                .next()
                .switchIfEmpty(Mono.error(ApiException.forbidden(switch (scope) {
                    case CLASS -> "A class ranking needs an active enrollment in a class";
                    case SCHOOL -> "A school ranking needs an active enrollment in a school";
                    case FRIENDS -> "The friends ranking needs an active enrollment in a school";
                    case GLOBAL -> "A cross-institution ranking needs an active enrollment in a school";
                })))
                .map(seat -> new Cohort(
                        // Friends are not a group: the "group id" the repository wants is
                        // the caller's own account, because a friendship is a pair of ids
                        // and not a column on enrollments.
                        //
                        // A cross-institution ranking has no group either, and this null is
                        // the whole of it: with no id in the cache key, every caller in the
                        // country shares one cached ranking, which is the correct reading —
                        // they are all being compared against the same table.
                        scope == Scope.FRIENDS ? accountId
                                : scope == Scope.CLASS ? seat.classId()
                                : scope == Scope.SCHOOL ? seat.institutionId() : null,
                        seat.schoolYearId(),
                        seat.institutionId()));
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
     * A paper being sat right now does not get ranked.
     *
     * <p>The same rule the community aggregates follow, for the same reason: a score
     * published while the room is still open tells a student who is halfway through the
     * paper what everybody else scored on it, and a class average is enough to work out
     * which options are the right ones. {@code CommunityStatsService} answers 409 to the
     * aggregate and the solutions listing for exactly this; a per-colleague average is the
     * same disclosure, spread thinner, so it gets the same status.</p>
     *
     * <p>Only for a named paper. Without a {@code statementId} there is no single exam
     * room to be inside of — the cohort spans whatever everyone happened to sit, closed
     * rooms included — and refusing the whole ranking because one room somewhere is open
     * would hide closed papers from students who are done. This is the deliberate limit of
     * the rule, not an oversight: what stays hidden is the ongoing exam, and the aggregate
     * of a cohort that finished is not it.</p>
     *
     * <p>Null-safe like the community's copy: with no rooms wired in there is no room to
     * be open, and a default interface that answers {@code false} must not be turned into
     * a 409 for every request.</p>
     *
     * <p>The check goes through {@code ExamRoomAccess} rather than the rooms table, which is
     * how the community copy reaches the same answer.
     * {@code CommunityStatsService} keeps its own copy of the check rather than
     * sharing this one, because it lives in {@code simulations.community.service} and is
     * package-private — {@code ArchitectureTest} puts both packages in the same slice, so
     * reusing it would only need a visibility change, not a dependency one. The copies can
     * drift, so they answer the same status for the same reason on purpose; if the rule
     * ever changes, it changes in both places.</p>
     */
    private Mono<Void> requireNoOpenRoom(Long statementId) {
        if (statementId == null || examRooms == null) {
            return Mono.empty();
        }
        Mono<Boolean> open = examRooms.hasNonClosedRoom(statementId);
        return (open == null ? Mono.just(false) : open)
                .defaultIfEmpty(false)
                .flatMap(hasOpen -> hasOpen
                        ? Mono.error(ApiException.conflict(
                                "The ranking of a paper is only available once the exam rooms "
                                        + "for it are closed"))
                        : Mono.empty());
    }

    /**
     * The whole cohort, ordered. Every member reads the same rows, which is exactly why
     * it — and not the response — is what gets cached.
     */
    private Mono<Ranking> ranking(
            Scope scope, Long groupId, Long schoolYearId, Long institutionId, Long statementId,
            Period period, Metric metric) {
        CacheKey key = new CacheKey(scope, groupId, schoolYearId, statementId, period, metric);

        Entry running = cache.get(key);
        if (running != null && !running.expired()) {
            return running.ranking();
        }

        Mono<Ranking> computed = Mono.defer(() -> Mono.zip(
                        repository.findAverages(scope, groupId, schoolYearId,
                                institutionId, statementId, period.from(), period.to())
                                .collectList(),
                        repository.countMembers(scope, groupId, schoolYearId, institutionId))
                        .map(tuples -> rank(tuples.getT1(), tuples.getT2(), metric)));

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
     * What the table is ordered by, and what a tie is judged on.
     *
     * <p>A cross-institution ranking is ordered by the percentile and every other scope
     * by the average, and there is never a mix inside one table: {@code scope=global}
     * computes a percentile for every row it returns, and the other scopes return none.
     * So one key serves both, and the three scopes from #117 keep the exact order they
     * had.</p>
     *
     * <p>Not "percentage across schools" with a percentile field bolted on. 80% on an easy
     * paper and 80% on a hard paper are different results, and letting the easier school
     * win on that is the one comparison the issue is asking not to make.</p>
     */
    private static double rankKey(GroupScore score, Metric metric) {
        if (metric == Metric.XP) {
            // Lifetime XP, and an account with none yet goes to the bottom rather than
            // out: it has a mark like anybody else, it just has no ladder.
            return score.xp() == null ? Double.NEGATIVE_INFINITY : score.xp().doubleValue();
        }
        if (score.percentile() != null) {
            return score.percentile();
        }
        return score.average() == null ? Double.NEGATIVE_INFINITY : score.average();
    }

    /**
     * Orders the averages and gives ties the same place, the way a race does: 1, 2, 2, 4.
     * Averages arrive already sorted by SQL, but they are sorted again here so the rule
     * holds whatever the repository returns.
     */
    private static Ranking rank(List<GroupScore> scores, long totalStudents, Metric metric) {
        List<GroupScore> ordered = new ArrayList<>(scores);
        ordered.sort(Comparator
                .comparingDouble((GroupScore score) -> rankKey(score, metric)).reversed()
                .thenComparing(GroupScore::accountId));

        Set<Long> institutions = new LinkedHashSet<>();
        List<Ranked> ranked = new ArrayList<>(ordered.size());
        int position = 1;
        Double previousKey = null;
        for (int i = 0; i < ordered.size(); i++) {
            GroupScore score = ordered.get(i);
            double key = rankKey(score, metric);
            // The key, not the average, is what decides a tie — and comparing keys means
            // two students on the same percentile share a place even though their raw
            // percentages, used only to break it, are different.
            if (previousKey == null || Double.compare(key, previousKey) != 0) {
                position = i + 1;
                previousKey = key;
            }
            if (score.institutionId() != null) {
                institutions.add(score.institutionId());
            }
            ranked.add(new Ranked(position, score.accountId(), score.average(),
                    score.percentile(), score.xp()));
        }
        return new Ranking(ranked, (int) totalStudents, institutions.size());
    }

    /** The caller's neighbourhood, plus the podium when the group is big enough for one. */
    private Mono<LeaderboardResponse> windowAround(
            Long accountId,
            Ranking ranking,
            Scope scope,
            Long groupId,
            Long statementId,
            Period period,
            Metric metric) {
        List<Ranked> ranked = ranking.ranked();
        // Both numbers matter. Too few enrolled, or too few with a score to rank, and the
        // table is short enough that naming its bottom says something about real people.
        //
        // Friends are the exception, and the acceptance criterion of issue #121 is the
        // reason: "two students become friends and appear in each other's ranking" cannot
        // hold if the floor hides every row of a two-person table. The floor exists because
        // a student is ranked beside people they were placed with, and the teacher can see
        // the table. A friend was asked and answered — that is the consent the floor was
        // standing in for, and here it has actually been given.
        boolean smallGroup = scope != Scope.FRIENDS
                && Math.min(ranking.totalStudents(), ranked.size()) < SMALL_GROUP_LIMIT;

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

        // Across institutions this is the caller's name and nothing else. The peers' names
        // are not read from the database and then shortened — they are never asked for,
        // which is the difference between not publishing a name and publishing half of one
        // (ADR-0013, decision 3).
        Set<Long> accounts = new LinkedHashSet<>();
        if (scope == Scope.GLOBAL) {
            accounts.add(accountId);
        } else {
            visible.forEach(entry -> accounts.add(entry.accountId()));
        }

        int myIndex = mine;
        return repository.findDisplayNames(accounts)
                .collectList()
                .map(names -> response(
                        accountId, scope, groupId, statementId, period, metric, ranking,
                        ranked, myIndex, visible, names, smallGroup));
    }

    private static LeaderboardResponse response(
            Long accountId,
            Scope scope,
            Long groupId,
            Long statementId,
            Period period,
            Metric metric,
            Ranking ranking,
            List<Ranked> ranked,
            int mine,
            List<Ranked> visible,
            List<DisplayName> names,
            boolean smallGroup) {
        Map<Long, String> labels = new LinkedHashMap<>();
        names.forEach(name -> labels.put(name.accountId(), mask(name.firstName(), name.lastName())));

        // Outside the caller's own school nobody is named, so the label is fixed rather
        // than looked up: there is no name in `labels` for them to find.
        boolean anonymous = scope == Scope.GLOBAL;

        List<LeaderboardResponse.Entry> entries = visible.stream()
                .map(entry -> {
                    boolean caller = entry.accountId().equals(accountId);
                    return new LeaderboardResponse.Entry(
                            entry.position(),
                            // The caller's own id is their own. A colleague's would turn a
                            // masked label back into a person, since the same id answers
                            // half a dozen other endpoints.
                            caller ? accountId : null,
                            caller ? labels.getOrDefault(entry.accountId(), FALLBACK_LABEL)
                                    : anonymous ? OUTSIDE_LABEL
                                            : labels.getOrDefault(entry.accountId(), FALLBACK_LABEL),
                            entry.average(),
                            entry.percentile(),
                            entry.xp(),
                            caller);
                })
                .toList();

        return new LeaderboardResponse(
                scope.name().toLowerCase(Locale.ROOT),
                // Null for scope=global: there is no group behind it, and a value here
                // would be the one field in the response that names a school.
                anonymous ? null : groupId,
                statementId,
                period.label(),
                metric.name().toLowerCase(Locale.ROOT),
                ranking.totalStudents(),
                ranked.size(),
                // How many schools the comparison drew from, for the reader to be told
                // where their place came from. Zero in the scopes that are inside a single
                // school already, where the question does not arise.
                ranking.institutionsCounted(),
                smallGroup,
                mine >= 0 ? ranked.get(mine).position() : null,
                mine >= 0 ? ranked.get(mine).average() : null,
                mine >= 0 ? ranked.get(mine).percentile() : null,
                entries);
    }

    /**
     * A ranking that named classmates whole would republish data the student never
     * agreed to share, so a colleague is a first name and an initial.
     *
     * <p>Used by {@code scope=class}, {@code school} and {@code friends}. Not used by
     * {@code scope=global}, where a first name and an initial next to a position is still
     * a name — see {@link #OUTSIDE_LABEL}.</p>
     */
    static String mask(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String initial = last.isEmpty() ? "" : last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        String masked = (first + " " + initial).trim();
        return masked.isEmpty() ? FALLBACK_LABEL : masked;
    }

    /**
     * The group being ranked and the year it is counted over.
     *
     * <p>The year is part of the cohort, not a filter on it: both the seats and the
     * papers have to be from the same school year, or the average belongs to another
     * cohort altogether.</p>
     *
     * @param id the class or institution behind the cohort, the caller's own account for
     *           {@code friends}, and null for {@code global} — which has no group
     */
    record Cohort(Long id, Long schoolYearId, Long institutionId) { }

    /**
     * What the positions are by.
     *
     * <p>The cohort does not change with it — the same students are ranked either way,
     * with the same year, the same school and the same small-group floor. Only the
     * ordering does, and the average travels with each row so the client can show it
     * under a position that was decided by something else.</p>
     *
     * <p>XP is lifetime and does not move with {@code statementId} or {@code period}.
     * Ranking by it asks "of the people who sat this paper, who has climbed furthest on
     * the ladder", not "who earned most XP here" — the second thing is not knowable,
     * because the XP awarded for one simulation is not attributed per period anywhere.</p>
     */
    enum Metric {
        AVERAGE, XP;

        public static Metric of(String value) {
            if (value == null || value.isBlank()) {
                return AVERAGE;
            }
            for (Metric metric : values()) {
                if (metric.name().equalsIgnoreCase(value)) {
                    return metric;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported leaderboard metric: " + value + " (expected average or xp)");
        }
    }

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

    /**
     * One member of the cohort.
     *
     * @param percentile where the student stands inside their own institution, or null in
     *                   every scope that has no institution to stand inside
     */
    record Ranked(int position, Long accountId, Double average, Double percentile, Long xp) { }

    /**
     * Everything every member of a cohort is allowed to see.
     *
     * @param institutionsCounted distinct schools behind the rows, which is the cross
     *                            institution scope's provenance and is 0 elsewhere
     */
    record Ranking(List<Ranked> ranked, int totalStudents, int institutionsCounted) { }

    /** What identifies a cached ranking, and nothing that could serve another's. */
    record CacheKey(Scope scope, Long groupId, Long schoolYearId, Long statementId,
                  Period period, Metric metric) { }

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
