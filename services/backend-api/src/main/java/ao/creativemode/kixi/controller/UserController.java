package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.dto.users.UserResponse;
import ao.creativemode.kixi.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Retrieves all active (non-deleted) users.
     */
    @GetMapping
    public Mono<ResponseEntity<List<UserResponse>>> listAllActive() {
        return service.findAllActive()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all soft-deleted (trashed) users.
     */
    @GetMapping("/trash")
    public Mono<ResponseEntity<List<UserResponse>>> listTrashed() {
        return service.findAllDeleted()
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves a single active user by ID.
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<UserResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id)
                .map(ResponseEntity::ok);
    }

    /**
     * Retrieves all active users by account ID.
     */
    @GetMapping("/account/{accountId}")
    public Mono<ResponseEntity<List<UserResponse>>> getByAccountId(@PathVariable Long accountId) {
        return service.findByAccountIdActive(accountId)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Creates a new user.
     */
    @PostMapping
    public Mono<ResponseEntity<UserResponse>> create(
            @Valid @RequestBody UserRequest request,
            UriComponentsBuilder uriBuilder) {

        return service.create(request)
                .map(created -> {
                    URI location = uriBuilder
                            .path("/api/v1/users/{id}")
                            .buildAndExpand(created.id())
                            .toUri();

                    return ResponseEntity.created(location).body(created);
                });
    }

    /**
     * Updates an existing active user.
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<UserResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {

        return service.update(id, request)
                .map(ResponseEntity::ok);
    }

    /**
     * Soft-deletes a user (moves it to trash).
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id)
                .thenReturn(ResponseEntity.status(NO_CONTENT).build());
    }

    /**
     * Restores a soft-deleted user from trash.
     */
    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id)
                .thenReturn(ResponseEntity.ok().build());
    }
}
