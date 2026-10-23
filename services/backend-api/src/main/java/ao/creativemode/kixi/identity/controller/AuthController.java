package ao.creativemode.kixi.identity.controller;

import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.auth.LoginRequest;
import ao.creativemode.kixi.identity.dto.auth.LoginResponse;
import ao.creativemode.kixi.identity.dto.auth.GoogleLinkAuthorizationRequest;
import ao.creativemode.kixi.identity.dto.auth.GoogleLinkAuthorizationResponse;
import ao.creativemode.kixi.identity.dto.auth.RegisterRequest;
import ao.creativemode.kixi.identity.service.AuthService;
import ao.creativemode.kixi.identity.service.GoogleLinkStateService;
import ao.creativemode.kixi.identity.service.LoginRateLimiter;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.identity.service.RegistrationRateLimiter;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Autenticação: login tradicional (username/email + password) e login Google OAuth2.
 */
@RestController
@RequestMapping("/api/v1/auth")
// Rotas públicas: anula o requisito global de bearer para não mostrar o
// cadeado no Swagger de login/registo, que não exigem token.
@SecurityRequirements()
public class AuthController {

    private static final String STATE_COOKIE_NAME = "kixi_oauth_state";

    private final AuthService authService;
    private final GoogleOAuth2Properties googleProperties;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final LoginRateLimiter loginRateLimiter;
    private final TrustedProxyProperties trustedProxies;
    private final ApiAbuseRateLimiter abuseRateLimiter;
    private final CurrentAccountService currentAccountService;
    private final GoogleLinkStateService googleLinkStateService;

    public AuthController(AuthService authService,
                          GoogleOAuth2Properties googleProperties,
                          RegistrationRateLimiter registrationRateLimiter,
                          LoginRateLimiter loginRateLimiter,
                          TrustedProxyProperties trustedProxies,
                          ApiAbuseRateLimiter abuseRateLimiter,
                          CurrentAccountService currentAccountService,
                          GoogleLinkStateService googleLinkStateService) {
        this.authService = authService;
        this.googleProperties = googleProperties;
        this.registrationRateLimiter = registrationRateLimiter;
        this.loginRateLimiter = loginRateLimiter;
        this.trustedProxies = trustedProxies;
        this.abuseRateLimiter = abuseRateLimiter;
        this.currentAccountService = currentAccountService;
        this.googleLinkStateService = googleLinkStateService;
    }

    /**
     * Login tradicional: username ou email + password.
     * Devolve JWT e roles para uso em Authorization: Bearer &lt;token&gt;.
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(
            ServerWebExchange exchange,
            @Valid @RequestBody LoginRequest request) {
        // Issue #123: /api/v1/auth/** is permitAll, so this was the one door of the
        // platform with nothing counting how often it was knocked on. Two keys, because
        // either alone is half a defence: the address stops one host spraying names, the
        // identity stops many hosts converging on one account.
        return loginRateLimiter.checkAddress("login-ip:" + clientAddress(exchange))
                .then(loginRateLimiter.checkIdentity(
                        "login-identity:" + request.usernameOrEmail().trim().toLowerCase(Locale.ROOT)))
                // defer, not a bare call: `then(authService.login(...))` would assemble
                // the credential lookup before the window had been consulted, so a
                // refused attempt still paid for building it. Nothing subscribes under
                // defer until the checks have completed.
                .then(Mono.defer(() -> authService.login(
                        request.usernameOrEmail(), request.password())))
                .map(ResponseEntity::ok);
    }

    /**
     * Registo público de uma conta de aluno. O papel STUDENT é atribuído no
     * serviço e nunca é aceite a partir do payload do cliente.
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<LoginResponse>> register(
            ServerWebExchange exchange,
            @Valid @RequestBody RegisterRequest request) {
        return registrationRateLimiter.check(clientAddress(exchange))
                .then(authService.register(request))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    private String identityKey(RegisterRequest request) {
        return "identity:" + request.email().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The address to count an attempt against.
     *
     * <p>{@code X-Forwarded-For} is read only when the peer that sent it is listed in
     * {@link TrustedProxyProperties}. That condition is the whole design and it cuts both
     * ways: believing the header unconditionally lets any caller invent a fresh address
     * per attempt, while ignoring it behind a real proxy makes every user on the platform
     * share one bucket, and a login limiter counting the whole school as a single client
     * locks everyone out at once. So the default is to believe nothing, and the operator
     * names the proxy when the topology makes the header the honest answer.</p>
     *
     * <p>Only the rightmost entry is used. A trusted direct peer must not let an earlier,
     * client-supplied value in a forwarded chain choose the rate-limit bucket. An empty
     * final entry falls back to the address rather than keying everyone on "".
     */
    private String clientAddress(ServerWebExchange exchange) {
        return ao.creativemode.kixi.shared.security.ClientAddress.resolve(exchange, trustedProxies);
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
        return abuseRateLimiter.checkLogin(clientAddress(exchange))
                .then(Mono.fromSupplier(() -> ResponseEntity.status(302)
                        .header(HttpHeaders.SET_COOKIE, stateCookie(state).toString())
                        .location(URI.create(url))
                        .build()));
    }

    /**
     * Callback do Google: troca o code por token, obtém userinfo, encontra/cria account, emite JWT.
     * Resposta JSON com accessToken e roles (para uso em Authorization: Bearer &lt;token&gt;).
     */
    @GetMapping("/google/callback")
    public Mono<ResponseEntity<LoginResponse>> googleCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = STATE_COOKIE_NAME, required = false) String expectedState,
            ServerWebExchange exchange) {
        Optional<Long> linkAccountId = googleLinkStateService.accountForCallback(state, expectedState);
        if (!isValidState(state, expectedState) && linkAccountId.isEmpty()) {
            return Mono.error(ApiException.badRequest("Invalid OAuth state"));
        }

        Mono<LoginResponse> completion = linkAccountId
                .map(accountId -> authService.completeGoogleLink(accountId, code))
                .orElseGet(() -> authService.loginWithGoogle(code));

        return abuseRateLimiter.checkLogin(clientAddress(exchange))
                .then(completion)
                .doOnError(error -> exchange.getResponse().addCookie(clearStateCookie()))
                .map(response -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearStateCookie().toString())
                        .body(response));
    }

    /** Starts explicit Google linking after re-authenticating the local account password. */
    @PostMapping("/google/link/authorize")
    @SecurityRequirement(name = "bearerAuth")
    public Mono<ResponseEntity<GoogleLinkAuthorizationResponse>> authorizeGoogleLink(
            ServerWebExchange exchange,
            @Valid @RequestBody GoogleLinkAuthorizationRequest request) {
        if (googleProperties.getClientId() == null || googleProperties.getClientId().isBlank()) {
            return Mono.error(ApiException.badRequest("Google OAuth2 is not configured"));
        }
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> loginRateLimiter.checkAddress("login-ip:" + clientAddress(exchange))
                        .then(loginRateLimiter.checkIdentity("google-link:" + accountId))
                        .then(Mono.defer(() -> authService.verifyPasswordForGoogleLink(accountId, request.password())))
                        .then(Mono.fromSupplier(() -> googleLinkStateService.issue(accountId)))
                        .map(issued -> ResponseEntity.ok()
                                .header(HttpHeaders.SET_COOKIE, stateCookie(issued.cookieValue()).toString())
                                .body(new GoogleLinkAuthorizationResponse(
                                        googleProperties.buildAuthorizationUrl(issued.state())))));
    }

    private ResponseCookie stateCookie(String cookieValue) {
        return ResponseCookie.from(STATE_COOKIE_NAME, cookieValue)
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
