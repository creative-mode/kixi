package ao.creativemode.kixi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ao.creativemode.kixi.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.test.StepVerifier;

import java.util.List;

class CurrentAccountServiceTest {

    private final CurrentAccountService service = new CurrentAccountService();

    @Test
    void resolvesAccountIdFromAuthenticatedPrincipal() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "42", null, List.of()
        );

        StepVerifier.create(service.requiredAccountId()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .assertNext(accountId -> assertEquals(42L, accountId))
                .verifyComplete();
    }

    @Test
    void rejectsMissingAuthentication() {
        StepVerifier.create(service.requiredAccountId())
                .expectErrorSatisfies(error -> {
                    ApiException exception = assertInstanceOf(ApiException.class, error);
                    assertEquals(401, exception.getStatusCode());
                })
                .verify();
    }

    @Test
    void rejectsNonNumericPrincipal() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "account-42", null, List.of()
        );

        StepVerifier.create(service.requiredAccountId()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .expectErrorSatisfies(error -> {
                    ApiException exception = assertInstanceOf(ApiException.class, error);
                    assertEquals(401, exception.getStatusCode());
                })
                .verify();
    }

    @Test
    void matchesRolesUsingSpringSecurityAuthorities() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "42", null, AuthorityUtils.createAuthorityList("ROLE_STUDENT")
        );

        StepVerifier.create(service.hasAnyRole("ADMIN", "TEACHER")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .assertNext(result -> assertFalse(result))
                .verifyComplete();

        StepVerifier.create(service.hasAnyRole("STUDENT")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .assertNext(result -> assertTrue(result))
                .verifyComplete();
    }
}
