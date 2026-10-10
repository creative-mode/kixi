package ao.creativemode.kixi.simulations.service;

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
 * <p>Three rules shape it:</p>
 * <ol>
 *   <li><b>The caller decides the cohort.</b> {@code scope=class} and {@code school}
 *       resolve through {@code /me}, never from a parameter, so nobody can ask for a
 *       group they do not belong to.</li>
 *   <li><b>The cache holds the cohort, the answer does not.</b> A ranking is cached per
 *       cohort because every member reads the same rows; the window that comes back is
 *       built per request. Caching the response instead would hand one caller somebody
 *       else's neighbourhood.</li>
 *   <li><b>A small group publishes no podium.</b> Under ten students a full ranking would
 *       name who is last, which is the exposure the issue asks to avoid. The caller
 *       still sees their own position and immediate neighbours.</li>
 * </ol>
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

    /** Bound on the cache so a caller cannot grow it by varying the statement. */
    private static final int MAX_CACHED_GROUPS = 500;

    private final MeService meService;
    private final LeaderboardRepository repository;

    private final Map<CacheKey, Mono<Ranking>> cache = new ConcurrentHashMap<>();

    public LeaderboardService(MeService meService, LeaderboardRepository repository) {
        this.meService = meService;
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
                    .flatMap(groupId -> ranking(resolved, groupId, statementId, window)
                            .flatMap(ranked -> windowAround(accountId, ranked, resolved, groupId, statementId, window)));
        });
    }

    /** The class or the school the caller actually belongs to. */
    private Mono<Long> cohortOf(Long accountId, Scope scope) {
        return meService.getMe(accountId)
                .flatMap(me -> switch (scope) {
                    case CLASS -> me.currentClass() != null
                            ? Mono.just(me.currentClass().id())
                            : Mono.error(ApiException.forbidden(
                                    "A class ranking needs an enrollment; this account has none"));
                    case SCHOOL -> me.school() != null
                            ? Mono.just(me.school().id())
                            : Mono.error(ApiException.forbidden(
                                    "A school ranking needs an affiliation; this account has none"));
                });
    }

    /**
     * The whole cohort, ordered. Every member reads the same rows, which is exactly why
     * it — and not the response — is what gets cached.
     */
    private Mono<Ranking> ranking(Scope scope, Long groupId, Long statementId, Period period) {
        CacheKey key = new CacheKey(scope, groupId, statementId, period);
        Mono<Ranking> fresh = Mono.defer(() -> Mono.zip(
                        repository.findAverages(scope, groupId, statementId, period.from(), period.to())
                                .collectList(),
                        repository.countMembers(scope, groupId))
                        .map(tuples -> rank(tuples.getT1(), tuples.getT2())))
                .cache(CACHE_TTL);

        Mono<Ranking> running = cache.putIfAbsent(key, fresh);
        if (running == null && cache.size() > MAX_CACHED_GROUPS) {
            // A cached Mono re-reads its source once the TTL is up, so an entry left in
            // the map past its TTL never serves a stale ranking — it only costs memory.
            // The bound stops that memory growing with whatever the caller varies.
            cache.clear();
        }
        return running != null ? running : fresh;
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
                .map(entry -> new LeaderboardResponse.Entry(
                        entry.position(),
                        entry.accountId(),
                        labels.getOrDefault(entry.accountId(), "Aluno"),
                        entry.average(),
                        entry.accountId().equals(accountId)))
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
}