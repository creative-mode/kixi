package ao.creativemode.kixi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.dto.auth.LoginResponse;
import ao.creativemode.kixi.service.AuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthControllerTest {

    private AuthService authService;
    private GoogleOAuth2Properties googleProperties;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        googleProperties = new GoogleOAuth2Properties();
        googleProperties.setClientId("google-client");
        googleProperties.setRedirectUri("https://api.example.test/api/v1/auth/google/callback");
        controller = new AuthController(authService, googleProperties);
    }

    @Test
    void redirectPersistsStateInHttpOnlyLaxCookie() {
        ResponseEntity<Void> response = controller.googleRedirect(null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst("Location"))
                .contains("state=");
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("kixi_oauth_state=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/api/v1/auth/google");
    }

    @Test
    void callbackRejectsMissingOrMismatchedStateBeforeCallingGoogle() {
        StepVerifier.create(controller.googleCallback("code", "received", "stored"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();

        verify(authService, never()).loginWithGoogle("code");
    }

    @Test
    void callbackClearsStateCookieAfterSuccessfulExchange() {
        LoginResponse loginResponse = new LoginResponse(
                "token",
                LoginResponse.TOKEN_TYPE,
                Instant.now(),
                42L,
                List.of("STUDENT")
        );
        org.mockito.Mockito.when(authService.loginWithGoogle("code"))
                .thenReturn(Mono.just(loginResponse));

        ResponseEntity<LoginResponse> response = controller
                .googleCallback("code", "same-state", "same-state")
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isEqualTo(loginResponse);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("kixi_oauth_state=")
                .contains("Max-Age=0");
    }
}
