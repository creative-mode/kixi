package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.profile.InstitutionCard;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionProfile;
import ao.creativemode.kixi.institutions.dto.profile.PersonProfile;
import ao.creativemode.kixi.institutions.dto.profile.VisibilityRequest;
import ao.creativemode.kixi.institutions.service.PersonProfileService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ao.creativemode.kixi.institutions.dto.profile.TeacherCard;
import ao.creativemode.kixi.institutions.service.ProfileService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Public profiles (issue #331). Any signed-in account reads them; they carry a minimum, never the
 * internal records behind {@code /institutions}. A school's statements are listed by the catalog:
 * {@code GET /api/v1/statements/catalog?institutionId=}.
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService service;
    private final PersonProfileService people;
    private final CurrentAccountService currentAccount;

    public ProfileController(
        ProfileService service, PersonProfileService people, CurrentAccountService currentAccount
    ) {
        this.service = service;
        this.people = people;
        this.currentAccount = currentAccount;
    }

    /** A person's profile, as much of it as they let this viewer see (issue #336). */
    @GetMapping("/users/{userId}")
    public Mono<ResponseEntity<PersonProfile>> person(@PathVariable Long userId) {
        return Mono.zip(currentAccount.requiredAccountId(), currentAccount.hasAnyRole("ADMIN"))
            .flatMap(who -> people.person(who.getT1(), who.getT2(), userId))
            .map(ResponseEntity::ok);
    }

    @GetMapping("/me/visibility")
    public Mono<ResponseEntity<Map<String, String>>> myVisibility() {
        return currentAccount.requiredAccountId()
            .flatMap(people::myVisibility)
            .map(visibility -> ResponseEntity.ok(Map.of("visibility", visibility)));
    }

    @PutMapping("/me/visibility")
    public Mono<ResponseEntity<Map<String, String>>> setMyVisibility(@Valid @RequestBody VisibilityRequest body) {
        return currentAccount.requiredAccountId()
            .flatMap(account -> people.setMyVisibility(account, body.visibility()))
            .map(visibility -> ResponseEntity.ok(Map.of("visibility", visibility)));
    }

    @GetMapping("/institutions/{id}")
    public Mono<ResponseEntity<InstitutionProfile>> institution(@PathVariable Long id) {
        return service.institution(id).map(ResponseEntity::ok);
    }

    @GetMapping("/institutions/{id}/teachers")
    public Mono<ResponseEntity<List<TeacherCard>>> teachers(@PathVariable Long id) {
        return service.teachersOf(id).collectList().map(ResponseEntity::ok);
    }

    @GetMapping("/search")
    public Mono<ResponseEntity<List<InstitutionCard>>> search(@RequestParam("q") String q) {
        return service.searchInstitutions(q).collectList().map(ResponseEntity::ok);
    }
}
