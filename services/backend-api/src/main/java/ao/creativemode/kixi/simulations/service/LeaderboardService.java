package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
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

/**
 * The relative leaderboard of issue #117.
 *
 * <p>Four rules shape it:</p>
 * <ol>
 *   <li><b>The caller decides the cohort.</b> {@code scope=class} and {@code school}
 *       resolve through {@code /me}, never from a parameter, so nobody can ask for a
 *       group they do not belong to.</li>
 *   <li><b>Membership is proved, not assumed.</b> {@code /me} is not proof of belonging:
 *       it answers with the most recent enrollment whether or not it is active, and its
 *       school can also come from an institutional link that has no enrollment behind it.
 *       Both are read here as a starting point and then confirmed against an ACTIVE
 *       enrollment, so neither a cancelled seat nor a bare affiliation can buy a ranking
 *       that is counted over active students alone.</li>
 *   <li><b>The cache holds the cohort, the answer does not.</b> A ranking is cached per
 *       cohort because every member reads the same rows; the window that comes back is
 *       built per request. Caching the response instead would hand one caller somebody
 *       else's neighbourhood.</li>
 *   <li><b>A small group publishes no podium.</b> Under ten students a full ranking would
 *       name who is last, which is the exposure the issue asks to avoid. The caller
 *       still sees their own position and immediate neighbours.</li>
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

    /**
     * Newest enrollment first, then highest id. The id is the tiebreaker so that two rows
     * created in the same millisecond still produce one answer instead of an arbitrary
     * one, and so that the order never depends on how the database felt like returning
     * them.
     */
    private static final Comparator<Enrollment> SEAT_ORDER = Comparator
            .comparing(Enrollment::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Enrollment::getId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final MeService meService;
    private final EnrollmentRepository enrollments;
    private final LeaderboardRepository repository;

    private final Map<CacheKey, Entry> cache = new ConcurrentHashMap<>();

    public LeaderboardService(
            MeService meService,
            EnrollmentRepository enrollments,
            LeaderboardRepository repository) {
        this.meService = meService;
        this.enrollments = enrollments;
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
                    .flatMap(cohort -> ranking(resolved, cohort.id(), statementId, window)
                            .flatMap(ranked -> windowAround(
                                    accountId, ranked, resolved, cohort.id(), statementId, window)));
        });
    }

    /**
     * The class or school to rank, together with the proof that the caller sits in it.
     *
     * <p>{@code /me} proposes; this confirms. Its enrollment is the most recent one
     * whatever its status, and its school may come from an institutional link that has no
     * enrollment behind it — neither is a reason to hand out a ranking counted over
     * ACTIVE students.</p>
     */
    private Mono<Cohort> cohortOf(Long accountId, Scope scope) {
        return meService.getMe(accountId).flatMap(me -> switch (scope) {
            case CLASS -> classCohort(accountId, me);
            case SCHOOL -> schoolCohort(accountId, me);
        });
    }

    private Mono<Cohort> classCohort(Long accountId, MeResponse me) {
        if (me.currentClass() == null) {
            return Mono.error(ApiException.forbidden(
                    "A class ranking needs an enrollment; this account has none"));
        }
        Long classId = me.currentClass().id();
        return activeEnrollmentIn(accountId, classId)
                .switchIfEmpty(Mono.error(ApiException.forbidden(
                        "No active enrollment in that class")))
                .map(enrollment -> new Cohort(classId, enrollment.getSchoolYearId()));
    }

    /**
     * The school comes from the caller's ACTIVE enrollment, never from {@code me.school()}.
     *
     * <p>{@code /me} picks its enrollment by id and not by state, so a cancelled one that
     * happens to be newer — say a late withdrawal from another school — points the profile
     * at that school. Filtering ACTIVE enrollments of *that* school would then refuse a
     * student who does hold a valid seat somewhere else. Reading the school off the seat
     * itself cannot be wrong in that way: there is no seat, there is no school.</p>
     */
    private Mono<Cohort> schoolCohort(Long accountId, MeResponse me) {
        return firstActiveEnrollmentWithASchool(accountId)
                .switchIfEmpty(Mono.error(ApiException.forbidden(
                        "A school ranking needs an active enrollment in a school")))
                .map(entry -> new Cohort(entry.schoolId(), entry.enrollment().getSchoolYearId()));
    }

    /**
     * The caller's ACTIVE seat in a school, decided by a rule and not by arrival.
     *
     * <p>A student may hold one ACTIVE enrollment per school year, so two of them in
     * different schools is a legal state, and the repository does not order what it
     * returns. Taking the first to arrive would pick a school out of the database's
     * whim, and the cache would then keep whichever it happened to get. The rule here is
     * the newest enrollment among the active ones — the same one {@code /me} uses for the
     * current seat, so "the school I am at now" means the same thing in both. Filtering
     * the inactive out first is what keeps a cancelled row from steering the choice.</p>
     *
     * <p>An institutional link with no enrollment never gets this far: there is no seat,
     * so there is nothing to rank with.</p>
     */
    private Mono<ActiveSeat> firstActiveEnrollmentWithASchool(Long accountId) {
        return enrollments.findAllByAccountIdAndDeletedAtIsNull(accountId)
                .filter(Enrollment::isActive)
                .filter(enrollment -> enrollment.getClassId() != null)
                // Newest first, so the seat the student joined most recently wins. Sorting
                // here rather than in SQL keeps the rule where it is read.
                .sort(SEAT_ORDER)
                // concatMap, not filter: deciding whether an enrollment sits in a school is
                // a query, and blocking here would park a thread on the database.
                .concatMap(enrollment -> meService.classInstitutionId(enrollment.getClassId())
                        .map(schoolId -> new ActiveSeat(schoolId, enrollment)))
                .next();
    }

    /** The caller's ACTIVE enrollment in the class they are asking about. */
    private Mono<Enrollment> activeEnrollmentIn(Long accountId, Long classId) {
        return enrollments.findAllByAccountIdAndDeletedAtIsNull(accountId)
                .filter(Enrollment::isActive)
                .filter(enrollment -> classId.equals(enrollment.getClassId()))
                .next();
    }

    /**
     * The whole cohort, ordered. Every member reads the same rows, which is exactly why
     * it — and not the response — is what gets cached.
     */
    private Mono<Ranking> ranking(Scope scope, Long groupId, Long statementId, Period period) {
        CacheKey key = new CacheKey(scope, groupId, statementId, period);

        Entry running = cache.get(key);
        if (running != null && !running.expired()) {
            return running.ranking();
        }

        Mono<Ranking> computed = Mono.defer(() -> Mono.zip(
                        repository.findAverages(scope, groupId, statementId, period.from(), period.to())
                                .collectList(),
                        repository.countMembers(scope, groupId))
                        .map(tuples -> rank(tuples.getT1(), tuples.getT2())));

        prune();
        if (cache.size() >= MAX_CACHED_COHORTS) {
            // Over the bound: this request is served without taking a slot. A caller that
            // varies statementId can make their own lookups uncached, but cannot push
            // anybody else's ranking out — which is what clearing the map would let them.
            return computed;
        }

        Mono<Ranking> fresh = computed
                // cache() holds the error as well as the value, and a database that blipped
                // would then answer the whole cohort with the same failure for the whole
                // TTL. Dropping the key on the way out leaves the next caller to retry.
                .doOnError(error -> cache.remove(key))
                .cache(CACHE_TTL);

        Entry entry = new Entry(fresh, System.nanoTime() + CACHE_TTL.toNanos());
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
        // "Turma com < 10 alunos" is about the class, not about how many of them sat.
        boolean smallGroup = ranking.totalStudents() < SMALL_GROUP_LIMIT;

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

    /** The group being ranked and the year the caller's seat in it belongs to. */
    record Cohort(Long id, Long schoolYearId) { }

    /** An ACTIVE enrollment together with the school it sits in. */
    record ActiveSeat(Long schoolId, Enrollment enrollment) { }

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

    /** What identifies a cached ranking: the cohort and the window everyone shares it under. */
    record CacheKey(Scope scope, Long groupId, Long statementId, Period period) { }

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