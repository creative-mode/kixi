package ao.creativemode.kixi.analytics.controller;

import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.ActivityPoint;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.ClassStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.InstitutionStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Overview;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.QuestionStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.StatementStats;
import ao.creativemode.kixi.analytics.service.AnalyticsService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The dashboard endpoints of issue #110, at the paths the manager already calls.
 *
 * <p>No endpoint takes a class, a school or a teacher: the audience is resolved from the
 * caller, so there is no combination of query parameters that reaches somebody else's
 * numbers.</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final CurrentAccountService currentAccountService;
    private final AnalyticsService analytics;

    public AnalyticsController(
            CurrentAccountService currentAccountService, AnalyticsService analytics) {
        this.currentAccountService = currentAccountService;
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    public Mono<ResponseEntity<Overview>> overview() {
        return currentAccountService.requiredAccountId()
                .flatMap(analytics::overview)
                .map(ResponseEntity::ok);
    }

    /** @param days how many days back, clamped to 1–365 */
    @GetMapping("/activity")
    public Flux<ActivityPoint> activity(@RequestParam(required = false) Integer days) {
        return currentAccountService.requiredAccountId()
                .flatMapMany(accountId -> analytics.activity(accountId, days));
    }

    @GetMapping("/institutions")
    public Flux<InstitutionStats> institutions() {
        return currentAccountService.requiredAccountId()
                .flatMapMany(analytics::institutions);
    }

    @GetMapping("/classes")
    public Flux<ClassStats> classes() {
        return currentAccountService.requiredAccountId()
                .flatMapMany(analytics::classes);
    }

    /** @param limit how many statements, clamped to 1–200 and 50 by default */
    @GetMapping("/statements")
    public Flux<StatementStats> statements(@RequestParam(required = false) Integer limit) {
        return currentAccountService.requiredAccountId()
                .flatMapMany(accountId -> analytics.statements(accountId, limit));
    }

    /** 404s when the paper does not exist or is not one the caller may read. */
    @GetMapping("/statements/{statementId}/questions")
    public Flux<QuestionStats> questions(@PathVariable Long statementId) {
        return currentAccountService.requiredAccountId()
                .flatMapMany(accountId -> analytics.questions(accountId, statementId));
    }
}