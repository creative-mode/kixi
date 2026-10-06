package ao.creativemode.kixi.institutions.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.dto.enrollment.MeUpdateRequest;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService service;
    private final CurrentAccountService currentAccountService;

    public MeController(MeService service, CurrentAccountService currentAccountService) {
        this.service = service;
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
}
