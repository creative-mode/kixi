package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.statement.StatementCatalogFilter;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Criteria;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Sort;
import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.Locale;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The catalog of past statements a student browses: only visible ones, filtered and
 * paged, starting from the student's own affiliation.
 *
 * <p>The affiliation (school, course, class) is the one {@link MeService} already
 * resolves for {@code /me}, so the catalog and the profile can never disagree about
 * where the student studies. With no explicit school and the default scope, the listing
 * stays inside the student's school; an explicit {@code institutionId}, or
 * {@code scope=all}, widens it. An account with no affiliation (staff, or a student not
 * enrolled yet) simply sees every school.</p>
 */
@Service
public class StatementCatalogService {

    static final int MAX_QUERY_LENGTH = 200;

    private final StatementCatalogRepository repository;
    private final MeService meService;

    public StatementCatalogService(StatementCatalogRepository repository, MeService meService) {
        this.repository = repository;
        this.meService = meService;
    }

    public Mono<PageResponse<Statement>> catalog(StatementCatalogFilter filter, Long accountId) {
        return Mono.defer(() -> {
            validatePaging(filter);
            Sort sort = parseSort(filter.sort());
            boolean mine = parseScopeIsMine(filter.scope());
            String query = trimToNull(filter.query());
            if (query != null && query.length() > MAX_QUERY_LENGTH) {
                return Mono.error(ApiException.badRequest(
                        "Search text cannot exceed " + MAX_QUERY_LENGTH + " characters"));
            }

            boolean defaultsToOwnSchool = mine && filter.institutionId() == null;
            Mono<Affiliation> affiliation = defaultsToOwnSchool || sort == Sort.RELEVANCE
                    ? affiliationOf(accountId)
                    : Mono.just(Affiliation.NONE);

            return affiliation.flatMap(own -> {
                Criteria criteria = new Criteria(
                        defaultsToOwnSchool ? own.institutionId() : filter.institutionId(),
                        filter.courseId(),
                        filter.classId(),
                        filter.grade(),
                        filter.subjectId(),
                        filter.schoolYearId(),
                        filter.termId(),
                        trimToNull(filter.examType()),
                        query,
                        own.classId(),
                        own.courseId(),
                        sort,
                        filter.size(),
                        (long) filter.page() * filter.size());

                return Mono.zip(repository.findPage(criteria).collectList(), repository.count(criteria))
                        .map(page -> PageResponse.of(page.getT1(), filter.page(), filter.size(), page.getT2()));
            });
        });
    }

    private Mono<Affiliation> affiliationOf(Long accountId) {
        return meService.getMe(accountId).map(me -> new Affiliation(
                me.school() != null ? me.school().id() : null,
                me.course() != null ? me.course().id() : null,
                idOf(me.currentClass())));
    }

    private static Long idOf(MeResponse.ClassInfo currentClass) {
        return currentClass != null ? currentClass.id() : null;
    }

    private static void validatePaging(StatementCatalogFilter filter) {
        if (filter.page() < 0) {
            throw ApiException.badRequest("Page cannot be negative");
        }
        if (filter.size() < 1 || filter.size() > StatementCatalogFilter.MAX_SIZE) {
            throw ApiException.badRequest(
                    "Size must be between 1 and " + StatementCatalogFilter.MAX_SIZE);
        }
    }

    private static Sort parseSort(String sort) {
        String value = trimToNull(sort);
        if (value == null) {
            return Sort.RELEVANCE;
        }
        try {
            return Sort.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "Unknown sort '" + value + "'. Use relevance, recent, oldest or title");
        }
    }

    private static boolean parseScopeIsMine(String scope) {
        String value = trimToNull(scope);
        if (value == null || value.equalsIgnoreCase("mine")) {
            return true;
        }
        if (value.equalsIgnoreCase("all")) {
            return false;
        }
        throw ApiException.badRequest("Unknown scope '" + value + "'. Use mine or all");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Affiliation(Long institutionId, Long courseId, Long classId) {
        static final Affiliation NONE = new Affiliation(null, null, null);
    }
}
