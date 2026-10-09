package ao.creativemode.kixi.exams.controller;

import ao.creativemode.kixi.exams.controller.StatementController.StatementSummary;
import ao.creativemode.kixi.exams.dto.statement.StatementCatalogFilter;
import ao.creativemode.kixi.exams.service.StatementCatalogService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The student's catalog of past statements, under the statements base path.
 *
 * <p>Kept apart from {@link StatementController}, which is the staff-facing CRUD: the
 * catalog only ever lists visible statements, is paged, and starts from the caller's
 * own school. GET under /api/v1/statements is open to any authenticated account in
 * SecurityConfig, so no extra rule is needed.</p>
 */
@RestController
@RequestMapping("/api/v1/statements")
public class StatementCatalogController {

    private final StatementCatalogService catalogService;
    private final CurrentAccountService currentAccountService;

    public StatementCatalogController(
            StatementCatalogService catalogService,
            CurrentAccountService currentAccountService
    ) {
        this.catalogService = catalogService;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Visible statements, paged. Without {@code institutionId} the listing stays inside
     * the caller's school ({@code scope=all} lifts that), and the default
     * {@code sort=relevance} shows the caller's own class first, then their course.
     *
     * @param grade the "classe" (10, 11, 12…)
     * @param q     free text matched against the title and the exam type
     */
    @GetMapping("/catalog")
    public Mono<ResponseEntity<PageResponse<StatementSummary>>> catalog(
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Integer grade,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long termId,
            @RequestParam(required = false) String examType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + StatementCatalogFilter.DEFAULT_SIZE) int size
    ) {
        StatementCatalogFilter filter = new StatementCatalogFilter(
                institutionId, courseId, classId, grade, subjectId, schoolYearId, termId,
                examType, q, scope, sort, page, size);

        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> catalogService.catalog(filter, accountId))
                .map(result -> new PageResponse<>(
                        result.content().stream().map(StatementSummary::from).toList(),
                        result.page(),
                        result.size(),
                        result.totalElements(),
                        result.totalPages()))
                .map(ResponseEntity::ok);
    }
}
