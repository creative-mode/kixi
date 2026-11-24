package ao.creativemode.kixi.moderation.controller;

import ao.creativemode.kixi.moderation.dto.BlockRequest;
import ao.creativemode.kixi.moderation.dto.BlockResponse;
import ao.creativemode.kixi.moderation.dto.BlockedAccountResponse;
import reactor.core.publisher.Flux;
import ao.creativemode.kixi.moderation.dto.ReportRequest;
import ao.creativemode.kixi.moderation.dto.ReportResponse;
import ao.creativemode.kixi.moderation.service.ModerationService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
public class ModerationController {
    private final ModerationService service;
    private final CurrentAccountService current;

    public ModerationController(ModerationService service, CurrentAccountService current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping("/profiles/accounts/{id}/block")
    public Mono<BlockResponse> blockState(@PathVariable Long id) {
        return current.requiredAccountId().flatMap(account -> service.blockState(account, id));
    }

    /**
     * Kept for the existing client: an empty body toggles, as it always did; {@code {"blocked":true|false}}
     * is explicit and idempotent. New clients should prefer POST and DELETE below.
     */
    @PutMapping("/profiles/accounts/{id}/block")
    public Mono<BlockResponse> setBlock(@PathVariable Long id, @RequestBody(required = false) BlockRequest request) {
        if (request == null || request.blocked() == null) {
            return current.requiredAccountId().flatMap(account -> service.toggleBlock(account, id));
        }
        return request.blocked() ? block(id) : unblock(id);
    }

    @PostMapping("/profiles/accounts/{id}/block")
    public Mono<BlockResponse> block(@PathVariable Long id) {
        return current.requiredAccountId().flatMap(account -> service.block(account, id));
    }

    @DeleteMapping("/profiles/accounts/{id}/block")
    public Mono<BlockResponse> unblock(@PathVariable Long id) {
        return current.requiredAccountId().flatMap(account -> service.unblock(account, id));
    }

    @GetMapping("/profiles/blocks")
    public Flux<BlockedAccountResponse> blocked() {
        return current.requiredAccountId().flatMapMany(service::listBlocked);
    }

    @PostMapping("/profiles/accounts/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ReportResponse> reportAccount(@PathVariable Long id, @RequestBody ReportRequest request) {
        return current.requiredAccountId().flatMap(account -> service.reportAccount(account, id, request));
    }

    @PostMapping("/institutions/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ReportResponse> reportInstitution(@PathVariable Long id, @RequestBody ReportRequest request) {
        return current.requiredAccountId().flatMap(account -> service.reportInstitution(account, id, request));
    }
}
