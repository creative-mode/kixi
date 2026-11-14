package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.PublicInstitution;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.security.ClientAddress;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The public catalogue of schools (kixi#597): the list the donation form offers, and the only
 * readable record of them without a session.
 *
 * <p>Scope is deliberately narrow: name and sigla of verified, non-deleted schools. No ids of
 * external systems, no internal codes, no logo, no contact details. Rate limited so the list
 * cannot be used to enumerate verified schools faster than a client can scroll them.</p>
 *
 * <p><strong>Stable under load.</strong> The payload is tiny and cached at the client; an empty
 * list is an honest answer when no school is verified yet, never a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/institutions")
public class InstitutionsPublicController {

    private final InstitutionRepository institutions;
    private final ApiAbuseRateLimiter rateLimiter;
    private final TrustedProxyProperties trustedProxies;

    public InstitutionsPublicController(InstitutionRepository institutions, ApiAbuseRateLimiter rateLimiter, TrustedProxyProperties trustedProxies) {
        this.institutions = institutions;
        this.rateLimiter = rateLimiter;
        this.trustedProxies = trustedProxies;
    }

    /** The verified, non-deleted schools as (id, name, sigla), ordered by name. */
    @GetMapping("/public")
    public Mono<ResponseEntity<List<PublicInstitution>>> listPublic(org.springframework.web.server.ServerWebExchange exchange) {
        // The address a request is counted against, exactly as the donation and login endpoints count.
        String address = ClientAddress.resolve(exchange, trustedProxies);
        // 60 requests per minute per client and 6000 per day platform-wide. Generous for the
        // donation form, tight enough to stop bulk enumeration.
        return rateLimiter.checkInstitutionsPublic(address)
                .then(institutions.findAllByVerifiedIsTrueAndDeletedAtIsNullOrderByNameAsc()
                        .map(this::toPublic)
                        .collectList())
                .map(ResponseEntity::ok);
    }

    private PublicInstitution toPublic(Institution row) {
        return new PublicInstitution(row.getId(), row.getName(), row.getShortName());
    }
}
