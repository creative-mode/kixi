package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.verification.VerificationRequestCreate;
import ao.creativemode.kixi.institutions.dto.verification.VerificationRequestResponse;
import ao.creativemode.kixi.institutions.dto.verification.VerificationReviewRequest;
import ao.creativemode.kixi.institutions.service.InstitutionVerificationService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * School verification badge (issue #332). Asking is for an administrator or a teacher of the school;
 * deciding, listing and revoking are ADMIN only. Both rules live in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1")
public class InstitutionVerificationController {

    private final InstitutionVerificationService service;
    private final CurrentAccountService currentAccount;

    public InstitutionVerificationController(
        InstitutionVerificationService service, CurrentAccountService currentAccount
    ) {
        this.service = service;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/institutions/{id}/verification-requests")
    public Mono<ResponseEntity<VerificationRequestResponse>> submit(
        @PathVariable Long id, @Valid @RequestBody VerificationRequestCreate body
    ) {
        return Mono.zip(currentAccount.requiredAccountId(), currentAccount.hasAnyRole("ADMIN"))
            .flatMap(who -> service.submit(who.getT1(), who.getT2(), id, body))
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @GetMapping("/verification-requests")
    public Mono<ResponseEntity<List<VerificationRequestResponse>>> list(
        @RequestParam(required = false) String status
    ) {
        return service.list(status).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/verification-requests/{id}/approve")
    public Mono<ResponseEntity<VerificationRequestResponse>> approve(
        @PathVariable Long id, @Valid @RequestBody(required = false) VerificationReviewRequest body
    ) {
        return currentAccount.requiredAccountId()
            .flatMap(account -> service.approve(id, account, body == null ? null : body.note()))
            .map(ResponseEntity::ok);
    }

    @PostMapping("/verification-requests/{id}/reject")
    public Mono<ResponseEntity<VerificationRequestResponse>> reject(
        @PathVariable Long id, @Valid @RequestBody VerificationReviewRequest body
    ) {
        return currentAccount.requiredAccountId()
            .flatMap(account -> service.reject(id, account, body.note()))
            .map(ResponseEntity::ok);
    }

    @PostMapping("/institutions/{id}/verification/revoke")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable Long id) {
        return service.revoke(id).thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
