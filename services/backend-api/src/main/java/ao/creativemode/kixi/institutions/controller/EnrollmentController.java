package ao.creativemode.kixi.institutions.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import ao.creativemode.kixi.institutions.dto.enrollment.EnrollRequest;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollmentResponse;
import ao.creativemode.kixi.institutions.service.EnrollmentService;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

    private final EnrollmentService service;
    private final CurrentAccountService currentAccountService;
    private final ApiAbuseRateLimiter rateLimiter;

    public EnrollmentController(
            EnrollmentService service,
            CurrentAccountService currentAccountService,
            ApiAbuseRateLimiter rateLimiter
    ) {
        this.service = service;
        this.currentAccountService = currentAccountService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Active enrollments: the caller's own, or another account's
     * ({@code ?accountId=}) when the caller is ADMIN/TEACHER.
     *
     * <p>An ADMIN without {@code ?accountId=} gets every enrollment (#146): the
     * manager's enrollments screen manages the whole platform, and listing only
     * the administrator's own left it empty. A TEACHER may request another
     * account's enrollments only when assigned to each enrollment's class.
     */
    @GetMapping
    public Mono<ResponseEntity<List<EnrollmentResponse>>> list(
            @RequestParam(required = false) Long accountId
    ) {
        return Mono.zip(
                currentAccountService.requiredAccountId(),
                currentAccountService.hasAnyRole("ADMIN"),
                currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> (accountId == null && tuple.getT2()
                    ? service.findAllActive()
                    : service.findVisible(tuple.getT1(), tuple.getT2(), tuple.getT3(), accountId))
                .collectList())
            .map(ResponseEntity::ok);
    }

    /**
     * Enroll: the caller enrolls themselves, or (ADMIN/TEACHER) another
     * account via {@code accountId}.
     */
    @PostMapping
    public Mono<ResponseEntity<EnrollmentResponse>> enroll(
            @Valid @RequestBody EnrollRequest request,
            UriComponentsBuilder uriBuilder
    ) {
        return Mono.zip(
                currentAccountService.requiredAccountId(),
                currentAccountService.hasAnyRole("ADMIN"),
                currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> rateLimiter.checkEnrollment(tuple.getT1()).thenReturn(tuple))
            .flatMap(tuple -> service.enroll(tuple.getT1(), tuple.getT2(), tuple.getT3(), request))
            .map(enrollment -> {
                URI location = uriBuilder
                        .path("/api/v1/enrollments/{id}")
                        .buildAndExpand(enrollment.id())
                        .toUri();
                return ResponseEntity.created(location).body(enrollment);
            });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> cancel(@PathVariable Long id) {
        return Mono.zip(
                currentAccountService.requiredAccountId(),
                currentAccountService.hasAnyRole("ADMIN"),
                currentAccountService.hasAnyRole("TEACHER"))
            .flatMap(tuple -> service.cancel(tuple.getT1(), tuple.getT2(), tuple.getT3(), id))
            .thenReturn(ResponseEntity.noContent().build());
    }
}
