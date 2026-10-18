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

import ao.creativemode.kixi.institutions.dto.enrollment.CreateEnrollmentCodeRequest;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollmentCodeResponse;
import ao.creativemode.kixi.institutions.service.EnrollmentCodeService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/**
 * Enrollment codes (#255): a school issues one per class, and a student trades it for a
 * self-enrollment through {@code POST /api/v1/enrollments}.
 *
 * <p>ADMIN-only for now, matching every other write under {@code institutions} that was not
 * already open to TEACHER (creating/editing a class, a school year, a course). Letting a
 * TEACHER issue codes for their own classes is a reasonable next step, but nothing in #255
 * asked for it, so it is left for whoever picks that up.</p>
 */
@RestController
@RequestMapping("/api/v1/enrollment-codes")
public class EnrollmentCodeController {

    private final EnrollmentCodeService service;
    private final CurrentAccountService currentAccountService;

    public EnrollmentCodeController(EnrollmentCodeService service, CurrentAccountService currentAccountService) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    @PostMapping
    public Mono<ResponseEntity<EnrollmentCodeResponse>> create(
            @Valid @RequestBody CreateEnrollmentCodeRequest request,
            UriComponentsBuilder uriBuilder
    ) {
        return requireAdmin()
            .then(currentAccountService.requiredAccountId())
            .flatMap(adminId -> service.create(adminId, request))
            .map(code -> {
                URI location = uriBuilder
                        .path("/api/v1/enrollment-codes/{id}")
                        .buildAndExpand(code.id())
                        .toUri();
                return ResponseEntity.created(location).body(code);
            });
    }

    @GetMapping
    public Mono<ResponseEntity<List<EnrollmentCodeResponse>>> listByClass(@RequestParam Long classId) {
        return requireAdmin()
            .then(service.listByClass(classId).collectList())
            .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<EnrollmentCodeResponse>> revoke(@PathVariable Long id) {
        return requireAdmin()
            .then(service.revoke(id))
            .map(ResponseEntity::ok);
    }

    private Mono<Void> requireAdmin() {
        return currentAccountService.hasAnyRole("ADMIN")
            .flatMap(isAdmin -> isAdmin
                ? Mono.<Void>empty()
                : Mono.error(ApiException.forbidden("Only ADMIN can manage enrollment codes")));
    }
}
