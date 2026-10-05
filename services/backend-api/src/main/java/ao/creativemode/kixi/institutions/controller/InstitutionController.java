package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.institution.InstitutionRequest;
import ao.creativemode.kixi.institutions.dto.institution.InstitutionResponse;
import ao.creativemode.kixi.institutions.dto.membership.StudentLinkResponse;
import ao.creativemode.kixi.institutions.dto.membership.SubjectLinkResponse;
import ao.creativemode.kixi.institutions.dto.membership.TeacherLinkResponse;
import ao.creativemode.kixi.institutions.service.InstitutionMembershipService;
import ao.creativemode.kixi.institutions.service.InstitutionService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/institutions")
public class InstitutionController {

    private final InstitutionService service;
    private final InstitutionMembershipService membership;
    private final CurrentAccountService currentAccountService;

    public InstitutionController(
        InstitutionService service,
        InstitutionMembershipService membership,
        CurrentAccountService currentAccountService
    ) {
        this.service = service;
        this.membership = membership;
        this.currentAccountService = currentAccountService;
    }

    @GetMapping
    public Mono<ResponseEntity<List<InstitutionResponse>>> listAllActive() {
        return service.findAllActive().collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/trash")
    public Mono<ResponseEntity<List<InstitutionResponse>>> listTrashed() {
        return service.findAllDeleted().collectList().map(ResponseEntity::ok);
    }

    /** The institutions the signed-in account belongs to (all of them for an administrator). */
    @GetMapping("/mine")
    public Mono<ResponseEntity<List<InstitutionResponse>>> listMine() {
        return Mono.zip(currentAccountService.requiredAccountId(), currentAccountService.hasAnyRole("ADMIN"))
            .flatMap(tuple ->
                membership.findForAccount(tuple.getT1(), tuple.getT2()).collectList())
            .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<InstitutionResponse>> getById(@PathVariable Long id) {
        return service.findByIdActive(id).map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<InstitutionResponse>> create(
            @Valid @RequestBody InstitutionRequest request,
            UriComponentsBuilder uriBuilder
    ) {
        return service.create(request)
                .map(institution -> {
                    URI location = uriBuilder
                            .path("/api/v1/institutions/{id}")
                            .buildAndExpand(institution.id())
                            .toUri();
                    return ResponseEntity.created(location).body(institution);
                });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<InstitutionResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionRequest request
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

    // ── Subjects of the institution ─────────────────────────────────────────

    @GetMapping("/{id}/subjects")
    public Mono<ResponseEntity<List<SubjectLinkResponse>>> listSubjects(@PathVariable Long id) {
        return membership.findSubjects(id).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/subjects/{subjectId}")
    public Mono<ResponseEntity<Void>> addSubject(@PathVariable Long id, @PathVariable Long subjectId) {
        return membership.addSubject(id, subjectId).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/subjects/{subjectId}")
    public Mono<ResponseEntity<Void>> removeSubject(@PathVariable Long id, @PathVariable Long subjectId) {
        return membership.removeSubject(id, subjectId).thenReturn(ResponseEntity.noContent().build());
    }

    // ── Teachers of the institution ─────────────────────────────────────────

    @GetMapping("/{id}/teachers")
    public Mono<ResponseEntity<List<TeacherLinkResponse>>> listTeachers(@PathVariable Long id) {
        return membership.findTeachers(id).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/teachers/{teacherId}")
    public Mono<ResponseEntity<Void>> addTeacher(@PathVariable Long id, @PathVariable Long teacherId) {
        return membership.addTeacher(id, teacherId).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/teachers/{teacherId}")
    public Mono<ResponseEntity<Void>> removeTeacher(@PathVariable Long id, @PathVariable Long teacherId) {
        return membership.removeTeacher(id, teacherId).thenReturn(ResponseEntity.noContent().build());
    }

    // ── Students of the institution ─────────────────────────────────────────

    @GetMapping("/{id}/students")
    public Mono<ResponseEntity<List<StudentLinkResponse>>> listStudents(@PathVariable Long id) {
        return membership.findStudents(id).collectList().map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/students/{userId}")
    public Mono<ResponseEntity<Void>> addStudent(@PathVariable Long id, @PathVariable Long userId) {
        return membership.addStudent(id, userId).thenReturn(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{id}/students/{userId}")
    public Mono<ResponseEntity<Void>> removeStudent(@PathVariable Long id, @PathVariable Long userId) {
        return membership.removeStudent(id, userId).thenReturn(ResponseEntity.noContent().build());
    }
}
