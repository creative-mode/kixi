package ao.creativemode.kixi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ao.creativemode.kixi.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
}
