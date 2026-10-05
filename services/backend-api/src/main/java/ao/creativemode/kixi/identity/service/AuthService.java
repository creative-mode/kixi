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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

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
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Invalid username or password")))
                .filter(Account::getActive)
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Account is inactive")))
                .filter(account -> passwordEncoder.matches(password, account.getPasswordHash()))
                .switchIfEmpty(Mono.error(ApiException.unauthorized("Invalid username or password")))
                .flatMap(account -> recordLogin(account)
                        .flatMap(updated -> loadRoleNames(updated.getId())
                                .map(roles -> buildLoginResponse(updated.getId(), roles))));
    }

    @Transactional
    public Mono<LoginResponse> register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        return accountRepository.findByUsernameAndDeletedAtIsNull(username)
                .flatMap(existing -> Mono.<Account>error(
                        ApiException.conflict("Username already in use")))
                .switchIfEmpty(Mono.defer(() -> accountRepository.findByEmailAndDeletedAtIsNull(email)
                        .flatMap(existing -> Mono.<Account>error(
                                ApiException.conflict("Email already in use")))))
                .then(Mono.defer(() -> roleRepository.findByNameAndDeletedAtIsNull(DEFAULT_ROLE_NAME)
                        .switchIfEmpty(Mono.error(ApiException.conflict(
                                "Default role is not configured: " + DEFAULT_ROLE_NAME)))
                        .flatMap(role -> {
                            Account account = new Account();
                            account.setUsername(username);
                            account.setEmail(email);
                            account.setPasswordHash(passwordEncoder.encode(request.password()));
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
                        })));
    }

    private Mono<Account> findAccountByUsernameOrEmail(String input) {
        return accountRepository.findByUsernameAndDeletedAtIsNull(input)
                .switchIfEmpty(accountRepository.findByEmailAndDeletedAtIsNull(input));
    }

    private Mono<Account> recordLogin(Account account) {
        account.setLastLogin(java.time.LocalDateTime.now());
        return accountRepository.save(account);
    }

    private Mono<List<String>> loadRoleNames(Long accountId) {
        return accountRoleRepository.findByAccountIdAndDeletedAtIsNull(accountId)
                .flatMap(ar -> roleRepository.findById(ar.getRoleId()))
                .filter(role -> role.getDeletedAt() == null)
                .map(Role::getName)
                .collect(Collectors.toList());
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

    private Mono<Account> findOrCreateAccountFromGoogle(GoogleUserInfo info) {
        if (info.email() == null || info.email().isBlank()) {
            return Mono.error(ApiException.badRequest("Google did not provide email"));
        }
        String email = info.email().trim().toLowerCase();
        return accountRepository.findByEmailAndDeletedAtIsNull(email)
                .switchIfEmpty(createAccountAndUserFromGoogle(email, info));
    }

    private Mono<Account> createAccountAndUserFromGoogle(String email, GoogleUserInfo info) {
        String username = email.split("@")[0];
        String passwordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        Account account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        account.setPasswordHash(passwordHash);
        account.setEmailVerified(true);
        account.setActive(true);
        account.setDeletedAt(null);

        return roleRepository.findByNameAndDeletedAtIsNull(DEFAULT_ROLE_NAME)
                .switchIfEmpty(Mono.error(ApiException.conflict(
                        "Default role is not configured: " + DEFAULT_ROLE_NAME
                )))
                .flatMap(role -> accountRepository.save(account)
                        .onErrorMap(DuplicateKeyException.class,
                                e -> ApiException.conflict(
                                        "An account with this email or username already exists"))
                        .flatMap(savedAccount -> Mono.when(
                                accountRoleRepository.save(
                                        new AccountRole(savedAccount.getId(), role.getId())
                                ),
                                createUserFromGoogle(savedAccount.getId(), info)
                        ).thenReturn(savedAccount)));
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
