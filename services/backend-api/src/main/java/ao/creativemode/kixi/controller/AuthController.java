package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.dto.auth.LoginRequest;
import ao.creativemode.kixi.dto.auth.LoginResponse;
import ao.creativemode.kixi.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Autenticação: login tradicional (username/email + password) e login Google OAuth2.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

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
        return Mono.just(ResponseEntity.status(302).location(URI.create(url)).build());
    }

    /**
     * Callback do Google: troca o code por token, obtém userinfo, encontra/cria account, emite JWT.
     * Resposta JSON com accessToken e roles (para uso em Authorization: Bearer &lt;token&gt;).
     */
    @GetMapping("/google/callback")
    public Mono<ResponseEntity<LoginResponse>> googleCallback(@RequestParam String code) {
        return authService.loginWithGoogle(code)
                .map(ResponseEntity::ok);
    }
}
