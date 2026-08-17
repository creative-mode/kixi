package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Resolves the account represented by the authenticated JWT. */
@Service
public class CurrentAccountService {

    public Mono<Long> requiredAccountId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(this::isAuthenticated)
                .map(Authentication::getName)
                .flatMap(this::parseAccountId)
                .switchIfEmpty(Mono.error(
                        ApiException.unauthorized("An authenticated account is required")
                ));
    }

    public Mono<Boolean> hasAnyRole(String... roles) {
        var requiredAuthorities = AuthorityUtils.createAuthorityList(
                java.util.Arrays.stream(roles)
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .toArray(String[]::new)
        );

        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(this::isAuthenticated)
                .map(authentication -> authentication.getAuthorities().stream()
                        .anyMatch(requiredAuthorities::contains))
                .defaultIfEmpty(false);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null;
    }

    private Mono<Long> parseAccountId(String principal) {
        try {
            return Mono.just(Long.valueOf(principal));
        } catch (NumberFormatException exception) {
            return Mono.error(ApiException.unauthorized("Invalid authenticated account"));
        }
    }
}
