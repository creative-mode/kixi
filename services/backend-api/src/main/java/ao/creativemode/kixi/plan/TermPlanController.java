package ao.creativemode.kixi.plan;

import ao.creativemode.kixi.plan.TermPlanService.Plan;
import ao.creativemode.kixi.plan.TermPlanService.PlanInput;
import ao.creativemode.kixi.plan.TermPlanService.Reminder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** O plano do trimestre (kixi#523): professor, coordenador e administrador. As regras de quem pode o quê estão no serviço. */
@RestController
@RequestMapping("/api/v1/term-plans")
public class TermPlanController {

    public record CreateRequest(Long classId, Long subjectId, Long termId, LocalDate p1Date, LocalDate p2Date, Integer macCount,
                                List<LocalDate> macDates, List<String> macTitles) { }

    public record LinkRequest(Long statementId, Long roomId, String title, LocalDate plannedDate) { }

    private final TermPlanService service;

    public TermPlanController(TermPlanService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Plan> create(@RequestBody CreateRequest r) {
        return service.access().flatMap(who -> service.create(who, new PlanInput(r.classId(), r.subjectId(), r.termId(), r.p1Date(),
                r.p2Date(), r.macCount(), r.macDates(), r.macTitles())));
    }

    @GetMapping
    public Flux<Plan> list(@RequestParam(required = false) Long classId, @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Long termId) {
        return service.access().flatMapMany(who -> service.list(who, classId, subjectId, termId));
    }

    @GetMapping("/reminders")
    public Flux<Reminder> reminders() {
        return service.access().flatMapMany(who -> service.reminders(who, LocalDate.now()));
    }

    @GetMapping("/{id}")
    public Mono<Plan> get(@PathVariable Long id) {
        return service.access().flatMap(who -> service.get(who, id));
    }

    @PutMapping("/{id}")
    public Mono<Plan> update(@PathVariable Long id, @RequestBody CreateRequest r) {
        return service.access().flatMap(who -> service.update(who, id, new PlanInput(null, null, null, r.p1Date(), r.p2Date(),
                r.macCount(), r.macDates(), r.macTitles())));
    }

    @PatchMapping("/{id}/assessments/{assessmentId}")
    public Mono<Plan> link(@PathVariable Long id, @PathVariable Long assessmentId, @RequestBody LinkRequest r) {
        return service.access().flatMap(who -> service.link(who, id, assessmentId, r.statementId(), r.roomId(), r.title(), r.plannedDate()));
    }

    @PostMapping("/{id}/assessments/{assessmentId}/done")
    public Mono<Plan> done(@PathVariable Long id, @PathVariable Long assessmentId) {
        return service.access().flatMap(who -> service.markDone(who, id, assessmentId));
    }

    @PostMapping("/{id}/assessments/{assessmentId}/dispense")
    public Mono<Plan> dispense(@PathVariable Long id, @PathVariable Long assessmentId, @RequestBody Map<String, String> body) {
        return service.access().flatMap(who -> service.dispense(who, id, assessmentId, body.get("reason")));
    }

    public record RescheduleRequest(LocalDate date, String reason) { }

    @PostMapping("/{id}/assessments/{assessmentId}/reschedule")
    public Mono<Plan> reschedule(@PathVariable Long id, @PathVariable Long assessmentId, @RequestBody RescheduleRequest r) {
        return service.access().flatMap(who -> service.reschedule(who, id, assessmentId, r.date(), r.reason()));
    }

    @PostMapping("/{id}/approve")
    public Mono<Plan> approve(@PathVariable Long id) {
        return service.access().flatMap(who -> service.approve(who, id));
    }
}
