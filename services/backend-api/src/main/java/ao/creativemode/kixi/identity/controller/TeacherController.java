package ao.creativemode.kixi.identity.controller;

import ao.creativemode.kixi.identity.dto.accounts.AccountRequest;
import ao.creativemode.kixi.identity.dto.teachers.TeacherRequest;
import ao.creativemode.kixi.identity.dto.teachers.TeacherResponse;
import ao.creativemode.kixi.identity.service.TeacherService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/teachers")
public class TeacherController {

    private final TeacherService service;
    private final CurrentAccountService currentAccountService;

    public TeacherController(TeacherService service, CurrentAccountService currentAccountService) {
        this.service = service;
        this.currentAccountService = currentAccountService;
    }

    /**
     * The caller's own teacher record (#176), for the first teaching assignment a
     * teacher makes. Open to TEACHER as well as ADMIN in SecurityConfig; the rest
     * of this controller stays ADMIN-only. 404 when the account has no record.
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<TeacherResponse>> mine() {
        return currentAccountService.requiredAccountId()
            .flatMap(service::findMine)
            .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<List<TeacherResponse>>> listAllActive() {
        return service.findAllActive().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<TeacherResponse>>> listTrashed() {
        return service.findAllDeleted().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TeacherResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<TeacherResponse>> create(
            @Valid @RequestBody TeacherRequest request,
            UriComponentsBuilder uriBuilder
    ) {
        return service.create(request)
                .map(teacher -> {
                    URI location = uriBuilder
                            .path("/api/v1/teachers/{id}")
                            .buildAndExpand(teacher.id())
                            .toUri();
                    return ResponseEntity.created(location).body(teacher);
                });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<TeacherResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody TeacherRequest request
    ) {
        return service.update(id, request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> softDelete(@PathVariable Long id) {
        return service.softDelete(id).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/restore")
    public Mono<ResponseEntity<Void>> restore(@PathVariable Long id) {
        return service.restore(id).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/purge")
    public Mono<ResponseEntity<Void>> hardDelete(@PathVariable Long id) {
        return service.hardDelete(id).thenReturn(ResponseEntity.noContent().build());
    }

    /** Gives the teacher platform access by creating an account with the TEACHER role. */
    @PostMapping("/{id}/account")
    public Mono<ResponseEntity<TeacherResponse>> grantAccess(
            @PathVariable Long id,
            @Valid @RequestBody AccountRequest request
    ) {
        return service.grantAccess(id, request).map(ResponseEntity::ok);
    }

    /** Takes the teacher's platform access away. */
    @DeleteMapping("/{id}/account")
    public Mono<ResponseEntity<TeacherResponse>> revokeAccess(@PathVariable Long id) {
        return service.revokeAccess(id).map(ResponseEntity::ok);
    }
}
