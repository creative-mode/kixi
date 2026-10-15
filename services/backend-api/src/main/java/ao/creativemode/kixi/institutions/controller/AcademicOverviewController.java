package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.ClassSubject;
import ao.creativemode.kixi.institutions.dto.structure.InstitutionStructureResponse;
import ao.creativemode.kixi.institutions.dto.structure.SubjectOfferingsResponse;
import ao.creativemode.kixi.institutions.dto.structure.TeacherOverviewResponse;
import ao.creativemode.kixi.institutions.service.AcademicOverviewService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read models of the academic structure (issue #253). Who may call what is decided in the security
 * config: the school tree, the teacher overview and the offerings of a subject are for ADMIN
 * ({@code /teachers/me/overview} also for TEACHER), the subjects of a class for any signed-in
 * account, which sees the teachers only when it is ADMIN or TEACHER.
 */
@RestController
@RequestMapping("/api/v1")
public class AcademicOverviewController {

    private final AcademicOverviewService service;
    private final CurrentAccountService currentAccountService;

    public AcademicOverviewController(AcademicOverviewService service, CurrentAccountService currentAccountService) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    @GetMapping("/institutions/{id}/structure")
    public Mono<ResponseEntity<InstitutionStructureResponse>> structure(
            @PathVariable Long id, @RequestParam(required = false) Long schoolYearId) {
        return service.structure(id, schoolYearId).map(ResponseEntity::ok);
    }

    @GetMapping("/classes/{id}/subjects")
    public Mono<ResponseEntity<List<ClassSubject>>> classSubjects(@PathVariable Long id) {
        return currentAccountService.hasAnyRole("ADMIN", "TEACHER")
            .flatMap(includeTeachers -> service.classSubjects(id, includeTeachers))
            .map(ResponseEntity::ok);
    }

    @GetMapping("/subjects/{code}/offerings")
    public Mono<ResponseEntity<SubjectOfferingsResponse>> offerings(@PathVariable String code) {
        return service.subjectOfferings(code).map(ResponseEntity::ok);
    }

    @GetMapping("/teachers/{id}/overview")
    public Mono<ResponseEntity<TeacherOverviewResponse>> teacherOverview(@PathVariable Long id) {
        return service.teacherOverview(id).map(ResponseEntity::ok);
    }

    @GetMapping("/teachers/me/overview")
    public Mono<ResponseEntity<TeacherOverviewResponse>> myOverview() {
        return currentAccountService.requiredAccountId()
            .flatMap(service::teacherOverviewForAccount)
            .map(ResponseEntity::ok);
    }
}
