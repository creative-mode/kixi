package ao.creativemode.kixi.identity.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleLinkStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void issuedStateIsBoundToAccountNonceAndCookieSignature() {
        GoogleLinkStateService service = new GoogleLinkStateService("test-secret", Clock.fixed(NOW, ZoneOffset.UTC));
        GoogleLinkStateService.IssuedState issued = service.issue(42L);

        assertThat(service.accountForCallback(issued.state(), issued.cookieValue())).contains(42L);
        assertThat(service.accountForCallback("another-state", issued.cookieValue())).isEmpty();
        assertThat(service.accountForCallback(issued.state(), issued.cookieValue() + "x")).isEmpty();
    }

    @Test
    void expiredAndWrongSecretStatesAreRejected() {
        GoogleLinkStateService issuer = new GoogleLinkStateService("test-secret", Clock.fixed(NOW, ZoneOffset.UTC));
        GoogleLinkStateService.IssuedState issued = issuer.issue(42L);
        GoogleLinkStateService expired = new GoogleLinkStateService(
                "test-secret", Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC));
        GoogleLinkStateService wrongSecret = new GoogleLinkStateService(
                "different-secret", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(expired.accountForCallback(issued.state(), issued.cookieValue())).isEmpty();
        assertThat(wrongSecret.accountForCallback(issued.state(), issued.cookieValue())).isEmpty();
    }
}
