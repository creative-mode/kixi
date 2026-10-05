package ao.creativemode.kixi.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.identity.dto.auth.LoginResponse;
import ao.creativemode.kixi.identity.dto.auth.RegisterRequest;
import ao.creativemode.kixi.identity.service.AuthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
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

    @Test
    void registerReturnsCreatedWithLoginResponse() {
        RegisterRequest request = new RegisterRequest(
                "student", "student@kixi.ao", "password123", "Ana", "Silva");
        LoginResponse loginResponse = new LoginResponse(
                "token", LoginResponse.TOKEN_TYPE, Instant.now(), 9L, List.of("STUDENT"));
        org.mockito.Mockito.when(authService.register(request)).thenReturn(Mono.just(loginResponse));

        ResponseEntity<LoginResponse> response = controller.register(request).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(loginResponse);
        verify(authService).register(request);
    }

    @Test
    void registerRejectsInvalidPayloadBeforeCallingService() {
        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "username": "student",
                          "email": "not-an-email",
                          "password": "short",
                          "firstName": "",
                          "lastName": "Silva"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(authService, never()).register(org.mockito.ArgumentMatchers.any(RegisterRequest.class));
    }
}
