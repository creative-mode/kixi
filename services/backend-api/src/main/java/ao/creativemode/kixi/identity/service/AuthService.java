package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.auth.LoginResponse;
import ao.creativemode.kixi.identity.dto.auth.RegisterRequest;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.identity.service.GoogleOAuth2Client.GoogleUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String DEFAULT_ROLE_NAME = "STUDENT";

    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuth2Client googleOAuth2Client;

    public AuthService(AccountRepository accountRepository,
                       AccountRoleRepository accountRoleRepository,
                       RoleRepository roleRepository,
                       UserRepository userRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       GoogleOAuth2Client googleOAuth2Client) {
        this.accountRepository = accountRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuth2Client = googleOAuth2Client;
    }

    public Mono<LoginResponse> login(String usernameOrEmail, String password) {
        // Rejected credentials are an authentication failure (401), not a
        // malformed request (400): the caller authenticated fine, the
        // username/password pair did not. Answering 400 here also let clients
        // tell "no such account" apart from "wrong password", since the same
        // 400 is used for genuine payload validation errors on this endpoint.
        return findAccountByUsernameOrEmail(usernameOrEmail.trim())
                .switchIfEmpty(Mono.error(() -> ApiException.unauthorized("Invalid username or password")))
                .filter(Account::getActive)
                .switchIfEmpty(Mono.error(() -> ApiException.unauthorized("Account is inactive")))
                .filterWhen(account -> passwordMatches(password, account.getPasswordHash()))
                .switchIfEmpty(Mono.error(() -> ApiException.unauthorized("Invalid username or password")))
                // Recording the login and reading the roles do not depend on each other: one round trip of waiting, not two.
                .flatMap(account -> Mono.zip(recordLogin(account), loadRoleNames(account.getId()))
                        .map(done -> buildLoginResponse(done.getT1().getId(), done.getT2())));
    }

    @Transactional
    public Mono<LoginResponse> register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        return accountRepository.findByUsernameAndDeletedAtIsNull(username)
                .flatMap(existing -> Mono.<Account>error(
                        ApiException.conflict("Username or email already in use")))
                .switchIfEmpty(Mono.defer(() -> accountRepository.findByEmailAndDeletedAtIsNull(email)
                        .flatMap(existing -> Mono.<Account>error(
                                ApiException.conflict("Username or email already in use")))))
                .then(Mono.defer(() -> roleRepository.findByNameAndDeletedAtIsNull(DEFAULT_ROLE_NAME)
                        .switchIfEmpty(Mono.error(ApiException.internalError(
                                "Default role is not configured: " + DEFAULT_ROLE_NAME)))
                        .flatMap(role -> encodePassword(request.password()).flatMap(passwordHash -> {
                            Account account = new Account();
                            account.setUsername(username);
                            account.setEmail(email);
                            account.setPasswordHash(passwordHash);
                            account.setEmailVerified(false);
                            account.setActive(true);
                            account.setDeletedAt(null);

                            return accountRepository.save(account)
                                    .onErrorMap(DuplicateKeyException.class,
                                            error -> ApiException.conflict(
                                                    "An account with this email or username already exists"))
                                    .flatMap(savedAccount -> {
                                        User user = new User();
                                        user.setAccountId(savedAccount.getId());
                                        user.setFirstName(request.firstName().trim());
                                        user.setLastName(request.lastName().trim());
                                        user.setDeletedAt(null);

                                        return Mono.when(
                                                accountRoleRepository.save(
                                                        new AccountRole(savedAccount.getId(), role.getId())),
                                                userRepository.save(user)
                                        ).thenReturn(savedAccount);
                                    })
                                    .map(savedAccount -> buildLoginResponse(
                                            savedAccount.getId(), List.of(DEFAULT_ROLE_NAME)));
                        }))));
    }

    /**
     * BCrypt costs ~100 ms of CPU on purpose. On the thread that delivered the database
     * row — an R2DBC I/O thread shared by every connection — it stalled all other
     * requests while it ran; under load, logins froze the whole API (#124).
     */
    private Mono<Boolean> passwordMatches(String rawPassword, String passwordHash) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.fromCallable(() -> passwordEncoder.matches(rawPassword, passwordHash))
                    .subscribeOn(Schedulers.boundedElastic())
                    // From subscription to the answer, so it includes waiting for a free thread when the CPU is saturated: that
                    // wait is what a class logging in together pays, and what the #566 measurements could not see (kixi#566).
                    .doFinally(signal -> logIfSlow("bcrypt", started));
        });
    }

    /** Above this, a phase of the login is logged: it is the slow login the issue could not explain from outside. */
    static final long SLOW_PHASE_MS = 750;

    static void logIfSlow(String phase, long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000;
        if (ms >= SLOW_PHASE_MS) {
            log.warn("Slow login phase: phase={} durationMs={}", phase, ms);
        }
    }

    /** Same reason as {@link #passwordMatches}: hashing stays off the I/O threads. */
    private Mono<String> encodePassword(String rawPassword) {
        return Mono.fromCallable(() -> passwordEncoder.encode(rawPassword))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Account> findAccountByUsernameOrEmail(String input) {
        // One query for both, not a username lookup and then an e-mail one: a student who logs in with their e-mail
        // (the usual case) paid two round trips. An exact username match still wins (kixi#566).
        return accountRepository.findForLogin(input, input.toLowerCase(Locale.ROOT));
    }

    private Mono<Account> recordLogin(Account account) {
        account.setLastLogin(java.time.LocalDateTime.now());
        return accountRepository.save(account);
    }

    private Mono<List<String>> loadRoleNames(Long accountId) {
        // One join instead of the grants and then one role lookup per grant (kixi#566).
        return roleRepository.findActiveNamesByAccountId(accountId).collect(Collectors.toList());
    }

    private LoginResponse buildLoginResponse(Long accountId, List<String> roles) {
        String token = jwtService.generateToken(accountId, roles);
        Instant expiresAt = Instant.now().plusMillis(jwtService.getExpirationMs());
        return new LoginResponse(token, LoginResponse.TOKEN_TYPE, expiresAt, accountId, roles);
    }

    /**
     * Login via Google OAuth2: troca o code por token, obtém userinfo, encontra ou cria Account/User, atribui role padrão, emite JWT.
     */
    public Mono<LoginResponse> loginWithGoogle(String code) {
        return googleOAuth2Client.exchangeCodeForAccessToken(code)
                .flatMap(googleOAuth2Client::getUserInfo)
                .flatMap(this::findOrCreateAccountFromGoogle)
                .flatMap(account -> recordLogin(account)
                        .flatMap(updated -> loadRoleNames(updated.getId())
                                .map(roles -> buildLoginResponse(updated.getId(), roles))));
    }

    /** Rechecks the local password before beginning a Google identity-link flow. */
    public Mono<Void> verifyPasswordForGoogleLink(Long accountId, String password) {
        return accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .filter(account -> Boolean.TRUE.equals(account.getActive()))
                .filterWhen(account -> passwordMatches(password, account.getPasswordHash()))
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Invalid account credentials")))
                .then();
    }

    /** Completes the explicit, password-confirmed link started by the signed OAuth state. */
    public Mono<LoginResponse> completeGoogleLink(Long accountId, String code) {
        return googleOAuth2Client.exchangeCodeForAccessToken(code)
                .flatMap(googleOAuth2Client::getUserInfo)
                .flatMap(this::requireVerifiedGoogleIdentity)
                .flatMap(info -> accountRepository.findByIdAndDeletedAtIsNull(accountId)
                        .filter(account -> Boolean.TRUE.equals(account.getActive()))
                        .filter(account -> account.getEmail() != null
                                && account.getEmail().equalsIgnoreCase(info.email().trim()))
                        .switchIfEmpty(Mono.error(ApiException.unauthorized(
                                "Google identity does not match the signed-in account")))
                        .flatMap(account -> accountRepository.findByGoogleSubAndDeletedAtIsNull(info.sub())
                                .flatMap(linked -> linked.getId().equals(accountId)
                                        ? Mono.just(account)
                                        : Mono.error(ApiException.conflict("Google identity is already linked")))
                                .switchIfEmpty(Mono.defer(() -> {
                                    if (account.getGoogleSub() != null
                                            && !account.getGoogleSub().equals(info.sub())) {
                                        return Mono.error(ApiException.conflict(
                                                "A different Google identity is already linked"));
                                    }
                                    account.setGoogleSub(info.sub());
                                    account.setEmailVerified(true);
                                    return accountRepository.save(account)
                                            .onErrorMap(DuplicateKeyException.class,
                                                    error -> ApiException.conflict(
                                                            "Google identity is already linked"));
                                }))))
                .flatMap(account -> recordLogin(account)
                        .flatMap(updated -> loadRoleNames(updated.getId())
                                .map(roles -> buildLoginResponse(updated.getId(), roles))));
    }

    private Mono<Account> findOrCreateAccountFromGoogle(GoogleUserInfo info) {
        if (info.email() == null || info.email().isBlank()) {
            return Mono.error(ApiException.badRequest("Google did not provide email"));
        }
        if (!isValidGoogleEmail(info.email())) {
            return Mono.error(ApiException.badRequest("Google did not provide a valid email"));
        }
        if (!info.emailVerified() || info.sub() == null || info.sub().isBlank()) {
            return Mono.error(ApiException.unauthorized("Google must provide a verified email identity"));
        }
        String email = info.email().trim().toLowerCase(Locale.ROOT);
        return accountRepository.findByGoogleSubAndDeletedAtIsNull(info.sub())
                .flatMap(account -> Boolean.TRUE.equals(account.getActive())
                        ? Mono.just(account)
                        : Mono.error(ApiException.unauthorized("Google account is inactive")))
                .switchIfEmpty(Mono.defer(() -> {
                    Mono<Account> deletedGoogleIdentity = accountRepository
                            .findByGoogleSubAndDeletedAtIsNotNull(info.sub())
                            .flatMap(deleted -> Mono.<Account>error(ApiException.conflict(
                                    "Google identity belongs to a deleted account")));
                    return deletedGoogleIdentity.switchIfEmpty(Mono.defer(() -> findOrCreateByGoogleEmail(email, info)));
                }));
    }

    private Mono<Account> findOrCreateByGoogleEmail(String email, GoogleUserInfo info) {
        return accountRepository.findByEmailAndDeletedAtIsNull(email)
                .flatMap(existing -> {
                    if (!Boolean.TRUE.equals(existing.getActive())
                            || !Boolean.TRUE.equals(existing.getEmailVerified())
                            || (existing.getGoogleSub() != null && !existing.getGoogleSub().equals(info.sub()))) {
                        return Mono.error(ApiException.unauthorized(
                                "Google identity is not linked to this account"));
                    }
                    existing.setGoogleSub(info.sub());
                    return accountRepository.save(existing)
                            .onErrorMap(DuplicateKeyException.class,
                                    error -> ApiException.conflict("Google identity is already linked"));
                })
                .switchIfEmpty(Mono.defer(() -> accountRepository.findByEmailAndDeletedAtIsNotNull(email)
                        .flatMap(deleted -> Mono.<Account>error(ApiException.conflict(
                                "Google email belongs to a deleted account")))
                        .switchIfEmpty(Mono.defer(() -> createAccountAndUserFromGoogle(email, info)))));
    }

    private Mono<Account> createAccountAndUserFromGoogle(String email, GoogleUserInfo info) {
        String preferredUsername = email.substring(0, email.indexOf('@'));
        String normalizedUsername = preferredUsername.substring(0, Math.min(100, preferredUsername.length()));
        Mono<String> username = accountRepository.findByUsername(normalizedUsername)
                .flatMap(existing -> {
                    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                    String base = normalizedUsername.substring(0, Math.min(89, normalizedUsername.length()));
                    return Mono.just(base + "-" + suffix);
                })
                .switchIfEmpty(Mono.just(normalizedUsername));

        return username.flatMap(candidate -> roleRepository.findByNameAndDeletedAtIsNull(DEFAULT_ROLE_NAME)
                .switchIfEmpty(Mono.error(() -> ApiException.conflict(
                        "Default role is not configured: " + DEFAULT_ROLE_NAME
                )))
                .flatMap(role -> encodePassword(UUID.randomUUID().toString())
                        .flatMap(passwordHash -> {
                            Account account = new Account();
                            account.setEmail(email);
                            account.setGoogleSub(info.sub());
                            account.setPasswordHash(passwordHash);
                            account.setEmailVerified(true);
                            account.setActive(true);
                            account.setDeletedAt(null);
                            account.setUsername(candidate);
                            return accountRepository.save(account)
                                    .onErrorMap(DuplicateKeyException.class,
                                            error -> ApiException.conflict("Google account could not be created"))
                                    .flatMap(savedAccount -> Mono.when(
                                            accountRoleRepository.save(
                                                    new AccountRole(savedAccount.getId(), role.getId())
                                            ),
                                            createUserFromGoogle(savedAccount.getId(), info)
                                    ).thenReturn(savedAccount));
                        })));
    }

    private Mono<GoogleUserInfo> requireVerifiedGoogleIdentity(GoogleUserInfo info) {
        if (info.email() == null || info.email().isBlank()) {
            return Mono.error(ApiException.badRequest("Google did not provide email"));
        }
        if (!isValidGoogleEmail(info.email())) {
            return Mono.error(ApiException.badRequest("Google did not provide a valid email"));
        }
        if (!info.emailVerified() || info.sub() == null || info.sub().isBlank()) {
            return Mono.error(ApiException.unauthorized("Google must provide a verified email identity"));
        }
        return Mono.just(info);
    }

    private boolean isValidGoogleEmail(String value) {
        String email = value.trim();
        int separator = email.lastIndexOf('@');
        return separator > 0
                && separator < email.length() - 1
                && email.chars().noneMatch(Character::isWhitespace);
    }

    private Mono<User> createUserFromGoogle(Long accountId, GoogleUserInfo info) {
        String[] names = info.name() != null && !info.name().isBlank()
                ? info.name().trim().split("\\s+", 2)
                : new String[]{"", ""};
        String firstName = names[0];
        String lastName = names.length > 1 ? names[1] : "";
        User user = new User();
        user.setAccountId(accountId);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoto(info.picture());
        user.setDeletedAt(null);
        return userRepository.save(user);
    }
}
