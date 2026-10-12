package ao.creativemode.kixi.analytics.controller;

import ao.creativemode.kixi.analytics.dto.ActivityPoint;
import ao.creativemode.kixi.analytics.dto.ClassStats;
import ao.creativemode.kixi.analytics.dto.ClassStudentStats;
import ao.creativemode.kixi.analytics.dto.HardQuestion;
import ao.creativemode.kixi.analytics.dto.InstitutionStats;
import ao.creativemode.kixi.analytics.dto.Overview;
import ao.creativemode.kixi.analytics.dto.QuestionStats;
import ao.creativemode.kixi.analytics.dto.StatementStats;
import ao.creativemode.kixi.analytics.dto.WeeklyPoint;
import ao.creativemode.kixi.analytics.service.AnalyticsService;
import ao.creativemode.kixi.analytics.service.AnalyticsService.Filters;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The dashboard of the manager (BE-14), narrowed by role (BE-15). The contract is
 * {@code types/analytics.ts} in kixi-frontend. ADMIN and TEACHER only
 * ({@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Mono<ResponseEntity<Overview>> overview(
            @RequestParam(required = false) Long institutionId, @RequestParam(required = false) Long classId) {
        return Mono.defer(() -> service.overview(new Filters(institutionId, classId))).map(ResponseEntity::ok);
    }

    @GetMapping("/activity")
    public Mono<ResponseEntity<List<ActivityPoint>>> activity(@RequestParam(required = false) Integer days,
            @RequestParam(required = false) Long institutionId, @RequestParam(required = false) Long classId) {
        return Mono.defer(() -> service.activity(days, new Filters(institutionId, classId)).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/institutions")
    public Mono<ResponseEntity<List<InstitutionStats>>> institutions(
            @RequestParam(required = false) Long institutionId, @RequestParam(required = false) Long classId) {
        return Mono.defer(() -> service.institutions(new Filters(institutionId, classId)).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/classes")
    public Mono<ResponseEntity<List<ClassStats>>> classes(
            @RequestParam(required = false) Long institutionId, @RequestParam(required = false) Long classId) {
        return Mono.defer(() -> service.classes(new Filters(institutionId, classId)).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/statements")
    public Mono<ResponseEntity<List<StatementStats>>> statements(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long institutionId, @RequestParam(required = false) Long classId) {
        return Mono.defer(() -> service.statements(limit, new Filters(institutionId, classId)).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/statements/{id}/questions")
    public Mono<ResponseEntity<List<QuestionStats>>> statementQuestions(@PathVariable Long id) {
        return service.statementQuestions(id).collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/classes/{id}/students")
    public Mono<ResponseEntity<List<ClassStudentStats>>> classStudents(@PathVariable Long id) {
        return service.classStudents(id).collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/classes/{id}/weekly")
    public Mono<ResponseEntity<List<WeeklyPoint>>> classWeekly(
            @PathVariable Long id, @RequestParam(required = false) Integer weeks) {
        return Mono.defer(() -> service.classWeekly(id, weeks).collectList()).map(ResponseEntity::ok);
    }

    @GetMapping("/classes/{id}/questions")
    public Mono<ResponseEntity<List<HardQuestion>>> classHardestQuestions(
            @PathVariable Long id, @RequestParam(required = false) Integer limit) {
        return Mono.defer(() -> service.classHardestQuestions(id, limit).collectList()).map(ResponseEntity::ok);
    }
}
