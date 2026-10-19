package ao.creativemode.kixi.institutions.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.dto.enrollment.MeUpdateRequest;
import ao.creativemode.kixi.institutions.dto.experience.AcademicHistoryResponse;
import ao.creativemode.kixi.institutions.service.ExperienceService;
import ao.creativemode.kixi.institutions.service.MeService;
import org.springframework.web.bind.annotation.RequestParam;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService service;
    private final ExperienceService experience;
    private final CurrentAccountService currentAccountService;

    public MeController(
        MeService service, ExperienceService experience, CurrentAccountService currentAccountService
    ) {
        this.service = service;
        this.experience = experience;
        this.currentAccountService = currentAccountService;
    }

    @GetMapping
    public Mono<ResponseEntity<MeResponse>> getMe() {
        return currentAccountService.requiredAccountId()
            .flatMap(service::getMe)
            .map(ResponseEntity::ok);
    }

    @PutMapping
    public Mono<ResponseEntity<MeResponse>> updateMe(@Valid @RequestBody MeUpdateRequest request) {
        return currentAccountService.requiredAccountId()
            .flatMap(accountId -> service.updateMe(accountId, request))
            .map(ResponseEntity::ok);
    }

    /**
     * The signed-in student's own results in one school year. Private by construction: the route
     * has no account id, so there is nothing to point at somebody else, and administrators get no
     * exception. 404 when the caller has no enrollment in that year.
     */
    @GetMapping("/academic-history")
    public Mono<ResponseEntity<AcademicHistoryResponse>> academicHistory(@RequestParam Long schoolYearId) {
        return currentAccountService.requiredAccountId()
            .flatMap(accountId -> experience.historyOf(accountId, schoolYearId))
            .map(ResponseEntity::ok);
    }
}
