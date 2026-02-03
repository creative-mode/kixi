package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.accounts.AccountRequest;
import ao.creativemode.kixi.dto.accounts.AccountResponse;
import ao.creativemode.kixi.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /**
     * Retrieves all active (non-deleted) accounts.
     */
    @GetMapping
    public Mono<ResponseEntity<List<AccountResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) accounts.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<AccountResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves accounts filtered by active status.
     */
    @GetMapping("/active")
    public Mono<ResponseEntity<List<AccountResponse>>> listByActive(
            @RequestParam(defaultValue = "true") Boolean active) {
        return service.findAllByActive(active)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active account by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<AccountResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active account by username.
     */
    @GetMapping("/username/{username}")
    public Mono<ResponseEntity<AccountResponse>> getByUsername(@PathVariable String username) {
        return service.findByUsername(username)
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new account.
     */
    @PostMapping
    public Mono<ResponseEntity<AccountResponse>> create(
            @Valid @RequestBody AccountRequest request,
            UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/accounts/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active account.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<AccountResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody AccountRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes an account (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted account from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }

    /**
     * Records a login event for an account.
     */
    @PostMapping("/{id}/login")
    public Mono<ResponseEntity<AccountResponse>> recordLogin(@PathVariable Long id) {
        return service.recordLogin(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Permanently deletes a account (only if already soft-deleted).
     */
    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }
}
