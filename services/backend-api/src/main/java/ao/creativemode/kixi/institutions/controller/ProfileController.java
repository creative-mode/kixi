package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.profile.InstitutionCard;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionProfile;
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

    public ProfileController(ProfileService service) {
        this.service = service;
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
