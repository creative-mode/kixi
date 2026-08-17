package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.auth.LoginRequest;
import ao.creativemode.kixi.dto.auth.LoginResponse;
import ao.creativemode.kixi.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Autenticação: login tradicional (username/email + password) e login Google OAuth2.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String STATE_COOKIE_NAME = "kixi_oauth_state";

    private final AuthService authService;
    private final GoogleOAuth2Properties googleProperties;

    public AuthController(AuthService authService, GoogleOAuth2Properties googleProperties) {
        this.authService = authService;
        this.googleProperties = googleProperties;
    }

    /**
     * Login tradicional: username ou email + password.
     * Devolve JWT e roles para uso em Authorization: Bearer &lt;token&gt;.
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.usernameOrEmail(), request.password())
                .map(ResponseEntity::ok);
    }

    /**
     * Redireciona o utilizador para o consentimento Google OAuth2.
     * Só funciona se app.auth.google.client-id estiver configurado.
     */
    @GetMapping("/google")
    public Mono<ResponseEntity<Void>> googleRedirect(ServerWebExchange exchange) {
        if (googleProperties.getClientId() == null || googleProperties.getClientId().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().<Void>build());
        }
        String state = java.util.UUID.randomUUID().toString();
        String url = googleProperties.buildAuthorizationUrl(state);
        return Mono.just(ResponseEntity.status(302)
                .header(HttpHeaders.SET_COOKIE, stateCookie(state).toString())
                .location(URI.create(url))
                .build());
    }

    /**
     * Callback do Google: troca o code por token, obtém userinfo, encontra/cria account, emite JWT.
     * Resposta JSON com accessToken e roles (para uso em Authorization: Bearer &lt;token&gt;).
     */
    @GetMapping("/google/callback")
    public Mono<ResponseEntity<LoginResponse>> googleCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = STATE_COOKIE_NAME, required = false) String expectedState) {
        if (!isValidState(state, expectedState)) {
            return Mono.error(ApiException.badRequest("Invalid OAuth state"));
        }

        return authService.loginWithGoogle(code)
                .map(response -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearStateCookie().toString())
                        .body(response));
    }

    private ResponseCookie stateCookie(String state) {
        return ResponseCookie.from(STATE_COOKIE_NAME, state)
                .httpOnly(true)
                .secure(googleProperties.isStateCookieSecure())
                .sameSite("Lax")
                .path("/api/v1/auth/google")
                .maxAge(Duration.ofMinutes(10))
                .build();
    }

    private ResponseCookie clearStateCookie() {
        return ResponseCookie.from(STATE_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(googleProperties.isStateCookieSecure())
                .sameSite("Lax")
                .path("/api/v1/auth/google")
                .maxAge(Duration.ZERO)
                .build();
    }

    private boolean isValidState(String state, String expectedState) {
        if (state == null || expectedState == null) {
            return false;
        }
        return MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8),
                expectedState.getBytes(StandardCharsets.UTF_8));
    }
}
